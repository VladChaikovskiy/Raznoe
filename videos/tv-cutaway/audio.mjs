// Звук берём из общего синтезатора tv-3d, синхронизируя «вжухи» с рывками камеры этого ролика.
import { makeTrack } from '../tv-3d/audio.mjs';

const WHIPS = [
  [2.2, 3.0], [5.5, 6.4], [9.0, 9.8], [12.0, 12.9], [15.0, 15.8], [18.5, 19.3], [26.0, 26.8], [29.0, 29.7],
  [33.5, 34.3], [36.2, 36.9], [40.5, 41.3], [43.6, 44.4], [47.0, 48.2],
];
const POPS = [24, 31.5, 38.5];

export function generate(duration, sr = 48000) {
  return makeTrack(duration, sr, { whips: WHIPS, pops: POPS, ticks: null });
}
