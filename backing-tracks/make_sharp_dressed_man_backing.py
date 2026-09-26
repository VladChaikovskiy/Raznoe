"""Синтезирует минусовку (без гитары) в духе "Sharp Dressed Man" (ZZ Top).

Это не оригинальная запись: барабаны, бас и клавишный пэд сгенерированы
с нуля по гармонии песни (тональность C, ~126 BPM).

    pip install numpy lameenc
    python make_sharp_dressed_man_backing.py
"""
import numpy as np
import lameenc

SR = 44100
BPM = 126
BEAT = 60 / BPM
BAR = 4 * BEAT
rng = np.random.default_rng(7)

NOTE = {"C": 0, "C#": 1, "D": 2, "Eb": 3, "E": 4, "F": 5, "F#": 6,
        "G": 7, "Ab": 8, "A": 9, "Bb": 10, "B": 11}


def freq(name, octave):
    midi = 12 * (octave + 1) + NOTE[name]
    return 440.0 * 2 ** ((midi - 69) / 12)


def env(n, attack, decay_tau):
    t = np.arange(n) / SR
    a = np.minimum(1.0, t / max(attack, 1e-4))
    return a * np.exp(-t / decay_tau)


# ---------- инструменты ----------
def kick():
    n = int(0.35 * SR)
    t = np.arange(n) / SR
    f = 50 + 90 * np.exp(-t / 0.03)
    phase = 2 * np.pi * np.cumsum(f) / SR
    click = rng.standard_normal(n) * np.exp(-t / 0.002) * 0.3
    return (np.sin(phase) * np.exp(-t / 0.12) + click) * 1.0


def snare():
    n = int(0.3 * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    noise = np.diff(noise, prepend=0) * 0.6 + noise * 0.4
    body = np.sin(2 * np.pi * 185 * t) * np.exp(-t / 0.05)
    return (noise * np.exp(-t / 0.09) * 0.55 + body * 0.6) * 0.8


def hat(open_=False):
    n = int((0.25 if open_ else 0.06) * SR)
    t = np.arange(n) / SR
    noise = np.diff(np.diff(rng.standard_normal(n), prepend=0), prepend=0)
    return noise * np.exp(-t / (0.08 if open_ else 0.015)) * 0.12


def crash():
    n = int(1.8 * SR)
    t = np.arange(n) / SR
    noise = np.diff(rng.standard_normal(n), prepend=0)
    return noise * np.exp(-t / 0.6) * 0.25


def bass_note(f, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    sig = np.zeros(n)
    for k in range(1, 12):  # пилообразная волна с завалом верхов
        sig += np.sin(2 * np.pi * f * k * t) / k * np.exp(-(k - 1) * 0.35)
    e = np.minimum(1, t / 0.004) * np.exp(-t / 0.6)
    rel = np.minimum(1, (n - np.arange(n)) / (0.012 * SR))
    return np.tanh(sig * 1.5) * e * rel * 0.45


def pad_chord(root, quality, dur):
    """Мягкий органный аккорд, чтобы слышать гармонию."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    third = 3 if quality == "m" else 4
    base = freq(root, 3)
    sig = np.zeros(n)
    for semis in (0, third, 7, 12):
        f = base * 2 ** (semis / 12)
        for detune in (-0.12, 0.12):
            ff = f * 2 ** (detune / 1200 * 100)
            sig += np.sin(2 * np.pi * ff * t) + 0.3 * np.sin(2 * np.pi * 2 * ff * t)
    a = np.minimum(1, t / 0.08)
    rel = np.minimum(1, (n - np.arange(n)) / (0.08 * SR))
    return sig * a * rel * 0.035


# ---------- форма песни ----------
# (секция, аккорды по тактам, играет ли пэд, партия барабанов)
def bars(chords, times=1):
    return chords * times


CHORUS = bars(["Bb", "F", "C", "C"], 2)
FORM = [
    ("count", ["-"], False, "count"),
    ("drum intro", ["-"] * 2, False, "beat"),
    ("intro riff", ["C"] * 8, False, "beat"),
    ("verse 1", ["C"] * 8, True, "beat"),
    ("chorus 1", CHORUS, True, "beat"),
    ("riff", ["C"] * 4, False, "beat"),
    ("verse 2", ["C"] * 8, True, "beat"),
    ("chorus 2", CHORUS, True, "beat"),
    ("solo", ["C"] * 16, True, "beat"),
    ("verse 3", ["C"] * 8, True, "beat"),
    ("chorus 3", CHORUS, True, "beat"),
    ("outro solo", ["C"] * 16, True, "beat"),
    ("end", ["C"], True, "end"),
]

total_bars = sum(len(c) for _, c, _, _ in FORM)
out = np.zeros(int((total_bars * BAR + 3) * SR) + SR)
L, R = out.copy(), out.copy()

K, S, CR = kick(), snare(), crash()
H, HO = hat(), hat(True)
bass_cache, pad_cache = {}, {}


def put(buf, sample, t, gain=1.0):
    i = int(t * SR)
    j = min(len(buf), i + len(sample))
    buf[i:j] += sample[: j - i] * gain


def put_st(sample, t, gain=1.0, pan=0.0):
    put(L, sample, t, gain * (1 - pan) / 2 * 1.4)
    put(R, sample, t, gain * (1 + pan) / 2 * 1.4)


# Басовая партия на тонике аккорда: ровные восьмые с движением в духе буги.
BASS_PATTERN = [0, 0, 0, 0, 0, 0, 10 - 12, 0]  # полутона от тоники

t = 0.0
bar_idx = 0
for name, chords, pad_on, drums in FORM:
    for ci, ch in enumerate(chords):
        bar_start = t
        first_of_section = ci == 0
        if drums == "count":
            for b in range(4):
                put_st(HO if b == 3 else H, bar_start + b * BEAT, 2.0)
        elif drums == "end":
            put_st(K, bar_start, 1.0)
            put_st(S, bar_start, 0.8)
            put_st(CR, bar_start, 1.0, 0.3)
            put(L, bass_note(freq("C", 2), 2.5), bar_start, 0.7)
            put(R, bass_note(freq("C", 2), 2.5), bar_start, 0.7)
            put_st(pad_chord("C", "", 3.0), bar_start, 1.0)
        else:
            if first_of_section and name != "drum intro":
                put_st(CR, bar_start, 0.8, 0.3)
            for b in range(4):
                bt = bar_start + b * BEAT
                put_st(K if b in (0, 2) else S, bt, 0.9 if b in (0, 2) else 0.75)
                if b == 2 and bar_idx % 2:
                    put_st(K, bt + BEAT / 2, 0.6)  # подкачка бочки
                for e in range(2):
                    put_st(H, bt + e * BEAT / 2, 1.0 if e == 0 else 0.7, -0.35)
            # сбивка на последнем такте секции
            if ci == len(chords) - 1 and name not in ("count",):
                for s in range(4):
                    put_st(S, bar_start + 3 * BEAT + s * BEAT / 4, 0.35 + 0.1 * s, 0.1)

            if ch != "-":
                root = freq(ch, 1 if NOTE[ch] >= NOTE["F"] else 2)
                for e, semis in enumerate(BASS_PATTERN):
                    f = root * 2 ** (semis / 12)
                    key = round(f, 2)
                    if key not in bass_cache:
                        bass_cache[key] = bass_note(f, BEAT / 2 * 0.92)
                    s = bass_cache[key]
                    put(L, s, bar_start + e * BEAT / 2)
                    put(R, s, bar_start + e * BEAT / 2)
                if pad_on:
                    if ch not in pad_cache:
                        pad_cache[ch] = pad_chord(ch, "", BAR)
                    put_st(pad_cache[ch], bar_start, 1.0, 0.25)
        t += BAR
        bar_idx += 1

# мастеринг: мягкая компрессия и нормализация
mix = np.stack([L, R], axis=1)
end = int((t + 3) * SR)
mix = mix[:end]
mix = np.tanh(mix * 1.2)
mix /= np.max(np.abs(mix)) + 1e-9
mix *= 0.89
pcm = (mix * 32767).astype(np.int16)

enc = lameenc.Encoder()
enc.set_bit_rate(192)
enc.set_in_sample_rate(SR)
enc.set_channels(2)
enc.set_quality(2)
data = enc.encode(pcm.tobytes()) + enc.flush()
fname = "Sharp_Dressed_Man_backing_track_C_126bpm.mp3"
with open(fname, "wb") as f:
    f.write(data)
print(f"{fname}: {len(pcm) / SR:.1f} s, {len(data) / 1e6:.2f} MB")
