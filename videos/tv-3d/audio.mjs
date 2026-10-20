// Светлый саундтрек: мажорные аккорды C–G–Am–F, «маримба»-арпеджио, мягкий бит,
// «вжух» на каждом быстром повороте камеры и «поп» на сменах сцен.

const BPM = 112;
const POPS = [6.5, 22.5, 34.5, 42.5, 50.5];
// [начало, конец] быстрых поворотов камеры (совпадают с ключами 'whip' в scene.html)
const WHIPS = [
  [2.0, 2.8], [4.2, 5.0], [8.0, 8.8], [10.6, 11.4], [15.2, 16.0], [17.6, 18.3], [19.6, 20.3],
  [24.6, 25.4], [27.4, 28.2], [30.0, 30.8], [36.2, 37.0], [38.8, 39.6], [44.2, 45.0], [46.8, 47.6], [51.6, 53.6],
];

export function generate(duration, sr = 48000) {
  return makeTrack(duration, sr, { whips: WHIPS, pops: POPS, ticks: [7.7, 10.2] });
}

// Общий синтезатор: whips — интервалы рывков камеры, pops — смены сцен,
// ticks — интервал тиканья счётчика (или null).
export function makeTrack(duration, sr, { whips, pops, ticks }) {
  const n = Math.ceil(duration * sr);
  const L = new Float32Array(n), R = new Float32Array(n);
  const TAU = Math.PI * 2;
  const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
  const beat = 60 / BPM;
  const chordDur = beat * 8;
  const chords = [[60, 64, 67], [55, 59, 62], [57, 60, 64], [53, 57, 60]];
  const roots = [36, 31, 33, 29];

  let seed = 777;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff) * 2 - 1;
  let bp1 = 0, bp2 = 0, hpPrev = 0;

  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const ci = Math.floor(t / chordDur) % 4, pi = (ci + 3) % 4;
    const w = Math.min(1, (t % chordDur) / 0.6);
    let l = 0, r = 0;

    // мягкий пэд
    const bt = t % beat;
    const kick = t > 1.0 ? Math.exp(-bt * 9) : 0;
    const duck = 1 - 0.3 * kick;
    for (const [chord, k] of [[chords[ci], w], [chords[pi], 1 - w]]) {
      if (k <= 0) continue;
      for (const m of chord) {
        const f = mtof(m);
        l += (Math.sin(TAU * f * t) + 0.2 * Math.sin(TAU * 2 * f * t)) * 0.04 * k * duck;
        r += (Math.sin(TAU * f * 1.003 * t) + 0.2 * Math.sin(TAU * 2.006 * f * t)) * 0.04 * k * duck;
      }
    }

    // бас и мягкая бочка
    if (t > 1.0) {
      const fb = mtof(roots[ci]);
      const b = Math.sin(TAU * fb * t) * 0.16 * (0.5 + 0.5 * Math.exp(-bt * 3));
      const kf = 55 + 90 * Math.exp(-bt * 30);
      const kd = Math.sin(TAU * kf * bt) * Math.exp(-bt * 14) * 0.22;
      l += b + kd; r += b + kd;
    }

    // «маримба» восьмыми
    if (t > 0.6 && t < duration - 1.2) {
      const step = beat / 2;
      const si = Math.floor(t / step);
      const dt = t - si * step;
      const pattern = [0, 2, 1, 2, 0, 2, 1, 2];
      const m = chords[ci][pattern[si % 8]] + 12 + (si % 16 >= 12 ? 12 : 0);
      const f = mtof(m);
      const v = (Math.sin(TAU * f * dt) + 0.25 * Math.sin(TAU * 4 * f * dt) * Math.exp(-dt * 40)) * Math.exp(-dt * 9) * 0.085;
      const pan = 0.5 + 0.3 * Math.sin(si * 1.3);
      l += v * (1 - pan) * 1.6; r += v * pan * 1.6;
    }

    // шейкер на слабые доли
    const noise = rnd();
    if (t > 6.5 && t < duration - 1.2) {
      const off = (t + beat / 2) % beat;
      const hp = noise - hpPrev;
      const v = hp * Math.exp(-off * 45) * 0.03;
      l += v; r += v * 0.8;
    }
    hpPrev = noise;

    // «вжух»: полосовой шум, который разгоняется и тормозит вместе с камерой
    let wh = 0, center = 0.05;
    for (const [a, b] of whips) {
      if (t > a && t < b) {
        const x = (t - a) / (b - a);
        wh = Math.pow(Math.sin(Math.PI * x), 2);
        center = 0.03 + 0.12 * Math.sin(Math.PI * x);
      }
    }
    bp1 += center * (noise - bp1);
    bp2 += center * (bp1 - bp2);
    const ws = (bp1 - bp2) * wh * 1.4;
    l += ws; r += ws * 0.85;

    // «поп» на сменах сцен
    for (const T of pops) {
      const d = t - T + 0.12;
      if (d >= 0 && d < 0.25) {
        const f = 300 + 900 * (d / 0.25);
        const v = Math.sin(TAU * f * d) * Math.exp(-d * 18) * 0.25;
        l += v; r += v;
      }
    }

    // включение экрана
    if (t > 0.4 && t < 0.9) {
      const d = t - 0.4;
      const v = Math.sin(TAU * (500 + 700 * d) * d) * Math.exp(-d * 6) * 0.08 * Math.min(1, d * 40);
      l += v; r += v;
    }

    // тики счётчика пикселей
    if (ticks && t > ticks[0] && t < ticks[1]) {
      const dt = t % (1 / 22);
      const v = Math.sin(TAU * 2600 * dt) * Math.exp(-dt * 450) * 0.045;
      l += v; r += v;
    }

    const fade = Math.min(1, t / 0.05) * Math.min(1, (duration - t) / 1.2);
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
