// GENERATED FILE - do not edit.
// Built from src/engine.js + src/worklet-tail.js by build-worklet.mjs.
// Run "node web/build-worklet.mjs" after changing either of those.

// Tanpura audio engine.
//
// This file is the single source of truth for the DSP and the music model. It is
// used three ways:
//
//   1. imported as an ES module by the main thread, for the pitch maths, tuning
//      tables and instrument definitions the UI needs to render;
//   2. concatenated into the AudioWorklet processor by build-worklet.mjs, because
//      an AudioWorklet is loaded as a single script and cannot reliably `import`;
//   3. imported by test/engine.test.mjs and run under plain Node, which is how the
//      tuning accuracy is actually verified rather than trusted.
//
// Because of (2) it must stay self-contained: no imports, no `export default`,
// and every export written inline on its declaration so the generator can strip
// them with one regex.

const CONTROL_BLOCK = 64;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/**
 * Pade [7/6] approximation of tanh: odd-symmetric, monotonic, and strictly
 * bounded by 1.
 *
 * That last property is load-bearing. This is the jawari waveshaper and it sits
 * *inside* the string's feedback loop, so any overshoot past unity gain would
 * make the string self-oscillate.
 */
function fastTanh(x) {
  if (x > 4.5) return 1;
  if (x < -4.5) return -1;
  const x2 = x * x;
  const num = x * (10395 + x2 * (1260 + x2 * 21));
  const den = 10395 + x2 * (4725 + x2 * (210 + x2));
  return num / den;
}

function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

function clamp01(v) {
  return clamp(v, 0, 1);
}

/** Curved interpolation between two endpoints. */
function taper(t, lo, hi, curve = 1) {
  const x = clamp01(t);
  const shaped = curve === 1 ? x : Math.pow(x, curve);
  return lo + (hi - lo) * shaped;
}

/** One-pole smoothing coefficient for a given time constant. */
function smoothingCoef(seconds, sampleRate, blockSize = 1) {
  if (seconds <= 0) return 1;
  return 1 - Math.exp(-(blockSize / sampleRate) / seconds);
}

/** A parameter that ramps towards its target instead of jumping. */
class Smoothed {
  constructor(initial, coef) {
    this.value = initial;
    this.target = initial;
    this.coef = coef;
  }

  step() {
    this.value += (this.target - this.value) * this.coef;
    return this.value;
  }

  snap(v) {
    this.value = v;
    this.target = v;
  }
}

/** Deterministic, allocation-free noise source (xorshift32). */
class Noise {
  constructor(seed = 0x1234_5678) {
    this.state = seed | 0 || 1;
  }

  nextBits() {
    let x = this.state;
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    this.state = x | 0;
    return this.state;
  }

  /** Uniform in [-1, 1). */
  nextBipolar() {
    return this.nextBits() / 2147483647;
  }

  /** Uniform in [0, 1). */
  nextUnit() {
    return (this.nextBits() >>> 8) / 16777216;
  }
}

/** One-pole lowpass: y += a * (x - y). */
class OnePole {
  constructor(a = 0.5) {
    this.a = a;
    this.y = 0;
  }

  setCoef(coef) {
    this.a = clamp(coef, 0.0001, 1);
  }

  process(x) {
    this.y += this.a * (x - this.y);
    return this.y;
  }

  reset() {
    this.y = 0;
  }
}

/** Direct-form-1 biquad, Audio EQ Cookbook coefficients. */
class Biquad {
  constructor() {
    this.b0 = 1;
    this.b1 = 0;
    this.b2 = 0;
    this.a1 = 0;
    this.a2 = 0;
    this.reset();
  }

  reset() {
    this.x1 = 0;
    this.x2 = 0;
    this.y1 = 0;
    this.y2 = 0;
  }

  setPeaking(freq, q, gainDb, sampleRate) {
    const A = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * freq) / sampleRate;
    const cosW = Math.cos(w0);
    const alpha = Math.sin(w0) / (2 * q);
    const a0 = 1 + alpha / A;
    this.b0 = (1 + alpha * A) / a0;
    this.b1 = (-2 * cosW) / a0;
    this.b2 = (1 - alpha * A) / a0;
    this.a1 = (-2 * cosW) / a0;
    this.a2 = (1 - alpha / A) / a0;
  }

  setHighPass(freq, q, sampleRate) {
    const w0 = (2 * Math.PI * freq) / sampleRate;
    const cosW = Math.cos(w0);
    const alpha = Math.sin(w0) / (2 * q);
    const a0 = 1 + alpha;
    this.b0 = ((1 + cosW) / 2) / a0;
    this.b1 = -(1 + cosW) / a0;
    this.b2 = ((1 + cosW) / 2) / a0;
    this.a1 = (-2 * cosW) / a0;
    this.a2 = (1 - alpha) / a0;
  }

  setLowShelf(freq, gainDb, sampleRate) {
    const A = Math.pow(10, gainDb / 40);
    const w0 = (2 * Math.PI * freq) / sampleRate;
    const cosW = Math.cos(w0);
    const alpha = (Math.sin(w0) / 2) * Math.sqrt((A + 1 / A) * 1.4 + 2);
    const ap1 = A + 1;
    const am1 = A - 1;
    const twoSqrtA = 2 * Math.sqrt(A) * alpha;
    const a0 = ap1 + am1 * cosW + twoSqrtA;
    this.b0 = (A * (ap1 - am1 * cosW + twoSqrtA)) / a0;
    this.b1 = (2 * A * (am1 - ap1 * cosW)) / a0;
    this.b2 = (A * (ap1 - am1 * cosW - twoSqrtA)) / a0;
    this.a1 = (-2 * (am1 + ap1 * cosW)) / a0;
    this.a2 = (ap1 + am1 * cosW - twoSqrtA) / a0;
  }

  process(x) {
    const y =
      this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;
    this.x2 = this.x1;
    this.x1 = x;
    this.y2 = this.y1;
    this.y1 = y;
    return y;
  }
}

// ---------------------------------------------------------------------------
// The string
// ---------------------------------------------------------------------------

const MIN_STRING_FREQ = 38;
const SILENCE_THRESHOLD = 2e-5;

/**
 * One tanpura string, modelled as an extended Karplus-Strong waveguide:
 *
 *   pluck -> delay line -> one-pole loop filter -> jawari waveshaper -> back in
 *
 * Three things make this a tanpura rather than a generic plucked string:
 *
 *  1. **Jawari.** On a real tanpura a cotton thread sits between string and
 *     curved bridge, so a loud string grazes the bridge and folds its own
 *     waveform - the shimmering bloom of overtones on the attack. Modelled as an
 *     amplitude-dependent soft waveshaper inside the feedback loop.
 *  2. **Tension modulation.** A hard-plucked string is stretched further and
 *     starts a touch sharp, settling as it decays.
 *  3. **Pluck-position comb.** The excitation is comb filtered, notching out the
 *     harmonics a finger at that position cannot excite.
 *
 * Tuning is computed rather than approximated: the loop filter's phase delay at
 * the fundamental is derived in closed form and subtracted from the delay length.
 */
class StringVoice {
  constructor(sampleRate) {
    this.sampleRate = sampleRate;
    this.maxDelay = Math.ceil(sampleRate / MIN_STRING_FREQ) + 4;
    this.buffer = new Float32Array(this.maxDelay);
    this.scratch = new Float32Array(this.maxDelay);
    this.writeIdx = 0;

    this.noise = new Noise(0x51f0a731);
    this.loopFilter = new OnePole(0.5);
    this.exciteFilter = new OnePole(0.5);

    this.delayTarget = 200;
    this.delay = 200;
    this.loopGain = 0.999;
    this.frequency = 220;

    this.brightness = 0.5;
    this.decaySeconds = 8;
    this.jawariAmount = 0.55;
    this.pluckPosition = 0.22;
    this.tension = 0.5;

    this.envelope = 0;
    this.silentBlocks = 1024;

    this.gainSm = new Smoothed(1, smoothingCoef(0.02, sampleRate));
  }

  set gain(v) {
    this.gainSm.target = v;
  }

  get gain() {
    return this.gainSm.target;
  }

  get isRinging() {
    return this.silentBlocks < 64;
  }

  reset() {
    this.buffer.fill(0);
    this.writeIdx = 0;
    this.envelope = 0;
    this.silentBlocks = 1024;
    this.loopFilter.reset();
    this.exciteFilter.reset();
    this.delay = this.delayTarget;
  }

  setFrequency(freqHz, glide = true) {
    this.frequency = clamp(freqHz, MIN_STRING_FREQ + 1, this.sampleRate / 4);
    this.recomputeLoop();
    if (!glide) this.delay = this.delayTarget;
  }

  /**
   * @param brightness 0 = dark and woolly, 1 = bright and metallic
   * @param decaySeconds time for the fundamental to fall 60 dB
   * @param jawari 0 = plain plucked string, 1 = heavy bridge buzz
   * @param pluckPosition 0..0.5 as a fraction of string length
   * @param tension 0..1 amount of amplitude-driven pitch bend on the attack
   */
  setCharacter(brightness, decaySeconds, jawari, pluckPosition, tension) {
    this.brightness = clamp01(brightness);
    this.decaySeconds = clamp(decaySeconds, 0.2, 40);
    this.jawariAmount = clamp01(jawari);
    this.pluckPosition = clamp(pluckPosition, 0.03, 0.5);
    this.tension = clamp01(tension);

    this.loopFilter.setCoef(taper(this.brightness, 0.12, 0.92, 0.7));
    this.exciteFilter.setCoef(taper(this.brightness, 0.1, 0.85, 0.8));
    this.recomputeLoop();
  }

  /**
   * The loop is `z^-D * H(z)` where H is the one-pole lowpass. For the string to
   * sound at f0 the total loop delay must be fs/f0, so D = fs/f0 - phaseDelay(H).
   */
  recomputeLoop() {
    const period = this.sampleRate / this.frequency;
    const w = (2 * Math.PI * this.frequency) / this.sampleRate;
    const p = 1 - this.loopFilter.a; // pole radius of y += a*(x-y)

    const denomRe = 1 - p * Math.cos(w);
    const numIm = p * Math.sin(w);
    const phaseDelay = w > 1e-9 ? Math.atan(numIm / denomRe) / w : p / (1 - p);
    const mag = (1 - p) / Math.sqrt(denomRe * denomRe + numIm * numIm);

    this.delayTarget = clamp(period - phaseDelay, 2.5, this.maxDelay - 3);

    const periodsInT60 = Math.max(1, this.frequency * this.decaySeconds);
    const wanted = Math.pow(0.001, 1 / periodsInT60);
    this.loopGain = clamp(wanted / mag, 0.5, 0.99995);
  }

  /** Excites the string. `velocity` is 0..1 and maps to peak string amplitude. */
  pluck(velocity) {
    const v = clamp(velocity, 0, 1.5);
    if (v <= 0) return;

    const n = clamp(Math.floor(this.delayTarget), 4, this.maxDelay - 1) | 0;
    const combOffset = clamp(Math.floor(this.pluckPosition * n), 1, n - 1) | 0;
    const scratch = this.scratch;

    // One period of lowpassed noise: the string's displacement right after the
    // finger releases it.
    for (let i = 0; i < n; i++) {
      scratch[i] = this.exciteFilter.process(this.noise.nextBipolar());
    }

    // Comb filter for pluck position, plus a raised-cosine fade at both ends so
    // re-plucking a ringing string does not produce a step.
    const fade = Math.max(2, (n / 12) | 0);
    let sum = 0;
    for (let i = 0; i < n; i++) {
      const combed = scratch[i] - (i >= combOffset ? scratch[i - combOffset] : 0);
      let win = 1;
      if (i < fade) win = 0.5 - 0.5 * Math.cos((Math.PI * i) / fade);
      else if (i >= n - fade) win = 0.5 - 0.5 * Math.cos((Math.PI * (n - 1 - i)) / fade);
      const s = combed * win;
      scratch[i] = s;
      sum += s * s;
    }
    const rms = Math.sqrt(sum / n);
    if (rms < 1e-9) return;
    const scale = (v * 0.3) / rms;

    let idx = this.writeIdx - n;
    while (idx < 0) idx += this.maxDelay;
    for (let i = 0; i < n; i++) {
      this.buffer[idx] += scratch[i] * scale;
      idx++;
      if (idx >= this.maxDelay) idx = 0;
    }
    this.silentBlocks = 0;
  }

  /**
   * Renders `frames` samples and *adds* them into `out` at `offset`.
   * Call with frames <= CONTROL_BLOCK; control-rate updates happen per call.
   */
  render(out, offset, frames) {
    // ---- control rate ----
    this.delay += (this.delayTarget - this.delay) * 0.02;

    const drive = 1 + this.jawariAmount * 26 * this.envelope;
    const invDrive = 1 / drive;

    // Tension modulation: a hotter string reads a marginally shorter delay.
    const effective = this.delay * (1 - this.tension * 0.0025 * this.envelope);
    const readDelay = clamp(effective, 2.5, this.maxDelay - 3);

    const baseIdx = Math.floor(readDelay);
    const frac = readDelay - baseIdx;
    const jw = this.jawariAmount;
    const buffer = this.buffer;
    const maxDelay = this.maxDelay;
    const loopGain = this.loopGain;

    let env = this.envelope;
    let peak = 0;
    let writeIdx = this.writeIdx;

    for (let i = 0; i < frames; i++) {
      // buffer[writeIdx - k] is the sample delayed by k, so a delay of
      // (baseIdx + frac) interpolates between delay baseIdx and baseIdx+1, which
      // is the index *below* r0. Reading the index above instead would give a
      // delay of (baseIdx - frac) and tune every string sharp.
      let r0 = writeIdx - baseIdx;
      if (r0 < 0) r0 += maxDelay;
      let r1 = r0 - 1;
      if (r1 < 0) r1 += maxDelay;

      const delayed = buffer[r0] * (1 - frac) + buffer[r1] * frac;

      let y = this.loopFilter.process(delayed);

      // Jawari: compressive odd-symmetric shaper. Small-signal gain is 1, so
      // |shaped| <= |y| and the feedback loop is unconditionally stable.
      if (jw > 0) {
        const shaped = fastTanh(y * drive) * invDrive;
        y += jw * (shaped - y);
      }

      y *= loopGain;
      if (!(y > -4 && y < 4)) y = 0; // also catches NaN

      buffer[writeIdx] = y;
      writeIdx++;
      if (writeIdx >= maxDelay) writeIdx = 0;

      const a = y < 0 ? -y : y;
      if (a > peak) peak = a;
      env += (a - env) * 0.0012;

      out[offset + i] += y * this.gainSm.step();
    }

    this.writeIdx = writeIdx;
    this.envelope = env;
    if (peak < SILENCE_THRESHOLD) this.silentBlocks++;
    else this.silentBlocks = 0;
  }
}

// ---------------------------------------------------------------------------
// Room
// ---------------------------------------------------------------------------

const COMB_TUNING = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
const ALLPASS_TUNING = [556, 441, 341, 225];
const STEREO_SPREAD = 23;

class Comb {
  constructor(size) {
    this.buf = new Float32Array(Math.max(4, size | 0));
    this.idx = 0;
    this.store = 0;
    this.feedback = 0.84;
    this.damp = 0.2;
  }

  clear() {
    this.buf.fill(0);
    this.store = 0;
  }

  process(input) {
    const out = this.buf[this.idx];
    this.store = out * (1 - this.damp) + this.store * this.damp;
    this.buf[this.idx] = input + this.store * this.feedback;
    if (++this.idx >= this.buf.length) this.idx = 0;
    return out;
  }
}

class Allpass {
  constructor(size) {
    this.buf = new Float32Array(Math.max(4, size | 0));
    this.idx = 0;
  }

  clear() {
    this.buf.fill(0);
  }

  process(input) {
    const bufOut = this.buf[this.idx];
    this.buf[this.idx] = input + bufOut * 0.5;
    if (++this.idx >= this.buf.length) this.idx = 0;
    return -input + bufOut;
  }
}

/**
 * Freeverb-style reverberator, mono in / stereo out.
 *
 * Not decoration: the tail is what fuses the overlapping string decays into one
 * continuous drone.
 */
class Reverb {
  constructor(sampleRate) {
    const scale = sampleRate / 44100;
    this.combsL = COMB_TUNING.map((t) => new Comb(t * scale));
    this.combsR = COMB_TUNING.map((t) => new Comb((t + STEREO_SPREAD) * scale));
    this.apL = ALLPASS_TUNING.map((t) => new Allpass(t * scale));
    this.apR = ALLPASS_TUNING.map((t) => new Allpass((t + STEREO_SPREAD) * scale));
    this.wet = 0.2;
    this.width = 0.85;
    this.setRoom(0.86, 0.28);
  }

  clear() {
    for (const c of this.combsL) c.clear();
    for (const c of this.combsR) c.clear();
    for (const a of this.apL) a.clear();
    for (const a of this.apR) a.clear();
  }

  setRoom(size, damping) {
    const fb = 0.7 + 0.28 * clamp01(size);
    const dmp = 0.4 * clamp01(damping);
    for (const c of this.combsL) {
      c.feedback = fb;
      c.damp = dmp;
    }
    for (const c of this.combsR) {
      c.feedback = fb;
      c.damp = dmp;
    }
  }

  setMix(mix) {
    this.wet = clamp01(mix);
  }

  /** Reads `frames` mono samples and writes into separate L/R output arrays. */
  process(mono, frames, left, right, outOffset) {
    const w = this.wet;
    const dry = 1 - w * 0.65;
    const wet1 = w * (this.width / 2 + 0.5);
    const wet2 = w * ((1 - this.width) / 2);

    for (let i = 0; i < frames; i++) {
      const input = mono[i] * 0.22;
      let l = 0;
      let r = 0;
      for (const c of this.combsL) l += c.process(input);
      for (const c of this.combsR) r += c.process(input);
      for (const a of this.apL) l = a.process(l);
      for (const a of this.apR) r = a.process(r);
      const d = mono[i] * dry;
      left[outOffset + i] = d + l * wet1 + r * wet2;
      right[outOffset + i] = d + r * wet1 + l * wet2;
    }
  }
}

// ---------------------------------------------------------------------------
// Strum
// ---------------------------------------------------------------------------

const MAX_STRINGS = 5;

/** Fraction of the cycle occupied by the strum itself; the rest is the rest. */
const STRUM_SPREAD = 0.72;

/**
 * Drives the repeating right-hand strum: string 1, 2, 3, 4, pause, repeat.
 *
 * A metronomic strum sounds like a machine, so each cycle gets fresh timing and
 * velocity jitter. The amount is controllable because a perfectly steady strum is
 * sometimes exactly what you want for tuning.
 */
class StrumSequencer {
  constructor(sampleRate) {
    this.sampleRate = sampleRate;
    this.noise = new Noise(0x2b7e1516);
    this.triggerTimes = new Float64Array(MAX_STRINGS);
    this.triggerVels = new Float64Array(MAX_STRINGS);
    this.baseVelocities = new Float64Array(MAX_STRINGS).fill(0.8);
    this.phase = 0;
    this.nextIndex = 0;
    this.cycleLen = 1;
    this.stringCount = 4;
    this.cycleSeconds = 3.2;
    this.humanize = 0.35;
    this.lastStruckString = -1;
    this.reset();
  }

  reset() {
    this.phase = 0;
    this.lastStruckString = -1;
    this.beginCycle();
  }

  beginCycle() {
    this.cycleLen = Math.max(64, clamp(this.cycleSeconds, 0.35, 20) * this.sampleRate);
    const count = clamp(this.stringCount, 1, MAX_STRINGS) | 0;
    const span = this.cycleLen * STRUM_SPREAD;
    const interval = count > 1 ? span / (count - 1) : span;
    const timeJitter = this.humanize * 0.1 * interval;

    for (let i = 0; i < count; i++) {
      const t = i * interval + this.noise.nextBipolar() * timeJitter;
      this.triggerTimes[i] = clamp(t, 0, this.cycleLen - 1);
      const velJitter = 1 + this.noise.nextBipolar() * this.humanize * 0.16;
      this.triggerVels[i] = clamp(this.baseVelocities[i] * velJitter, 0.02, 1.4);
    }
    // Timing jitter can reorder adjacent strings; keep the sweep monotonic.
    for (let i = 1; i < count; i++) {
      if (this.triggerTimes[i] < this.triggerTimes[i - 1]) {
        this.triggerTimes[i] = this.triggerTimes[i - 1];
      }
    }
    this.nextIndex = 0;
  }

  /** Advances by `frames` samples, calling onPluck(index, velocity) as needed. */
  advance(frames, onPluck) {
    this.phase += frames;
    const count = clamp(this.stringCount, 1, MAX_STRINGS) | 0;
    let guard = 0;
    while (guard++ < 8) {
      while (this.nextIndex < count && this.triggerTimes[this.nextIndex] <= this.phase) {
        const idx = this.nextIndex++;
        this.lastStruckString = idx;
        onPluck(idx, this.triggerVels[idx]);
      }
      if (this.phase < this.cycleLen) break;
      this.phase -= this.cycleLen;
      this.beginCycle();
    }
  }

  cyclePosition() {
    return this.cycleLen > 0 ? this.phase / this.cycleLen : 0;
  }
}

// ---------------------------------------------------------------------------
// Tuner reference tone
// ---------------------------------------------------------------------------

const HARMONIC_LEVELS = [1, 0.34, 0.16, 0.07, 0.03];

/**
 * A sustained reference tone for tuning. Not a pure sine: a handful of harmonics
 * makes it far easier to match by ear against an instrument.
 */
class RefTone {
  constructor(sampleRate) {
    this.phases = new Float64Array(HARMONIC_LEVELS.length);
    this.increment = 0;
    this.env = 0;
    this.envTarget = 0;
    this.attackCoef = smoothingCoef(0.05, sampleRate);
    this.releaseCoef = smoothingCoef(0.18, sampleRate);
    this.sampleRate = sampleRate;
  }

  get isSounding() {
    return this.env > 1e-4 || this.envTarget > 0;
  }

  setFrequency(freqHz) {
    this.increment = (2 * Math.PI * clamp(freqHz, 20, this.sampleRate / 3)) / this.sampleRate;
  }

  noteOn() {
    this.envTarget = 1;
  }

  noteOff() {
    this.envTarget = 0;
  }

  reset() {
    this.env = 0;
    this.envTarget = 0;
    this.phases.fill(0);
  }

  render(out, offset, frames, level) {
    if (!this.isSounding) return;
    const coef = this.envTarget > this.env ? this.attackCoef : this.releaseCoef;
    const TWO_PI = 2 * Math.PI;
    for (let i = 0; i < frames; i++) {
      this.env += (this.envTarget - this.env) * coef;
      let s = 0;
      for (let h = 0; h < HARMONIC_LEVELS.length; h++) {
        this.phases[h] += this.increment * (h + 1);
        if (this.phases[h] > TWO_PI) this.phases[h] -= TWO_PI;
        s += Math.sin(this.phases[h]) * HARMONIC_LEVELS[h];
      }
      out[offset + i] += s * this.env * level * 0.28;
    }
  }
}

// ---------------------------------------------------------------------------
// Music model
// ---------------------------------------------------------------------------

const SHARP_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];

/** Lowest / highest Sa the app offers: C2 .. C5. */
const MIN_SA = 36;
const MAX_SA = 72;
/** C#3, the classic male-voice tanpura pitch. */
const DEFAULT_SA = 49;

function frequency(midi, cents = 0, a4Hz = 440) {
  return a4Hz * Math.pow(2, (midi - 69 + cents / 100) / 12);
}

function frequencyFromSemitone(semitonesFromSa, saMidi, cents, a4Hz) {
  return a4Hz * Math.pow(2, (saMidi + semitonesFromSa - 69 + cents / 100) / 12);
}

function noteName(midi) {
  const pc = ((midi % 12) + 12) % 12;
  return SHARP_NAMES[pc] + (Math.floor(midi / 12) - 1);
}

function formatCents(cents) {
  const c = Math.round(cents);
  if (c > 0) return `+${c} c`;
  if (c < 0) return `${c} c`;
  return '0 c';
}

/**
 * Tanpura tunings. Offsets are in semitones relative to the middle Sa.
 *
 * On a real four-string tanpura the strings are: a side string tuned to Pa (or
 * Ma, or Ni, depending on the raga), two middle strings at Sa, and a thick brass
 * string at the Sa an octave below. The side string sounds *below* the middle Sa,
 * so Pa is -5 semitones - a fourth down - not +7.
 */
const STRING_PATTERNS = [
  { id: 'pa', label: 'Pa – Sa – Sa – Sa', short: 'Pa', labels: ['Pa', 'Sa', 'Sa', 'Sa↓'], offsets: [-5, 0, 0, -12], note: 'Standard tuning, works for most ragas' },
  { id: 'ma', label: 'Ma – Sa – Sa – Sa', short: 'Ma', labels: ['Ma', 'Sa', 'Sa', 'Sa↓'], offsets: [-7, 0, 0, -12], note: 'For ragas that omit Pa (Malkauns, Chandrakauns)' },
  { id: 'ma_teevra', label: 'Ma♯ – Sa – Sa – Sa', short: 'Ma♯', labels: ['Ma♯', 'Sa', 'Sa', 'Sa↓'], offsets: [-6, 0, 0, -12], note: 'For Marwa, Puriya, Sohini' },
  { id: 'ni', label: 'Ni – Sa – Sa – Sa', short: 'Ni', labels: ['Ni', 'Sa', 'Sa', 'Sa↓'], offsets: [-1, 0, 0, -12], note: 'For Bhairav-family ragas and Lalit' },
  { id: 'ni_komal', label: 'ni – Sa – Sa – Sa', short: 'ni', labels: ['ni', 'Sa', 'Sa', 'Sa↓'], offsets: [-2, 0, 0, -12], note: 'For Todi, Miyan ki Todi' },
  { id: 'dha', label: 'Dha – Sa – Sa – Sa', short: 'Dha', labels: ['Dha', 'Sa', 'Sa', 'Sa↓'], offsets: [-3, 0, 0, -12], note: 'Occasionally used for Bihag and Kalyan' },
  { id: 'ga', label: 'Ga – Sa – Sa – Sa', short: 'Ga', labels: ['Ga', 'Sa', 'Sa', 'Sa↓'], offsets: [-8, 0, 0, -12], note: 'Rare; for ragas built around Ga' },
  { id: 'sa', label: 'Sa – Sa – Sa – Sa', short: 'Sa', labels: ['Sa', 'Sa', 'Sa', 'Sa↓'], offsets: [0, 0, 0, -12], note: 'Pure Sa drone, no side string' },
  { id: 'pa5', label: 'Pa – Sa – Sa – Sa – Sa', short: 'Pa ×5', labels: ['Pa', 'Sa', 'Sa', 'Sa', 'Sa↓'], offsets: [-5, 0, 0, 0, -12], note: 'Five-string tanpura, fuller wash' },
  { id: 'nipa5', label: 'Pa – Ni – Sa – Sa – Sa', short: 'Pa+Ni', labels: ['Pa', 'Ni', 'Sa', 'Sa', 'Sa↓'], offsets: [-5, -1, 0, 0, -12], note: 'Five-string with both Pa and Ni side strings' },
];

function patternById(id) {
  return STRING_PATTERNS.find((p) => p.id === id) || STRING_PATTERNS[0];
}

/**
 * Instrument characters: how big the gourd is, how the jawari is set, how long
 * the strings ring.
 */
const VOICES = [
  {
    id: 'male',
    label: 'Male',
    description: 'Large Tanjore-style tanpura. Deep, slow, long sustain.',
    brightness: 0.4,
    decaySeconds: 11.5,
    jawari: 0.62,
    pluckPosition: 0.24,
    tension: 0.65,
    bodyPeaks: [[104, 0.9, 5], [248, 1.2, 3], [615, 1.5, 2], [1850, 1.1, -2.5]],
    highPassHz: 45,
    lowShelfDb: 2.5,
    velocities: [0.72, 0.8, 0.8, 0.95],
    gains: [0.85, 0.95, 0.95, 1],
    decayScale: [0.85, 1, 1, 1.35],
    suggestedSa: 49,
    reverbSize: 0.88,
    reverbMix: 0.24,
  },
  {
    id: 'female',
    label: 'Female',
    description: 'Miraj-style tanpura. Brighter, tighter, more shimmer.',
    brightness: 0.56,
    decaySeconds: 8.5,
    jawari: 0.58,
    pluckPosition: 0.2,
    tension: 0.55,
    bodyPeaks: [[152, 1, 4.5], [340, 1.3, 3], [830, 1.5, 2.5], [2400, 1, -2]],
    highPassHz: 60,
    lowShelfDb: 1.5,
    velocities: [0.74, 0.82, 0.82, 0.92],
    gains: [0.88, 0.95, 0.95, 1],
    decayScale: [0.9, 1, 1, 1.3],
    suggestedSa: 56,
    reverbSize: 0.84,
    reverbMix: 0.22,
  },
  {
    id: 'instrumental',
    label: 'Instrumental',
    description: 'Very deep tanpura for sitar, sarod and bansuri riyaaz.',
    brightness: 0.34,
    decaySeconds: 13.5,
    jawari: 0.7,
    pluckPosition: 0.27,
    tension: 0.75,
    bodyPeaks: [[88, 0.85, 6], [210, 1.1, 3.5], [540, 1.5, 1.5], [1600, 1.1, -3]],
    highPassHz: 38,
    lowShelfDb: 3.5,
    velocities: [0.7, 0.78, 0.78, 1],
    gains: [0.82, 0.92, 0.92, 1],
    decayScale: [0.85, 1, 1, 1.4],
    suggestedSa: 45,
    reverbSize: 0.92,
    reverbMix: 0.28,
  },
  {
    id: 'shruti',
    label: 'Soft',
    description: 'Gentle, almost buzz-free drone. Easy on the ears for long sittings.',
    brightness: 0.46,
    decaySeconds: 9.5,
    jawari: 0.28,
    pluckPosition: 0.33,
    tension: 0.35,
    bodyPeaks: [[130, 0.9, 3.5], [290, 1.2, 2], [700, 1.6, 1], [2000, 0.9, -4]],
    highPassHz: 50,
    lowShelfDb: 2,
    velocities: [0.7, 0.75, 0.75, 0.85],
    gains: [0.9, 0.95, 0.95, 1],
    decayScale: [0.95, 1, 1, 1.2],
    suggestedSa: 52,
    reverbSize: 0.8,
    reverbMix: 0.2,
  },
];

function voiceById(id) {
  return VOICES.find((v) => v.id === id) || VOICES[0];
}

/**
 * Stretches a per-string list to `count` entries. The last value always maps to
 * the low brass string; extra strings on a five-string tanpura repeat the middle
 * value.
 */
function perString(values, count) {
  const out = new Array(count).fill(1);
  if (!values.length || count <= 0) return out;
  const midIndex = Math.max(0, values.length - 2);
  for (let i = 0; i < count - 1; i++) out[i] = values[Math.min(i, midIndex)];
  out[count - 1] = values[values.length - 1];
  return out;
}

const DEFAULT_SETTINGS = {
  saMidi: DEFAULT_SA,
  fineCents: 0,
  a4Hz: 440,
  patternId: 'pa',
  voiceId: 'male',
  brightnessTrim: 0,
  jawariTrim: 0,
  decayScale: 1,
  cycleSeconds: 3.2,
  humanize: 0.35,
  masterVolume: 0.78,
  reverbMix: -1, // < 0 means "use the voice default"
  stringGains: [1, 1, 1, 1, 1],
  stringMuted: [false, false, false, false, false],
  loopPitchCents: 0,
};

function effectiveReverbMix(settings) {
  return settings.reverbMix < 0 ? voiceById(settings.voiceId).reverbMix : settings.reverbMix;
}

function stringGain(settings, index) {
  if (settings.stringMuted[index]) return 0;
  const g = settings.stringGains[index];
  return g === undefined ? 1 : g;
}

// ---------------------------------------------------------------------------
// The engine
// ---------------------------------------------------------------------------

const LIMIT_KNEE = 0.9;

/**
 * The whole signal chain. Owns the strings, the strum, the body EQ and the room.
 *
 * `submit()` is called from the message handler and takes effect at the next
 * block boundary, so a slider drag can never tear a coefficient set.
 */
class TanpuraEngine {
  constructor(sampleRate) {
    this.sampleRate = sampleRate;
    this.strings = [];
    for (let i = 0; i < MAX_STRINGS; i++) this.strings.push(new StringVoice(sampleRate));
    this.sequencer = new StrumSequencer(sampleRate);
    this.reverb = new Reverb(sampleRate);
    this.refTone = new RefTone(sampleRate);

    this.bodyPeaks = [new Biquad(), new Biquad(), new Biquad(), new Biquad()];
    this.activeBodyPeaks = 0;
    this.highPass = new Biquad();
    this.lowShelf = new Biquad();

    this.monoBus = new Float32Array(CONTROL_BLOCK);
    this.pending = null;
    this.settings = { ...DEFAULT_SETTINGS };
    this.appliedVoiceId = null;
    this.appliedPatternId = null;
    this.stringCount = 4;

    this.masterSm = new Smoothed(0, smoothingCoef(0.22, sampleRate));
    this.playRequested = false;
    this.quiesced = true;
    this.externalGain = 1;

    this.outputLevel = 0;
    this.strumPosition = 0;
    this.stringFlash = new Float32Array(MAX_STRINGS);

    this.applySettings(this.settings, true);
  }

  submit(settings) {
    this.pending = settings;
  }

  setPlaying(playing) {
    this.playRequested = playing;
    if (playing) this.sequencer.reset();
  }

  setReferenceTone(freqHz) {
    if (freqHz === null || freqHz === undefined) {
      this.refTone.noteOff();
    } else {
      this.refTone.setFrequency(freqHz);
      this.refTone.noteOn();
    }
  }

  get isIdle() {
    if (this.playRequested) return false;
    if (this.masterSm.value >= 1e-4) return false;
    if (this.refTone.isSounding) return false;
    for (const s of this.strings) if (s.isRinging) return false;
    return true;
  }

  reset() {
    for (const s of this.strings) s.reset();
    this.reverb.clear();
    this.refTone.reset();
    this.masterSm.snap(0);
    this.sequencer.reset();
    this.stringFlash.fill(0);
  }

  stringActivity(index) {
    return this.stringFlash[index] || 0;
  }

  /** Renders `frames` frames into the separate `left` and `right` arrays. */
  render(left, right, frames) {
    if (this.pending) {
      this.applySettings(this.pending, false);
      this.pending = null;
    }

    let done = 0;
    let peak = 0;
    const refLevel = this.settings.masterVolume;

    while (done < frames) {
      const n = Math.min(CONTROL_BLOCK, frames - done);
      this.monoBus.fill(0, 0, n);

      if (this.playRequested) {
        this.quiesced = false;
      } else if (!this.quiesced && this.masterSm.value < 1e-4) {
        // The fade-out has finished; free the strings so the engine can report
        // itself idle and the audio graph can be suspended.
        for (const s of this.strings) s.reset();
        this.quiesced = true;
      }

      if (!this.quiesced) {
        this.sequencer.advance(n, (idx, vel) => {
          this.strings[idx].pluck(vel);
          this.stringFlash[idx] = 1;
        });
        for (let i = 0; i < this.stringCount; i++) this.strings[i].render(this.monoBus, 0, n);
        this.applyBody(n);
      }

      const blockPeak = this.finishBlock(left, right, done, n, refLevel);
      if (blockPeak > peak) peak = blockPeak;

      for (let i = 0; i < MAX_STRINGS; i++) this.stringFlash[i] *= 0.995;
      done += n;
    }

    this.strumPosition = this.sequencer.cyclePosition();
    this.outputLevel = this.outputLevel * 0.7 + peak * 0.3;
  }

  applyBody(n) {
    const bus = this.monoBus;
    for (let i = 0; i < n; i++) {
      let v = this.highPass.process(bus[i]);
      v = this.lowShelf.process(v);
      for (let p = 0; p < this.activeBodyPeaks; p++) v = this.bodyPeaks[p].process(v);
      bus[i] = v;
    }
  }

  /**
   * Master fade, tuner tone, room and limiter for one control block.
   *
   * The master gain hits the dry bus *before* the reverb, so pausing fades the
   * room out with the strings instead of leaving a tail hanging.
   */
  finishBlock(left, right, frameOffset, n, refLevel) {
    const ext = clamp01(this.externalGain);
    this.masterSm.target = this.playRequested ? this.settings.masterVolume * ext : 0;
    const bus = this.monoBus;
    for (let i = 0; i < n; i++) bus[i] *= this.masterSm.step();

    // The tuner tone bypasses the drone fade so it works while paused.
    this.refTone.render(bus, 0, n, refLevel);

    this.reverb.process(bus, n, left, right, frameOffset);

    let peak = 0;
    for (let ch = 0; ch < 2; ch++) {
      const arr = ch === 0 ? left : right;
      for (let i = 0; i < n; i++) {
        const at = frameOffset + i;
        let v = arr[at];
        if (v > LIMIT_KNEE || v < -LIMIT_KNEE) {
          const sign = v < 0 ? -1 : 1;
          const over = (v < 0 ? -v : v) - LIMIT_KNEE;
          v = sign * (LIMIT_KNEE + (1 - LIMIT_KNEE) * fastTanh(over / (1 - LIMIT_KNEE)));
        }
        if (Number.isNaN(v)) v = 0;
        arr[at] = v;
        const a = v < 0 ? -v : v;
        if (a > peak) peak = a;
      }
    }
    return peak;
  }

  applySettings(s, force) {
    this.settings = s;
    const voice = voiceById(s.voiceId);
    const pattern = patternById(s.patternId);
    const count = clamp(pattern.offsets.length, 1, MAX_STRINGS) | 0;

    if (force || count !== this.stringCount) {
      for (let i = count; i < MAX_STRINGS; i++) this.strings[i].reset();
    }
    this.stringCount = count;

    const brightness = clamp01(voice.brightness + s.brightnessTrim * 0.35);
    const jawari = clamp01(voice.jawari + s.jawariTrim * 0.4);
    const decayScale = clamp(s.decayScale, 0.4, 2.5);
    const velocities = perString(voice.velocities, count);
    const voiceGains = perString(voice.gains, count);
    const decays = perString(voice.decayScale, count);

    // Only pitch changed -> glide instead of restarting the string.
    const shapeChanged = force || this.appliedPatternId !== s.patternId || this.appliedVoiceId !== s.voiceId;

    for (let i = 0; i < count; i++) {
      const freq = frequencyFromSemitone(pattern.offsets[i], s.saMidi, s.fineCents, s.a4Hz);
      const str = this.strings[i];
      str.setCharacter(
        brightness,
        voice.decaySeconds * decayScale * decays[i],
        jawari,
        voice.pluckPosition,
        voice.tension,
      );
      str.setFrequency(freq, !shapeChanged);
      str.gain = voiceGains[i] * stringGain(s, i);
      this.sequencer.baseVelocities[i] = velocities[i];
    }

    this.sequencer.stringCount = count;
    this.sequencer.cycleSeconds = s.cycleSeconds;
    this.sequencer.humanize = s.humanize;

    if (force || this.appliedVoiceId !== s.voiceId) {
      this.activeBodyPeaks = Math.min(voice.bodyPeaks.length, this.bodyPeaks.length);
      for (let i = 0; i < this.activeBodyPeaks; i++) {
        const [f, q, g] = voice.bodyPeaks[i];
        this.bodyPeaks[i].setPeaking(f, q, g, this.sampleRate);
      }
      this.highPass.setHighPass(voice.highPassHz, 0.707, this.sampleRate);
      this.lowShelf.setLowShelf(180, voice.lowShelfDb, this.sampleRate);
      this.reverb.setRoom(voice.reverbSize, 0.3);
    }

    this.reverb.setMix(effectiveReverbMix(s));
    this.appliedVoiceId = s.voiceId;
    this.appliedPatternId = s.patternId;
  }
}

// ---------------------------------------------------------------------------
// Pitch detection (used by the tests to verify tuning, not by the app)
// ---------------------------------------------------------------------------

function normalizedCorr(samples, offset, length, mean, lag) {
  let corr = 0;
  let normA = 0;
  let normB = 0;
  const count = length - lag;
  for (let i = 0; i < count; i++) {
    const a = samples[offset + i] - mean;
    const b = samples[offset + i + lag] - mean;
    corr += a * b;
    normA += a * a;
    normB += b * b;
  }
  const denom = Math.sqrt(normA * normB);
  return denom <= 0 ? 0 : corr / denom;
}

/** Autocorrelation pitch detection with sub-harmonic rejection. */
function detectPitch(samples, sampleRate, minHz = 50, maxHz = 1200, offset = 0, length = samples.length - offset) {
  if (length <= 0) return null;
  const minLag = Math.max(2, Math.floor(sampleRate / maxHz));
  const maxLag = Math.min(Math.floor(sampleRate / minHz), Math.floor(length / 2) - 1);
  if (maxLag <= minLag) return null;

  let mean = 0;
  for (let i = 0; i < length; i++) mean += samples[offset + i];
  mean /= length;
  let energy = 0;
  for (let i = 0; i < length; i++) {
    const v = samples[offset + i] - mean;
    energy += v * v;
  }
  if (Math.sqrt(energy / length) < 1e-6) return null;

  const scores = new Float64Array(maxLag + 1);
  let best = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    scores[lag] = normalizedCorr(samples, offset, length, mean, lag);
    if (scores[lag] > best) best = scores[lag];
  }
  if (best < 0.35) return null;

  // A harmonically rich tone correlates almost as well at two or three times its
  // true period, so take the shortest local peak that is nearly as good as the
  // global best rather than the global best itself.
  const threshold = best * 0.9;
  let bestLag = -1;
  for (let lag = minLag + 1; lag < maxLag; lag++) {
    if (scores[lag] >= threshold && scores[lag] >= scores[lag - 1] && scores[lag] >= scores[lag + 1]) {
      bestLag = lag;
      break;
    }
  }
  if (bestLag < 0) {
    bestLag = minLag;
    for (let lag = minLag; lag <= maxLag; lag++) if (scores[lag] > scores[bestLag]) bestLag = lag;
  }

  let refined = bestLag;
  if (bestLag > minLag && bestLag < maxLag) {
    const ym = scores[bestLag - 1];
    const y0 = scores[bestLag];
    const yp = scores[bestLag + 1];
    const denom = 2 * (2 * y0 - ym - yp);
    if (Math.abs(denom) > 1e-12) refined = bestLag + clamp((yp - ym) / denom, -1, 1);
  }
  return sampleRate / refined;
}

/** Difference between two frequencies, in cents. */
function cents(from, to) {
  return (1200 * Math.log(to / from)) / Math.LN2;
}

// Tail fragment appended to engine.js by build-worklet.mjs to produce
// tanpura-worklet.js.
//
// This file is NOT standalone: TanpuraEngine, MAX_STRINGS and friends come from
// the engine source concatenated above it. It lives separately so the processor
// glue stays readable instead of being buried in a build script string.
//
// Concatenation rather than `import` is deliberate: an AudioWorklet is loaded as
// a single script and module imports inside worklet scope are not reliably
// supported across browsers.

/**
 * Runs the tanpura on the audio rendering thread.
 *
 * Everything arrives by message and is applied at a block boundary, so nothing
 * the UI does can tear a coefficient set mid-render.
 */
class TanpuraProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    // `sampleRate` is a global inside AudioWorkletGlobalScope.
    this.engine = new TanpuraEngine(sampleRate);
    this.spare = new Float32Array(128);
    this.meterCounter = 0;

    this.port.onmessage = (event) => {
      const msg = event.data;
      switch (msg.type) {
        case 'settings':
          this.engine.submit(msg.settings);
          break;
        case 'play':
          this.engine.setPlaying(!!msg.playing);
          break;
        case 'ref':
          this.engine.setReferenceTone(msg.freq === undefined ? null : msg.freq);
          break;
        case 'externalGain':
          this.engine.externalGain = msg.gain;
          break;
        case 'reset':
          this.engine.reset();
          break;
        default:
          break;
      }
    };
  }

  process(inputs, outputs) {
    const out = outputs[0];
    if (!out || out.length === 0) return true;

    const left = out[0];
    const frames = left.length;

    let right;
    if (out.length > 1) {
      right = out[1];
    } else {
      // Mono output device: render the right channel into a scratch buffer so it
      // does not overwrite the left one.
      if (this.spare.length !== frames) this.spare = new Float32Array(frames);
      right = this.spare;
    }

    this.engine.render(left, right, frames);

    // Duplicate to any extra channels the device exposes.
    for (let ch = 2; ch < out.length; ch++) {
      out[ch].set(ch % 2 === 0 ? left : right);
    }

    // Meters at roughly 20 Hz - enough for a 60 fps animation to interpolate
    // against, cheap enough not to matter.
    this.meterCounter += frames;
    if (this.meterCounter >= 2048) {
      this.meterCounter = 0;
      const flash = new Array(MAX_STRINGS);
      for (let i = 0; i < MAX_STRINGS; i++) flash[i] = this.engine.stringActivity(i);
      this.port.postMessage({
        type: 'meter',
        level: this.engine.outputLevel,
        strum: this.engine.strumPosition,
        stringCount: this.engine.stringCount,
        flash,
        idle: this.engine.isIdle,
      });
    }

    // Never return false: the node stays alive and the graph is suspended
    // instead, which is what makes resuming instant.
    return true;
  }
}

registerProcessor('tanpura', TanpuraProcessor);
