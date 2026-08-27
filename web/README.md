# Tanpura — web version

The same tanpura as the Android app, running in a browser. Open it on a phone and
it behaves like an app; open it on a laptop and it lays itself out as a desktop
tool. One deployment, one URL, no install required.

Live-testable without any toolchain, which is the point: the Android build needs
Google's Maven repo, and this does not.

---

## Deploying to Vercel

There is **no build step**. The whole thing is static files plus one generated
worklet, so Vercel just serves the directory.

### From the dashboard

1. Push this repository to GitHub.
2. In Vercel: **Add New → Project**, import the repo.
3. Set **Root Directory** to `web`. This matters — the repository root is the
   Android app.
4. Framework Preset: **Other**. Leave Build Command and Output Directory empty.
5. Deploy. You get `https://<project>.vercel.app`.

### From the CLI

```bash
npm i -g vercel
cd web
vercel            # preview deployment
vercel --prod     # production
```

### After deploying

Open the URL on your phone and add it to the home screen — Chrome's *Add to Home
screen*, or Safari's *Share → Add to Home Screen*. It then launches full screen,
keeps its own icon, and works offline.

## Running it locally

Any static server works, but it must be `http://localhost` or HTTPS: browsers only
allow `AudioWorklet` and service workers in a secure context, and `file://` is not
one.

```bash
cd web
python -m http.server 8777
# then open http://127.0.0.1:8777/
```

## Layout

```
web/
├── index.html               the shell
├── style.css                both layouts, split at 900px
├── manifest.webmanifest     installable-app metadata
├── sw.js                    offline cache
├── vercel.json              headers (sw.js must not be cached)
├── tanpura-worklet.js       GENERATED - do not edit
├── build-worklet.mjs        regenerates the above
├── icons/
├── src/
│   ├── engine.js            the DSP and the music model
│   ├── worklet-tail.js      AudioWorkletProcessor glue
│   ├── instrument.js        the animated canvas
│   ├── ui.js                DOM control builders
│   └── app.js               audio graph, state, screens
└── test/engine.test.mjs     26 tests, no dependencies
```

### Why the worklet is generated

An `AudioWorklet` is loaded as a single script, and `import` inside worklet scope
is not reliably supported across browsers. Rather than keep a second copy of the
DSP, `build-worklet.mjs` concatenates `src/engine.js` with `src/worklet-tail.js`
and strips the `export` keywords. `engine.js` is written to make that one regex
sufficient: every export is inline on its declaration, and there are no imports.

**After editing `src/engine.js` or `src/worklet-tail.js`, run:**

```bash
node web/build-worklet.mjs
```

The generated file is committed so the deployment needs no build step. The
generator refuses to write if it finds an `import` or an `export {...}` block it
cannot strip.

## Tests

```bash
node --test web/test/engine.test.mjs
```

26 tests, zero dependencies, using Node's built-in runner. They mirror the Kotlin
tests for the Android app, because the two engines implement the same algorithm
and a port is exactly where a subtle numeric difference would hide. The important
one sweeps every semitone from C1 to C5 and asserts the rendered pitch lands
within **1 cent** of target.

## Browser check

`tools/webcheck/check.mjs` (in the repo root, not here) drives the real thing in
Chromium: it loads the page, starts the audio, walks every tab, opens the modals,
drags every slider, switches between the phone and laptop viewports, and reports
console errors, failed requests and screenshots.

```bash
cd web && python -m http.server 8777 &
cd tools/webcheck && npm install
node tools/webcheck/check.mjs
```

It found four real bugs during development that reading the code had not: a
`clip-path` pause icon that rendered as an X, `scrollIntoView` on a chip silently
scrolling the whole control panel and hiding the first card, an author
`display: flex` overriding `[hidden]` so collapsible cards never collapsed, and an
instrument drawing whose geometry stretched with its container.

## Differences from the Android app

| | Android | Web |
| --- | --- | --- |
| Engine | identical model, Kotlin | identical model, JS in an AudioWorklet |
| Background playback | foreground service, fully reliable | best-effort; the browser may suspend a hidden tab |
| Lock-screen controls | MediaSession notification | Media Session API, Android Chrome only |
| Long-recording loop | streamed with pitch shift | `<audio loop>` with `playbackRate` |
| Per-string sample import | yes | not yet |
| Install | APK | Add to Home Screen |

For an hour of riyaaz with the screen off, the native app is the better tool. The
web one is for testing, sharing a link, and casual use.

## Privacy

No network requests after load, no analytics, no accounts, no cookies. Settings
and presets live in `localStorage` on your device. A recording you load stays a
local `blob:` URL and is never uploaded.
