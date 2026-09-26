"""Делает гитару тише в готовой записи, остальные инструменты не трогает.

    pip install torch demucs soundfile lameenc
    python lower_guitar.py song.mp3 [-12] [папка_с_весами]
"""
import subprocess
import sys
from pathlib import Path

import lameenc
import numpy as np
import soundfile as sf

src = Path(sys.argv[1])
gain_db = float(sys.argv[2]) if len(sys.argv) > 2 else -12.0
work = Path("separated")
repo = ["--repo", sys.argv[3]] if len(sys.argv) > 3 else []

subprocess.run([sys.executable, "-m", "demucs", "-n", "htdemucs_6s",
                "-o", str(work), *repo, str(src)], check=True)
stems_dir = work / "htdemucs_6s" / src.stem

mix, sr = None, None
for stem in ("vocals", "drums", "bass", "guitar", "piano", "other"):
    audio, sr = sf.read(stems_dir / f"{stem}.wav", dtype="float32")
    if stem == "guitar":
        audio *= 10 ** (gain_db / 20)
    mix = audio if mix is None else mix + audio

peak = np.max(np.abs(mix))
if peak > 0.99:
    mix *= 0.99 / peak
pcm = (mix * 32767).astype(np.int16)

enc = lameenc.Encoder()
enc.set_bit_rate(320)
enc.set_in_sample_rate(sr)
enc.set_channels(pcm.shape[1])
enc.set_quality(2)
out = src.with_name(f"{src.stem}_guitar{gain_db:+.0f}dB.mp3")
out.write_bytes(enc.encode(pcm.tobytes()) + enc.flush())
print(out)
