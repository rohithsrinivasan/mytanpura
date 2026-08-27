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
