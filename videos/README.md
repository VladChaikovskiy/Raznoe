# Видео из кода

Ролики для TikTok/Reels, которые полностью «нарисованы» кодом, как у
[@RyanSael](https://x.com/RyanSael). Нейросеть не генерирует видео напрямую: она пишет HTML-сцену,
которая рисует каждый кадр, затем Chromium снимает кадры, а ffmpeg собирает MP4.

```
scene.html  ──renderAt(t)──▶  Chromium (Playwright) ──PNG-кадры──▶  ffmpeg ──▶  ролик.mp4
audio.mjs   ──синтез звука──────────────────────────────────────────▲
```

## Ролики

| Папка | Тема | Формат |
|---|---|---|
| [`tv-explainer/`](tv-explainer/) | Как устроен современный телевизор | 1080×1920, 30 fps, 54 с |

## Как пересобрать

Нужны Node 18+, ffmpeg и Playwright (`npm i -g playwright && npx playwright install chromium`).

```bash
# полный рендер → videos/tv-explainer/tv-explainer.mp4
node videos/render.mjs videos/tv-explainer

# только несколько кадров-превью (секунды) → videos/tv-explainer/stills/
node videos/render.mjs videos/tv-explainer --stills 3,10,25
```

Живой предпросмотр: откройте `scene.html` в браузере, и анимация будет крутиться по кругу.

## Как сделать новый ролик

1. Скопируйте папку `tv-explainer/` под новым именем.
2. В `scene.html` задайте `window.VIDEO = {width, height, fps, duration}` и перепишите сцены.
   Главное правило: всё на экране вычисляется **только из времени `t`** (никаких
   `Date.now()` и `Math.random()`), тогда каждый кадр рендерится точно и без рывков.
3. `audio.mjs` (необязательно) экспортирует `generate(duration, sampleRate)` → `{left, right}`.
4. Запустите `render.mjs`.

## Безопасные зоны TikTok

Сверху около 200 px занимают вкладки, снизу около 420 px подпись и ник, справа около 140 px
кнопки. Важный текст держите в полосе y ≈ 220–1480.
