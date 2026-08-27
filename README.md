# Tanpura

An Android tanpura for riyaaz, in the spirit of iTanpura on iOS: pick your Sa,
pick the first-string tuning the raga wants, set the strum speed, and let it run
for as long as you practise.

Two implementations of the same instrument:

- **`app/`** — native Android, Kotlin + Jetpack Compose, minSdk 26 (Android 8.0),
  targetSdk 35. The real tool: true background playback, lock-screen controls.
- **`web/`** — the same engine in an AudioWorklet, deployable to Vercel as a
  static site. Opens on any phone or laptop with no install and no toolchain, and
  adapts its layout to whichever you are on. See [web/README.md](web/README.md).

The web version exists because it needs nothing from Google's Maven repo, so it is
testable and shareable even on a network that blocks `dl.google.com` — which is
exactly the situation this was built in.

---

## What it does

**Pitch**
- Sa anywhere from C2 to C5, chosen by chevrons or from a scrolling strip of every pitch
- Fine tuning in half-cent steps, ±50 cents
- Adjustable concert pitch (A4 from 415 to 466 Hz) so the whole app can follow a
  harmonium that is not at 440

**Tuning (first string)**
Pa, Ma, teevra Ma, Ni, komal Ni, Dha and Ga, plus an all-Sa drone and two
five-string layouts. Offsets are measured from the middle Sa, so Pa is a fourth
*below* it (−5 semitones) exactly as on the instrument.

**Instrument**
Four voices — Male (Tanjore-ish, deep and slow), Female (Miraj-ish, brighter),
Instrumental (very deep, for sitar/sarod/bansuri) and Soft (nearly buzz-free for
long sittings). Each sets its own brightness, sustain, jawari depth, body
resonances and suggested Sa, and each is trimmable from the Tone panel.

**Performance**
- Strum speed from 0.9 to 8 seconds per cycle
- "Human feel" — per-cycle timing and velocity variation, turn it to zero for a
  mechanically even reference drone
- Per-string volume and mute (mute the first string and you have a shruti box)
- Practice timer with a fade-out, 1 to 180 minutes
- Named presets, plus five built-in starters
- Background playback with lock-screen and notification controls
- Sustained reference tones for all twelve swaras, for tuning your own instrument
- An animated instrument view whose strings flex as they are struck, in time with
  what you are hearing

## How the sound is made

The tanpura that ships with the app is **synthesised, not sampled**. Nothing is
recorded: there is not one byte of audio in the APK, it is perfectly in tune at
any pitch, and it never loops.

(The APK is about 13 MB all the same - that is Compose plus the extended Material
icon set, not audio. Enabling R8 in `app/build.gradle.kts` would cut most of it;
it is off by default here to keep the first build predictable.)

Each string is an extended Karplus-Strong waveguide: a delay line with a one-pole
loop filter, and three things on top that make it a tanpura rather than a generic
plucked string.

1. **Jawari.** On a real tanpura a cotton thread sits between the string and the
   curved bridge, so a loud string grazes the bridge and folds its own waveform —
   that is the shimmering bloom of overtones you hear on the attack. It is
   modelled as an amplitude-dependent soft waveshaper *inside* the feedback loop,
   normalised so its small-signal gain is exactly 1. That normalisation is
   load-bearing: any overshoot past unity and the string would self-oscillate.
2. **Tension modulation.** A hard-plucked string is stretched slightly further and
   starts a touch sharp, settling as it decays. A tiny amplitude-driven change to
   the delay length reproduces that.
3. **Pluck-position comb.** The excitation is comb-filtered, notching out the
   harmonics a finger at that position cannot excite.

The two middle Sa strings are detuned by a couple of cents against each other, so
they beat the way a real pair does. The summed strings pass through the
instrument's body resonances and a Freeverb-style room, because the overlapping
decays are what fuse into one continuous drone.

Tuning is computed, not approximated: the loop filter's phase delay at the
fundamental is derived in closed form and subtracted from the delay length. The
measured error over the app's whole range and all four voices is **0.13 cents
worst case, 0.08 cents mean** (see "Verifying" below).

### Two other sound sources

- **Your string recordings.** Import up to five short files, one per string, in
  strum order. The app detects each file's natural pitch by autocorrelation and
  resamples it to whatever Sa you pick, so the strum, speed and tuning controls
  all keep working.
- **Loop a long recording.** For a continuous tanpura recording of any length,
  including hours. The file is streamed from storage into a ring buffer and looped
  with a short fade across the seam, so a three-hour recording costs the same
  memory as a three-second one. A pitch-shift slider retunes it by resampling
  (which changes the speed too, like a tape machine).

Imported files are read from wherever they already live on your device through the
system file picker. Nothing is copied into the app and nothing leaves the phone.

> **On recordings you did not make.** Personal use of your own files is what the
> import feature is for. If you want to *ship or publish* a build with recordings
> baked in, use audio you recorded yourself, bought a licence for, or that is
> released CC0 — someone else's YouTube upload is their copyright, and a Play
> Store listing is distribution.

## Building

### The catch on a locked-down network

The Gradle build pulls the Android Gradle Plugin and all of AndroidX from Google's
Maven repo at `dl.google.com`. Some corporate networks block that host, in which
case `./gradlew` fails at configuration time with:

```
Plugin [id: 'com.android.application', version: '8.7.3'] was not found
```

Check with:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://dl.google.com/dl/android/maven2/
```

`200` means you are fine. `000` means the host is blocked, and you have three ways
forward:

1. **Build in CI** (easiest). Push the repo and GitHub Actions builds the APK for
   you — see below. No local network changes needed.
2. **Build off the corporate network** — a personal hotspot or a VPN that permits
   `dl.google.com` is enough. The dependencies are cached in `~/.gradle` after the
   first successful build, so you only need it once.
3. **Point at an internal mirror.** If your organisation runs an Artifactory or
   Nexus proxy of Google Maven, replace `google()` in `settings.gradle.kts` with
   `maven { url = uri("https://your-mirror/...") }`.

### Locally, once you have network access

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # or any JDK 17-21
./gradlew testDebugUnitTest      # run the audio engine tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

Or just open the folder in Android Studio and press Run.

### Installing on your phone

Over USB, with developer options and USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone, tap it, and allow installation from unknown
sources. The debug and release builds use different application IDs
(`...tanpura.debug` and `...tanpura`), so both can be installed side by side.

## CI

`.github/workflows/android.yml` runs on every push and can be triggered manually
from the Actions tab. It runs the unit tests, then builds the debug APK, the
release APK and the Play bundle, and uploads all of them as artifacts. Publishing
a GitHub release also attaches the release APK to it.

With no secrets configured, the release APK is signed with the debug key: it
installs on your phone but Google Play will reject it. To get a Play-uploadable
build, add four repository secrets:

| Secret | Value |
| --- | --- |
| `TANPURA_KEYSTORE_BASE64` | `base64 -w0 tanpura-release.jks` |
| `TANPURA_KEYSTORE_PASSWORD` | keystore password |
| `TANPURA_KEY_ALIAS` | key alias (`tanpura`) |
| `TANPURA_KEY_PASSWORD` | key password |

## Publishing to Google Play

1. Create the upload keystore and **back it up** — Play ties your app's identity
   to it permanently:
   ```bash
   keytool -genkeypair -v -keystore tanpura-release.jks \
     -alias tanpura -keyalg RSA -keysize 4096 -validity 10000
   ```
2. For local signed builds, copy `keystore.properties.example` to
   `keystore.properties` and fill it in. For CI, add the secrets above.
3. Register a Google Play developer account (one-off 25 USD).
4. Build the bundle: `./gradlew bundleRelease`, or download the `tanpura-aab`
   artifact from CI.
5. In the Play Console, create the app, complete the data-safety form (this app
   collects nothing, has no network permission and no analytics), add screenshots
   and a feature graphic, then upload the AAB to internal testing first.
6. Bump `versionCode` in `app/build.gradle.kts` for every upload.

## Verifying without Google Maven

`tools/verify/run.sh` compiles and tests the audio engine using the Kotlin
compiler bundled with Android Studio, so it works even when `dl.google.com` is
blocked. It covers the DSP, the music model and the Android-native audio classes —
which is where the real risk lives — and skips only the Compose UI, DataStore and
MediaSession layers, which need the full Gradle build.

```bash
bash tools/verify/run.sh            # 34 tests
bash tools/verify/run.sh --report   # measured tuning error, every pitch and voice
```

On first run it downloads JUnit and kotlinx-serialization from Maven Central into
`tools/verify/libs/` (git-ignored). Set `ANDROID_STUDIO_HOME` if Android Studio is
not at `C:/Program Files/Android/Android Studio`.

The same tests run under Gradle as `testDebugUnitTest`, so CI covers them too.

## The web version

```bash
node --test web/test/engine.test.mjs   # 26 tests, no dependencies
cd web && python -m http.server 8777   # then open http://127.0.0.1:8777/
```

Deploy: push to GitHub, import the repo in Vercel, and set **Root Directory** to
`web` (the repository root is the Android app). No build command, no output
directory — it is static files plus one generated worklet.

`web/README.md` covers the generated-worklet step, the browser-check harness, and
the honest differences from the native app.

## Layout

```
app/src/main/java/com/riyaaz/tanpura/
├── audio/                  pure-Kotlin DSP, unit-testable off-device
│   ├── Dsp.kt              tanh approximation, smoothing, biquads, noise
│   ├── StringVoice.kt      the Karplus-Strong string with jawari
│   ├── StrumSequencer.kt   the repeating right-hand strum
│   ├── Reverb.kt           Freeverb-style room
│   ├── RefTone.kt          sustained tuner tones
│   ├── SampleVoice.kt      imported per-string recordings
│   ├── PitchDetector.kt    autocorrelation, with sub-harmonic rejection
│   ├── PcmRing.kt          lock-free ring buffer
│   ├── TanpuraEngine.kt    the whole chain, one audio thread
│   ├── AudioOutput.kt      AudioTrack and the audio thread   (android)
│   ├── AudioDecoder.kt     MediaCodec decode to mono float   (android)
│   └── MediaLoopSource.kt  streaming loop for long files     (android)
├── model/                  pitch maths, tunings, instrument voices, settings
├── data/SettingsStore.kt   DataStore persistence
├── playback/               the app-scoped controller: transport, focus, timer
├── service/                foreground service, media session, notification
└── ui/                     Compose screens and the animated instrument
```

```
web/
├── src/engine.js            the same DSP and music model, in JavaScript
├── src/instrument.js        the animated canvas
├── src/app.js               audio graph, state, screens
├── tanpura-worklet.js       generated: engine.js + worklet glue, inlined
└── test/engine.test.mjs     the same assertions as the Kotlin tests
```

The engine deliberately has no Android imports on either side, which is why its
tuning can be measured on a desktop JVM and in Node rather than trusted.
