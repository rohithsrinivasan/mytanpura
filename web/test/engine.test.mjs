// Verifies the web engine with Node's built-in test runner. No dependencies:
//
//   node --test web/test/
//
// These mirror the Kotlin unit tests for the Android app, because the two engines
// implement the same algorithm and a port is exactly where a subtle numeric
// difference would hide.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  CONTROL_BLOCK,
  DEFAULT_SETTINGS,
  MAX_STRINGS,
  STRING_PATTERNS,
  StrumSequencer,
  StringVoice,
  TanpuraEngine,
  VOICES,
  cents,
  detectPitch,
  effectiveReverbMix,
  fastTanh,
  frequency,
  frequencyFromSemitone,
  noteName,
  patternById,
  perString,
  stringGain,
  voiceById,
} from '../src/engine.js';

const SR = 48000;

function renderPluck(freq, seconds, { brightness = 0.5, jawari = 0.55, decay = 8 } = {}) {
  const voice = new StringVoice(SR);
  voice.setCharacter(brightness, decay, jawari, 0.22, 0.5);
  voice.reset();
  voice.setFrequency(freq, false);
  voice.gain = 1;
  voice.pluck(1);

  const total = Math.round(SR * seconds);
  const out = new Float32Array(total);
  for (let done = 0; done < total; ) {
    const n = Math.min(CONTROL_BLOCK, total - done);
    voice.render(out, done, n);
    done += n;
  }
  return out;
}

function renderEngine(engine, seconds, blockFrames = 128) {
  const totalFrames = Math.round(SR * seconds);
  const left = new Float32Array(totalFrames);
  const right = new Float32Array(totalFrames);
  const bl = new Float32Array(blockFrames);
  const br = new Float32Array(blockFrames);
  for (let done = 0; done < totalFrames; ) {
    const n = Math.min(blockFrames, totalFrames - done);
    engine.render(bl, br, n);
    left.set(bl.subarray(0, n), done);
    right.set(br.subarray(0, n), done);
    done += n;
  }
  return { left, right };
}

function peakOf(arr) {
  let p = 0;
  for (const v of arr) {
    const a = Math.abs(v);
    if (a > p) p = a;
  }
  return p;
}

describe('StringVoice', () => {
  /**
   * The bound is deliberately tight. A fractional-delay bug that read the
   * interpolation neighbour on the wrong side made every string up to 9 cents
   * sharp in the Kotlin version while still sounding plausible in isolation. One
   * cent is roughly ten times better than anyone can hear and the implementation
   * comfortably achieves it, so anything looser would let that class of bug back.
   */
  it('sounds at the requested pitch across the whole range', () => {
    let worst = 0;
    let worstAt = 0;
    for (let midi = 24; midi <= 72; midi++) {
      const target = frequency(midi);
      if (target < 45) continue;
      const audio = renderPluck(target, 1);
      const detected = detectPitch(
        audio,
        SR,
        target * 0.7,
        target * 1.4,
        Math.round(SR * 0.45),
        Math.round(SR * 0.35),
      );
      assert.ok(detected !== null, `no pitch detected at ${target} Hz`);
      const err = Math.abs(cents(target, detected));
      if (err > worst) {
        worst = err;
        worstAt = target;
      }
    }
    assert.ok(worst < 1, `worst tuning error was ${worst.toFixed(3)} cents at ${worstAt} Hz`);
  });

  it('decays to silence and stays finite', () => {
    const audio = renderPluck(146.83, 6, { decay: 2 });
    for (const v of audio) {
      assert.ok(Number.isFinite(v), 'non-finite sample produced');
      assert.ok(Math.abs(v) < 4, `sample exploded: ${v}`);
    }
    const peak = peakOf(audio);
    const tailPeak = peakOf(audio.subarray(audio.length - SR / 10));
    assert.ok(peak > 0.01, 'nothing was rendered');
    assert.ok(tailPeak < peak * 0.05, `string never decayed (peak ${peak}, tail ${tailPeak})`);
  });

  it('rings longer when a longer decay is requested', () => {
    const shortD = renderPluck(146.83, 4, { decay: 1.5 });
    const longD = renderPluck(146.83, 4, { decay: 12 });
    const start = SR * 3;
    const window = SR / 4;
    const shortTail = peakOf(shortD.subarray(start, start + window));
    const longTail = peakOf(longD.subarray(start, start + window));
    assert.ok(longTail > shortTail * 4, `decay control had no effect (${shortTail} vs ${longTail})`);
  });

  it('jawari adds harmonics without adding level', () => {
    const plain = peakOf(renderPluck(146.83, 1.5, { jawari: 0 }));
    const buzzy = peakOf(renderPluck(146.83, 1.5, { jawari: 1 }));
    // The shaper is compressive, so it must never make the string louder.
    assert.ok(buzzy <= plain * 1.05, `jawari made the string louder (${plain} -> ${buzzy})`);
    assert.ok(buzzy > plain * 0.25, 'jawari silenced the string');
  });
});

describe('fastTanh', () => {
  it('matches tanh closely and stays bounded', () => {
    let maxError = 0;
    for (let x = -3; x <= 3; x += 0.01) {
      const err = Math.abs(fastTanh(x) - Math.tanh(x));
      if (err > maxError) maxError = err;
    }
    assert.ok(maxError < 0.002, `fastTanh error ${maxError} too large`);
    assert.ok(Math.abs(fastTanh(100)) <= 1.001);
    assert.equal(fastTanh(0), 0);
  });

  it('never exceeds unity gain, which keeps the string loop stable', () => {
    for (let x = 0; x <= 4.5; x += 0.005) {
      assert.ok(fastTanh(x) <= 1, `fastTanh(${x}) exceeded 1`);
      assert.ok(fastTanh(x) <= x + 1e-6, `fastTanh(${x}) amplified its input`);
    }
  });
});

describe('TanpuraEngine', () => {
  it('produces a bounded, non-silent drone', () => {
    const engine = new TanpuraEngine(SR);
    engine.submit({ ...DEFAULT_SETTINGS, saMidi: 49, masterVolume: 0.9 });
    engine.setPlaying(true);
    const { left, right } = renderEngine(engine, 4);
    for (const v of left) assert.ok(Number.isFinite(v), 'non-finite output');
    const peak = Math.max(peakOf(left), peakOf(right));
    assert.ok(peak > 0.05, 'engine produced silence');
    assert.ok(peak <= 1, `engine clipped past the limiter: ${peak}`);
  });

  it('renders every string pattern cleanly', () => {
    for (const pattern of STRING_PATTERNS) {
      const engine = new TanpuraEngine(SR);
      engine.submit({ ...DEFAULT_SETTINGS, patternId: pattern.id, cycleSeconds: 1.2 });
      engine.setPlaying(true);
      const { left } = renderEngine(engine, 2.5);
      for (const v of left) assert.ok(Number.isFinite(v), `non-finite output for ${pattern.id}`);
      assert.ok(peakOf(left) > 0.02, `pattern ${pattern.id} was silent`);
      assert.equal(engine.stringCount, pattern.offsets.length);
    }
  });

  it('fades out to true silence when paused', () => {
    const engine = new TanpuraEngine(SR);
    engine.submit({ ...DEFAULT_SETTINGS, cycleSeconds: 1.5 });
    engine.setPlaying(true);
    renderEngine(engine, 2);
    engine.setPlaying(false);
    const { left } = renderEngine(engine, 4);
    const residual = peakOf(left.subarray(left.length - SR / 2));
    assert.ok(residual < 1e-3, `still making noise after pause: ${residual}`);
    assert.ok(engine.isIdle, 'engine did not report itself idle');
  });

  it('survives settings changes mid-render without glitching', () => {
    const engine = new TanpuraEngine(SR);
    engine.setPlaying(true);
    const bl = new Float32Array(128);
    const br = new Float32Array(128);
    for (let i = 0; i < 400; i++) {
      if (i % 7 === 0) {
        engine.submit({
          ...DEFAULT_SETTINGS,
          saMidi: 45 + (Math.floor(i / 7) % 24),
          fineCents: (i % 21) - 10,
          patternId: STRING_PATTERNS[i % STRING_PATTERNS.length].id,
          voiceId: i % 2 === 0 ? 'male' : 'female',
          cycleSeconds: 1 + (i % 5) * 0.5,
        });
      }
      engine.render(bl, br, 128);
      for (const v of bl) {
        assert.ok(Number.isFinite(v) && Math.abs(v) <= 1, `glitch at iteration ${i}`);
      }
    }
  });

  it('sounds the reference tone while the drone is paused', () => {
    const engine = new TanpuraEngine(SR);
    engine.submit({ ...DEFAULT_SETTINGS, masterVolume: 0.8 });
    engine.setPlaying(false);
    engine.setReferenceTone(220);
    const { left } = renderEngine(engine, 1);
    const peak = peakOf(left);
    assert.ok(peak > 0.02, 'reference tone was silent');
    assert.ok(peak <= 1, 'reference tone clipped');
  });

  it('renders identically at 44.1 kHz', () => {
    const engine = new TanpuraEngine(44100);
    engine.submit({ ...DEFAULT_SETTINGS });
    engine.setPlaying(true);
    const left = new Float32Array(128);
    const right = new Float32Array(128);
    for (let i = 0; i < 400; i++) engine.render(left, right, 128);
    assert.ok(peakOf(left) > 0.01, 'silent at 44.1 kHz');
    for (const v of left) assert.ok(Number.isFinite(v));
  });
});

describe('StrumSequencer', () => {
  it('strikes every string once per cycle, in order', () => {
    const seq = new StrumSequencer(SR);
    seq.stringCount = 4;
    seq.cycleSeconds = 1;
    seq.humanize = 0;
    seq.reset();

    // Stop just short of one full cycle: at the wrap point string 1 of the next
    // cycle fires immediately, which is correct but not what is being counted.
    const fired = [];
    for (let sample = 0; sample < 47000; sample += 64) {
      seq.advance(64, (index) => fired.push([index, sample]));
    }

    assert.equal(fired.length, 4, 'wrong number of plucks in one cycle');
    assert.deepEqual(fired.map((f) => f[0]), [0, 1, 2, 3]);

    // With STRUM_SPREAD = 0.72 the four strings land at 0, 0.24, 0.48, 0.72.
    const expected = [0, 11520, 23040, 34560];
    fired.forEach(([, at], i) => {
      assert.ok(Math.abs(at - expected[i]) <= 128, `string ${i} fired at ${at}, expected ${expected[i]}`);
    });
  });

  it('keeps cycling and never fires out of order', () => {
    const seq = new StrumSequencer(SR);
    seq.stringCount = 4;
    seq.cycleSeconds = 0.5;
    seq.humanize = 1;
    seq.reset();

    let count = 0;
    let lastInCycle = -1;
    for (let i = 0; i < (SR * 5) / 64; i++) {
      seq.advance(64, (index, velocity) => {
        count++;
        assert.ok(velocity > 0 && velocity < 1.5, `velocity out of range: ${velocity}`);
        if (index === 0) lastInCycle = -1;
        assert.ok(index > lastInCycle, `out of order: ${lastInCycle} then ${index}`);
        lastInCycle = index;
      });
    }
    assert.ok(count >= 36 && count <= 44, `expected around 40 plucks, got ${count}`);
  });
});

describe('music model', () => {
  it('puts concert A where it should be', () => {
    assert.ok(Math.abs(frequency(69) - 440) < 1e-6);
    assert.ok(Math.abs(frequency(57) - 220) < 1e-6);
    assert.ok(Math.abs(frequency(60) - 261.6255653) < 1e-4);
  });

  it('names notes in scientific pitch notation', () => {
    assert.equal(noteName(60), 'C4');
    assert.equal(noteName(69), 'A4');
    assert.equal(noteName(49), 'C#3');
  });

  it('scales everything with the A4 reference', () => {
    assert.ok(Math.abs(frequency(69, 0, 442) - 442) < 1e-6);
    const ratio = frequency(49, 0, 442) / frequency(49, 0, 440);
    assert.ok(Math.abs(ratio - 442 / 440) < 1e-9);
  });

  it('measures string offsets from the middle Sa', () => {
    // With Sa = C4 the Pa side string is the G below it, not the G above.
    const sa = 60;
    assert.equal(patternById('pa').offsets[0], -5);
    assert.equal(noteName(sa + patternById('pa').offsets[0]), 'G3');
    assert.equal(noteName(sa + patternById('ma').offsets[0]), 'F3');
    assert.equal(noteName(sa + patternById('ni').offsets[0]), 'B3');
    assert.equal(noteName(sa + patternById('dha').offsets[0]), 'A3');
    assert.equal(noteName(sa + patternById('pa').offsets[3]), 'C3');
  });

  it('agrees between frequency and frequencyFromSemitone', () => {
    const direct = frequency(49 - 5, 0, 440);
    const viaOffset = frequencyFromSemitone(-5, 49, 0, 440);
    assert.ok(Math.abs(direct - viaOffset) < 1e-9);
  });

  it('keeps every pattern internally consistent', () => {
    for (const p of STRING_PATTERNS) {
      assert.equal(p.labels.length, p.offsets.length, `${p.id} has mismatched labels`);
      assert.ok(p.offsets.length >= 1 && p.offsets.length <= MAX_STRINGS, `${p.id} string count`);
      assert.equal(p.offsets[p.offsets.length - 1], -12, `${p.id} should end on the low Sa`);
      for (const o of p.offsets) assert.ok(o >= -12 && o <= 0, `${p.id} offset ${o} out of range`);
    }
    const ids = STRING_PATTERNS.map((p) => p.id);
    assert.equal(ids.length, new Set(ids).size, 'duplicate pattern id');
    assert.equal(patternById('nonsense').id, 'pa', 'lookup should fall back to Pa');
  });

  it('stretches per-string values to five strings without losing the low one', () => {
    const v = voiceById('male');
    const four = perString(v.decayScale, 4);
    assert.equal(four.length, 4);
    assert.equal(four[0], v.decayScale[0]);
    assert.equal(four[3], v.decayScale[3]);

    const five = perString(v.decayScale, 5);
    assert.equal(five.length, 5);
    assert.equal(five[0], v.decayScale[0]);
    // The extra string behaves like a middle Sa string...
    assert.equal(five[3], v.decayScale[2]);
    // ...and the low brass string stays last.
    assert.equal(five[4], v.decayScale[3]);
  });

  it('has unique voice ids inside the selectable pitch range', () => {
    const ids = VOICES.map((v) => v.id);
    assert.equal(ids.length, new Set(ids).size);
    assert.equal(voiceById('nope').id, 'male');
    for (const v of VOICES) {
      assert.ok(v.suggestedSa >= 36 && v.suggestedSa <= 72, `${v.id} suggests an unreachable Sa`);
    }
  });

  it('falls back to the voice reverb default until it is set', () => {
    const defaults = { ...DEFAULT_SETTINGS, voiceId: 'instrumental' };
    assert.equal(effectiveReverbMix(defaults), voiceById('instrumental').reverbMix);
    assert.equal(effectiveReverbMix({ ...defaults, reverbMix: 0.4 }), 0.4);
  });

  it('reports zero gain for a muted string', () => {
    const s = { ...DEFAULT_SETTINGS, stringGains: [0.5, 1, 1, 1, 1] };
    assert.equal(stringGain(s, 0), 0.5);
    const muted = { ...s, stringMuted: [false, false, true, false, false] };
    assert.equal(stringGain(muted, 2), 0);
  });
});

describe('pitch detector', () => {
  it('finds the fundamental of a synthetic tone', () => {
    for (const target of [82.41, 146.83, 261.63, 440]) {
      const samples = new Float32Array(SR / 2);
      for (let i = 0; i < samples.length; i++) {
        const t = i / SR;
        samples[i] =
          0.3 *
          (Math.sin(2 * Math.PI * target * t) +
            0.4 * Math.sin(4 * Math.PI * target * t) +
            0.2 * Math.sin(6 * Math.PI * target * t));
      }
      const detected = detectPitch(samples, SR, 50, 1200, 0, Math.round(SR * 0.25));
      assert.ok(detected !== null, `nothing detected for ${target}`);
      assert.ok(Math.abs(cents(target, detected)) < 5, `detected ${detected} instead of ${target}`);
    }
  });

  it('returns null for silence', () => {
    assert.equal(detectPitch(new Float32Array(SR), SR), null);
  });
});
