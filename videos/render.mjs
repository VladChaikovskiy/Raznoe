#!/usr/bin/env node
// Рендер ролика из HTML-сцены: Chromium (Playwright) рисует каждый кадр через
// window.renderAt(t), ffmpeg склеивает кадры и звук в MP4.
//
//   node videos/render.mjs videos/tv-explainer                 — полный рендер
//   node videos/render.mjs videos/tv-explainer --stills 2,9,15 — только кадры-превью (PNG)
//
// Сцены лежат в подпапках videos/ и отдаются локальным HTTP-сервером
// (для three.js нужен `npm install` в videos/).
//
// Сцена обязана выставить window.VIDEO = {width, height, fps, duration},
// window.renderAt(t) и window.READY = true. Если рядом лежит audio.mjs
// с export generate(duration, sampleRate) → {left, right}, звук добавится в ролик.

import { createRequire } from 'node:module';
import { execSync, spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);

function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    const root = execSync('npm root -g').toString().trim();
    return require(path.join(root, 'playwright'));
  }
}

function parseArgs(argv) {
  const args = { dir: null, stills: null, out: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--stills') args.stills = argv[++i].split(',').map(Number);
    else if (a === '--out') args.out = argv[++i];
    else args.dir = a;
  }
  if (!args.dir) {
    console.error('usage: node videos/render.mjs <project-dir> [--stills 1,2,3] [--out file.mp4]');
    process.exit(1);
  }
  args.dir = path.resolve(args.dir);
  return args;
}

function writeWav(file, left, right, sampleRate) {
  const n = left.length;
  const buf = Buffer.alloc(44 + n * 4);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 4, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); // PCM
  buf.writeUInt16LE(2, 22); // stereo
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 4, 28);
  buf.writeUInt16LE(4, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 4, 40);
  const s16 = (v) => Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
  for (let i = 0; i < n; i++) {
    buf.writeInt16LE(s16(left[i]), 44 + i * 4);
    buf.writeInt16LE(s16(right[i]), 46 + i * 4);
  }
  fs.writeFileSync(file, buf);
}

// Сцены отдаются по HTTP из папки videos/, чтобы работали ES-модули и import map
// (three.js из videos/node_modules).
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg' };
function serve(root) {
  const server = http.createServer((req, res) => {
    const file = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

const args = parseArgs(process.argv.slice(2));
const name = path.basename(args.dir);
const root = path.dirname(fileURLToPath(import.meta.url));
const server = await serve(root);
const sceneUrl = `http://127.0.0.1:${server.address().port}/${path.relative(root, args.dir)}/scene.html?render=1`;

const { chromium } = loadPlaywright();
const browser = await chromium.launch({
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
page.on('pageerror', (e) => console.error('page error:', e.message));
page.on('console', (m) => m.type() === 'error' && console.error('console:', m.text()));
await page.goto(sceneUrl);
await page.waitForFunction(() => window.READY === true, null, { timeout: 120000 });
const cfg = await page.evaluate(() => window.VIDEO);
await page.setViewportSize({ width: cfg.width, height: cfg.height });

if (args.stills) {
  const stillsDir = path.join(args.dir, 'stills');
  fs.mkdirSync(stillsDir, { recursive: true });
  for (const t of args.stills) {
    await page.evaluate((t) => window.renderAt(t), t);
    const file = path.join(stillsDir, `t${String(t).replace('.', '_')}.png`);
    await page.screenshot({ path: file });
    console.log(file);
  }
  await browser.close();
  server.close();
  process.exit(0);
}

const out = path.resolve(args.out ?? path.join(args.dir, `${name}.mp4`));
const ffArgs = ['-y', '-f', 'image2pipe', '-framerate', String(cfg.fps), '-i', '-'];

const audioModule = path.join(args.dir, 'audio.mjs');
let wav = null;
if (fs.existsSync(audioModule)) {
  const { generate } = await import(pathToFileURL(audioModule).href);
  const sampleRate = 48000;
  const { left, right } = generate(cfg.duration, sampleRate);
  wav = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'render-')), 'audio.wav');
  writeWav(wav, left, right, sampleRate);
  ffArgs.push('-i', wav);
}

ffArgs.push(
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p',
  '-profile:v', 'high', '-movflags', '+faststart',
);
if (wav) ffArgs.push('-c:a', 'aac', '-b:a', '192k', '-shortest');
ffArgs.push(out);

const ff = spawn('ffmpeg', ffArgs, { stdio: ['pipe', 'ignore', 'inherit'] });
const ffDone = new Promise((resolve, reject) =>
  ff.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}`)))),
);

const frames = Math.round(cfg.duration * cfg.fps);
const started = Date.now();
for (let i = 0; i < frames; i++) {
  await page.evaluate((t) => window.renderAt(t), i / cfg.fps);
  const png = await page.screenshot({ type: 'png' });
  if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once('drain', r));
  if (i % 60 === 0) {
    const sec = ((Date.now() - started) / 1000).toFixed(0);
    console.log(`frame ${i}/${frames} (${sec}s)`);
  }
}
ff.stdin.end();
await ffDone;
await browser.close();
server.close();
console.log(`done: ${out}`);
