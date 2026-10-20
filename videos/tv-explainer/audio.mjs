// Саундтрек ролика, синтезированный кодом: пэд Am–F–C–G, бас-пульс, арпеджио,
// «вжухи» на сменах сцен, тики счётчика пикселей. Без внешних сэмплов.

const TRANSITIONS = [4.4, 12.2, 21, 33, 41, 49];
const BPM = 110;

export function generate(duration, sr = 48000) {
  const n = Math.ceil(duration * sr);
  const L = new Float32Array(n), R = new Float32Array(n);
  const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
  const TAU = Math.PI * 2;
  const beat = 60 / BPM;
  const chordDur = beat * 8;
  const chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]];
  const roots = [33, 29, 36, 31];

  let seed = 12345;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  let lp = 0, hpPrev = 0;

  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const ci = Math.floor(t / chordDur) % 4;
    const pi = (ci + 3) % 4;
    const w = Math.min(1, (t % chordDur) / 0.8);
    const intro = t < 4.4;

    // бас-пульс по долям и «качание» пэда от него
    const bt = t % beat;
    const bassEnv = intro ? 0 : Math.exp(-bt * 7);
    const duck = 1 - 0.45 * bassEnv;

    let l = 0, r = 0;
    // пэд
    const padAmp = 0.05 * (0.85 + 0.15 * Math.sin(t * 0.7)) * duck;
    for (const [chord, k] of [[chords[ci], w], [chords[pi], 1 - w]]) {
      if (k <= 0) continue;
      for (const m of chord) {
        const f = mtof(m);
        const vL = Math.sin(TAU * f * t) + 0.3 * Math.sin(TAU * 2 * f * t) + 0.12 * Math.sin(TAU * 3 * f * t);
        const vR = Math.sin(TAU * f * 1.004 * t) + 0.3 * Math.sin(TAU * 2.008 * f * t) + 0.12 * Math.sin(TAU * 3 * f * 1.002 * t);
        l += vL * padAmp * k; r += vR * padAmp * k;
      }
    }

    // бас
    if (!intro) {
      const fb = mtof(roots[ci] + 12);
      const b = (Math.sin(TAU * fb * t) + 0.4 * Math.sin(TAU * fb * 0.5 * t)) * 0.22 * bassEnv;
      l += b; r += b;
    }

    // арпеджио восьмыми
    if (t > 4.4 && t < duration - 1.5) {
      const step = beat / 2;
      const si = Math.floor(t / step);
      const dt = t - si * step;
      const tone = chords[ci][[0, 1, 2, 1][si % 4]] + 12 + (si % 8 >= 4 ? 12 : 0);
      const f = mtof(tone);
      const v = (Math.sin(TAU * f * dt) + 0.35 * Math.sin(TAU * 2 * f * dt)) * Math.exp(-dt * 12) * 0.07;
      const pan = si % 2 ? 0.75 : 0.25;
      l += v * (1 - pan) * 2 * 0.6; r += v * pan * 2 * 0.6;
    }

    // хэты на слабые доли после первой главы
    if (t > 12.2 && t < duration - 1.5) {
      const off = (t + beat / 2) % beat;
      const noise = rnd();
      const hp = noise - hpPrev; hpPrev = noise;
      const v = hp * Math.exp(-off * 70) * 0.035;
      l += v * 0.8; r += v;
    } else {
      rnd();
    }

    // «вжух» перед сменой сцены и удар на ней
    let wh = 0, boom = 0;
    for (const T of TRANSITIONS) {
      const d = t - T;
      if (d > -0.8 && d < 0.4) {
        const k = d < 0 ? Math.pow((d + 0.8) / 0.8, 2) : Math.exp(-d * 10);
        wh = Math.max(wh, k);
      }
      if (d >= 0 && d < 1.2) {
        const f = 70 * Math.exp(-d * 2) + 38;
        boom += Math.sin(TAU * f * d) * Math.exp(-d * 4.5) * 0.3;
      }
    }
    const a = 0.02 + 0.35 * wh;
    lp += a * (rnd() - lp);
    l += lp * wh * 0.5 + boom; r += lp * wh * 0.45 + boom;

    // включение ТВ: «вз-з-з» вверх
    if (t > 0.95 && t < 1.35) {
      const d = t - 0.95;
      const f = 180 + 2400 * d * d * 6;
      const v = Math.sin(TAU * f * d) * 0.08 * Math.sin(Math.PI * d / 0.4);
      l += v; r += v;
    }

    // тики счётчика пикселей
    if (t > 6.5 && t < 9.5) {
      const dt = t % (1 / 24);
      const v = Math.sin(TAU * 3200 * dt) * Math.exp(-dt * 500) * 0.05;
      l += v; r += v;
    }

    // плавные края
    const fade = Math.min(1, t / 0.05) * Math.min(1, (duration - t) / 1.4);
    L[i] = l * fade; R[i] = r * fade;
  }

  let peak = 0;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
  const g = 0.9 / (peak || 1);
  for (let i = 0; i < n; i++) {
    L[i] = Math.tanh(L[i] * g * 1.1) * 0.92;
    R[i] = Math.tanh(R[i] * g * 1.1) * 0.92;
  }
  return { left: L, right: R };
}
