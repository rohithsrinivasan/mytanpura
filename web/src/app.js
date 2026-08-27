// Tanpura - web app entry point.
//
// The DSP runs in an AudioWorklet (see tanpura-worklet.js, generated from
// engine.js). This module owns the audio graph, the settings state, persistence
// and the DOM. Nothing here touches sample buffers.

import {
  DEFAULT_SETTINGS,
  MAX_SA,
  MIN_SA,
  STRING_PATTERNS,
  VOICES,
  effectiveReverbMix,
  formatCents,
  frequency,
  frequencyFromSemitone,
  noteName,
  patternById,
  voiceById,
} from './engine.js';
import { InstrumentView } from './instrument.js';
import {
  button,
  card,
  centreHorizontally,
  chips,
  formatDuration,
  h,
  hint,
  slider,
  toast,
  toggleRow,
} from './ui.js';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const STORAGE_SETTINGS = 'tanpura.settings.v1';
const STORAGE_PRESETS = 'tanpura.presets.v1';

const SLOWEST_CYCLE = 8;
const FASTEST_CYCLE = 0.9;

/** Reference tones offered by the tuner, as offsets from Sa. */
const TONE_GRID = [
  { label: 'Sa↓', semitone: -12 },
  { label: 'Ma↓', semitone: -7 },
  { label: 'Pa↓', semitone: -5 },
  { label: 'Ni↓', semitone: -1 },
  { label: 'Sa', semitone: 0 },
  { label: 're', semitone: 1 },
  { label: 'Re', semitone: 2 },
  { label: 'ga', semitone: 3 },
  { label: 'Ga', semitone: 4 },
  { label: 'Ma', semitone: 5 },
  { label: 'Ma♯', semitone: 6 },
  { label: 'Pa', semitone: 7 },
  { label: 'dha', semitone: 8 },
  { label: 'Dha', semitone: 9 },
  { label: 'ni', semitone: 10 },
  { label: 'Ni', semitone: 11 },
  { label: 'Sa↑', semitone: 12 },
];

const BUILT_IN_PRESETS = [
  { id: 'builtin-male', name: 'Male voice · C#', patch: { saMidi: 49, voiceId: 'male', patternId: 'pa', cycleSeconds: 3.4 } },
  { id: 'builtin-female', name: 'Female voice · G#', patch: { saMidi: 56, voiceId: 'female', patternId: 'pa', cycleSeconds: 3.0 } },
  { id: 'builtin-malkauns', name: 'Malkauns · Ma tuning', patch: { saMidi: 48, voiceId: 'male', patternId: 'ma', cycleSeconds: 3.8 } },
  { id: 'builtin-todi', name: 'Todi · komal Ni', patch: { saMidi: 51, voiceId: 'female', patternId: 'ni_komal', cycleSeconds: 3.2 } },
  { id: 'builtin-bansuri', name: 'Bansuri riyaaz · A2', patch: { saMidi: 45, voiceId: 'instrumental', patternId: 'pa5', cycleSeconds: 4.2 } },
];

let settings = loadSettings();

const transport = {
  playing: false,
  refSemitone: null,
  source: 'synth', // 'synth' | 'loop'
  timer: { running: false, total: 0, remaining: 0, fade: 15, minutes: 30 },
};

let presets = loadPresets();
let activeTab = 'player';
let panelRefresh = () => {};
let saveHandle = 0;

function loadSettings() {
  try {
    const raw = localStorage.getItem(STORAGE_SETTINGS);
    if (!raw) return { ...DEFAULT_SETTINGS };
    // Merge over the defaults so a blob saved by an older version still loads.
    return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function loadPresets() {
  try {
    const raw = localStorage.getItem(STORAGE_PRESETS);
    if (!raw) return [...BUILT_IN_PRESETS];
    const saved = JSON.parse(raw);
    return Array.isArray(saved) && saved.length ? saved : [...BUILT_IN_PRESETS];
  } catch {
    return [...BUILT_IN_PRESETS];
  }
}

function persist() {
  clearTimeout(saveHandle);
  saveHandle = setTimeout(() => {
    try {
      localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings));
    } catch {
      // Private browsing or a full quota; not worth interrupting practice for.
    }
  }, 400);
}

function persistPresets() {
  try {
    localStorage.setItem(STORAGE_PRESETS, JSON.stringify(presets));
  } catch {
    /* ignore */
  }
}

/** Applies a settings patch, pushes it to the audio thread and refreshes the UI. */
function update(patch) {
  settings = { ...settings, ...patch };
  audio.post({ type: 'settings', settings });
  if (transport.refSemitone !== null) {
    audio.post({
      type: 'ref',
      freq: frequencyFromSemitone(transport.refSemitone, settings.saMidi, settings.fineCents, settings.a4Hz),
    });
  }
  loopPlayer.applyPitch();
  persist();
  refreshAll();
}

function setStringGain(index, gain) {
  const next = settings.stringGains.slice();
  next[index] = gain;
  update({ stringGains: next });
}

function setStringMuted(index, muted) {
  const next = settings.stringMuted.slice();
  next[index] = muted;
  update({ stringMuted: next });
}

// ---------------------------------------------------------------------------
// Audio graph
// ---------------------------------------------------------------------------

const audio = {
  ctx: null,
  node: null,
  ready: false,
  starting: null,
  idleHandle: 0,

  /**
   * Creates the AudioContext and worklet on first use. Must be called from a
   * user gesture: browsers refuse to start audio otherwise.
   */
  async ensure() {
    if (this.ready) {
      if (this.ctx.state === 'suspended') await this.ctx.resume();
      return true;
    }
    if (this.starting) return this.starting;

    this.starting = (async () => {
      try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) throw new Error('Web Audio is not available in this browser.');
        this.ctx = new Ctx({ latencyHint: 'playback' });
        if (!this.ctx.audioWorklet) throw new Error('AudioWorklet is not available in this browser.');

        await this.ctx.audioWorklet.addModule('./tanpura-worklet.js');
        this.node = new AudioWorkletNode(this.ctx, 'tanpura', {
          numberOfInputs: 0,
          numberOfOutputs: 1,
          outputChannelCount: [2],
        });
        this.node.port.onmessage = (event) => {
          if (event.data.type === 'meter') onMeter(event.data);
        };
        this.node.connect(this.ctx.destination);
        this.post({ type: 'settings', settings });
        this.ready = true;
        if (this.ctx.state === 'suspended') await this.ctx.resume();
        return true;
      } catch (err) {
        console.error(err);
        toast(err.message || 'Could not start audio.');
        this.starting = null;
        return false;
      }
    })();

    return this.starting;
  },

  post(message) {
    if (this.node) this.node.port.postMessage(message);
  },

  /** Suspends the context once the engine has gone quiet, to save battery. */
  scheduleIdleCheck(idle) {
    clearTimeout(this.idleHandle);
    if (!idle || transport.playing) return;
    this.idleHandle = setTimeout(() => {
      if (!transport.playing && transport.refSemitone === null && this.ctx && this.ctx.state === 'running') {
        this.ctx.suspend().catch(() => {});
      }
    }, 2500);
  },
};

/**
 * A silent looping element kept playing alongside the worklet.
 *
 * Chrome on Android only shows lock-screen media controls for a page with a
 * playing media element, and only then does the Media Session API become useful.
 * Web Audio alone does not qualify.
 */
const keepAlive = {
  el: null,
  ensure() {
    if (this.el) return;
    const el = h('audio', { loop: 'loop' });
    el.src = silentWavUrl();
    el.volume = 0.0001;
    el.setAttribute('playsinline', '');
    document.body.append(el);
    this.el = el;
  },
  play() {
    this.ensure();
    this.el.play().catch(() => {});
  },
  pause() {
    if (this.el) this.el.pause();
  },
};

function silentWavUrl(seconds = 2, rate = 8000) {
  const frames = seconds * rate;
  const dataSize = frames * 2;
  const buf = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buf);
  const text = (offset, s) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };
  text(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  text(8, 'WAVE');
  text(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, 'data');
  view.setUint32(40, dataSize, true);
  return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
}

/**
 * Plays a recording the user picked, on repeat.
 *
 * This is the browser equivalent of the Android app's loop mode: for a long
 * continuous tanpura recording. It uses a plain media element rather than the
 * worklet, so a three-hour file streams instead of being decoded into memory.
 */
const loopPlayer = {
  el: null,
  url: null,
  name: null,

  load(file) {
    this.dispose();
    this.url = URL.createObjectURL(file);
    this.name = file.name;
    const el = h('audio', { loop: 'loop' });
    el.src = this.url;
    el.setAttribute('playsinline', '');
    el.preload = 'auto';
    el.addEventListener('error', () => toast('That file could not be decoded.'));
    document.body.append(el);
    this.el = el;
    this.applyPitch();
    transport.source = 'loop';
    refreshAll();
  },

  dispose() {
    if (this.el) {
      this.el.pause();
      this.el.remove();
      this.el = null;
    }
    if (this.url) {
      URL.revokeObjectURL(this.url);
      this.url = null;
    }
    this.name = null;
  },

  applyPitch() {
    if (!this.el) return;
    const rate = Math.pow(2, (settings.loopPitchCents || 0) / 1200);
    // Shifting pitch also shifts speed, the same way a tape machine would.
    this.el.preservesPitch = false;
    this.el.playbackRate = Math.min(4, Math.max(0.25, rate));
    this.el.volume = Math.min(1, Math.max(0, settings.masterVolume));
  },

  play() {
    if (!this.el) return false;
    this.applyPitch();
    this.el.play().catch(() => toast('The browser blocked playback; tap play again.'));
    return true;
  },

  pause() {
    if (this.el) this.el.pause();
  },
};

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

async function play() {
  if (transport.source === 'loop') {
    if (!loopPlayer.el) {
      toast('Pick a recording first, in the Audio tab.');
      return;
    }
    transport.playing = true;
    loopPlayer.play();
  } else {
    const ok = await audio.ensure();
    if (!ok) return;
    transport.playing = true;
    audio.post({ type: 'externalGain', gain: 1 });
    audio.post({ type: 'play', playing: true });
  }
  keepAlive.play();
  instrument.start();
  updateMediaSession();
  refreshAll();
}

function pause() {
  transport.playing = false;
  audio.post({ type: 'play', playing: false });
  loopPlayer.pause();
  keepAlive.pause();
  cancelTimer();
  instrument.stop();
  updateMediaSession();
  refreshAll();
}

function toggle() {
  if (transport.playing) pause();
  else play();
}

function onMeter(data) {
  instrument.setMeter(data);
  audio.scheduleIdleCheck(data.idle);
}

function updateMediaSession() {
  if (!('mediaSession' in navigator)) return;
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: `Sa = ${noteName(settings.saMidi)}${settings.fineCents ? ` ${formatCents(settings.fineCents)}` : ''}`,
      artist: transport.source === 'loop' ? loopPlayer.name || 'Your recording' : `${voiceById(settings.voiceId).label} · ${patternById(settings.patternId).label}`,
      album: 'Tanpura',
    });
    navigator.mediaSession.playbackState = transport.playing ? 'playing' : 'paused';
    navigator.mediaSession.setActionHandler('play', () => play());
    navigator.mediaSession.setActionHandler('pause', () => pause());
    navigator.mediaSession.setActionHandler('stop', () => pause());
  } catch {
    /* Media Session is best-effort. */
  }
}

// ---------------------------------------------------------------------------
// Practice timer
// ---------------------------------------------------------------------------

let timerHandle = 0;

function startTimer(minutes, fadeSeconds) {
  cancelTimer();
  transport.timer = {
    running: true,
    minutes,
    fade: fadeSeconds,
    total: minutes * 60,
    remaining: minutes * 60,
  };
  if (!transport.playing) play();

  timerHandle = setInterval(() => {
    const t = transport.timer;
    t.remaining -= 1;
    if (t.fade > 0 && t.remaining < t.fade) {
      const g = Math.max(0, t.remaining / t.fade);
      audio.post({ type: 'externalGain', gain: g });
      if (loopPlayer.el) loopPlayer.el.volume = settings.masterVolume * g;
    }
    if (t.remaining <= 0) {
      cancelTimer();
      pause();
      audio.post({ type: 'externalGain', gain: 1 });
      loopPlayer.applyPitch();
      toast('Practice timer finished.');
    }
    refreshAll();
  }, 1000);
  refreshAll();
}

function cancelTimer() {
  clearInterval(timerHandle);
  timerHandle = 0;
  if (transport.timer.running) {
    transport.timer.running = false;
    transport.timer.remaining = 0;
    audio.post({ type: 'externalGain', gain: 1 });
    loopPlayer.applyPitch();
  }
}

// ---------------------------------------------------------------------------
// Tuner
// ---------------------------------------------------------------------------

async function setReferenceTone(semitone) {
  if (semitone === null) {
    transport.refSemitone = null;
    audio.post({ type: 'ref' });
    refreshAll();
    return;
  }
  const ok = await audio.ensure();
  if (!ok) return;
  transport.refSemitone = semitone;
  audio.post({
    type: 'ref',
    freq: frequencyFromSemitone(semitone, settings.saMidi, settings.fineCents, settings.a4Hz),
  });
  refreshAll();
}

// ---------------------------------------------------------------------------
// Stage: pitch readout, instrument, transport
// ---------------------------------------------------------------------------

const els = {};
let instrument;

function buildStage() {
  const saNote = h('strong', { class: 'sa-note' });
  const saDetail = h('span', { class: 'sa-detail' });

  const down = h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Lower Sa by a semitone', text: '‹' });
  const up = h('button', { type: 'button', class: 'icon-btn', 'aria-label': 'Raise Sa by a semitone', text: '›' });
  down.addEventListener('click', () => update({ saMidi: Math.max(MIN_SA, settings.saMidi - 1) }));
  up.addEventListener('click', () => update({ saMidi: Math.min(MAX_SA, settings.saMidi + 1) }));

  const strip = h('div', { class: 'pitch-strip', role: 'radiogroup', 'aria-label': 'Choose Sa' });
  const stripButtons = [];
  for (let midi = MIN_SA; midi <= MAX_SA; midi++) {
    const btn = h('button', { type: 'button', class: 'pitch-cell', role: 'radio', text: noteName(midi) });
    btn.addEventListener('click', () => update({ saMidi: midi }));
    strip.append(btn);
    stripButtons.push({ btn, midi });
  }

  const canvas = h('canvas', { class: 'instrument', 'aria-label': 'Tanpura' });
  instrument = new InstrumentView(canvas);

  const playBtn = h('button', { type: 'button', class: 'play-btn', 'aria-label': 'Play' });
  const playIcon = h('span', { class: 'play-icon' });
  playBtn.append(playIcon);
  playBtn.addEventListener('click', toggle);

  const presetsBtn = h('button', { type: 'button', class: 'pill', text: 'Presets' });
  presetsBtn.addEventListener('click', openPresets);

  const timerBtn = h('button', { type: 'button', class: 'pill' });
  timerBtn.addEventListener('click', openTimer);

  els.saNote = saNote;
  els.saDetail = saDetail;
  els.stripButtons = stripButtons;
  els.strip = strip;
  els.playBtn = playBtn;
  els.playIcon = playIcon;
  els.timerBtn = timerBtn;

  return h(
    'div',
    { class: 'stage' },
    h(
      'div',
      { class: 'pitch-head' },
      down,
      h('div', { class: 'sa-block' }, saNote, saDetail),
      up,
    ),
    strip,
    canvas,
    h('div', { class: 'transport' }, presetsBtn, playBtn, timerBtn),
  );
}

function refreshStage() {
  els.saNote.textContent = noteName(settings.saMidi);
  const hz = frequency(settings.saMidi, settings.fineCents, settings.a4Hz);
  els.saDetail.textContent =
    `Sa · ${hz.toFixed(2)} Hz` + (settings.fineCents ? `  ${formatCents(settings.fineCents)}` : '');

  for (const { btn, midi } of els.stripButtons) {
    const on = midi === settings.saMidi;
    btn.classList.toggle('is-on', on);
    btn.setAttribute('aria-checked', on ? 'true' : 'false');
    // Horizontal-only: scrollIntoView would also scroll the control panel.
    if (on && els.lastSa !== settings.saMidi) centreHorizontally(els.strip, btn);
  }
  els.lastSa = settings.saMidi;

  els.playBtn.classList.toggle('is-playing', transport.playing);
  els.playBtn.setAttribute('aria-label', transport.playing ? 'Pause' : 'Play');
  els.timerBtn.textContent = transport.timer.running
    ? formatDuration(transport.timer.remaining)
    : 'Timer';
  els.timerBtn.classList.toggle('is-on', transport.timer.running);

  instrument.setLabels(patternById(settings.patternId).labels);
}

// ---------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------

function playerPanel() {
  const cards = [];

  cards.push(
    card({
      title: 'Fine tuning',
      children: [
        slider({
          label: 'Offset from equal temperament',
          min: -50,
          max: 50,
          step: 0.5,
          get: () => settings.fineCents,
          set: (v) => update({ fineCents: v }),
          format: (v) => formatCents(v),
        }),
        hint(
          () =>
            `Sa sounds at ${frequency(settings.saMidi, settings.fineCents, settings.a4Hz).toFixed(2)} Hz ` +
            `(A4 = ${Math.round(settings.a4Hz)} Hz)`,
        ),
        button({
          text: 'Reset to 0 cents',
          variant: 'ghost',
          onClick: () => update({ fineCents: 0 }),
        }),
      ],
    }),
  );

  cards.push(
    card({
      title: 'Tuning · first string',
      children: [
        chips({
          options: STRING_PATTERNS,
          getSelected: () => settings.patternId,
          keyOf: (p) => p.id,
          labelOf: (p) => p.label,
          ariaLabel: 'First string tuning',
          onSelect: (p) => update({ patternId: p.id }),
        }),
        hint(() => patternById(settings.patternId).note),
      ],
    }),
  );

  cards.push(
    card({
      title: 'Instrument',
      children: [
        chips({
          options: VOICES,
          getSelected: () => settings.voiceId,
          keyOf: (v) => v.id,
          labelOf: (v) => v.label,
          ariaLabel: 'Instrument voice',
          onSelect: (v) => update({ voiceId: v.id }),
        }),
        hint(() => voiceById(settings.voiceId).description),
        button({
          text: 'Use suggested Sa',
          variant: 'ghost',
          onClick: () => update({ saMidi: voiceById(settings.voiceId).suggestedSa }),
        }),
      ],
    }),
  );

  cards.push(
    card({
      title: 'Performance',
      children: [
        slider({
          label: 'Strum speed',
          min: 0,
          max: 1,
          step: 0.001,
          get: () => (SLOWEST_CYCLE - settings.cycleSeconds) / (SLOWEST_CYCLE - FASTEST_CYCLE),
          set: (fraction) =>
            update({ cycleSeconds: SLOWEST_CYCLE - fraction * (SLOWEST_CYCLE - FASTEST_CYCLE) }),
          format: () => `${settings.cycleSeconds.toFixed(1)} s / cycle`,
        }),
        slider({
          label: 'Volume',
          min: 0,
          max: 1,
          step: 0.01,
          get: () => settings.masterVolume,
          set: (v) => update({ masterVolume: v }),
          format: (v) => `${Math.round(v * 100)}%`,
        }),
      ],
    }),
  );

  cards.push(
    card({
      title: 'Tone',
      collapsible: true,
      children: [
        slider({
          label: 'Brightness',
          min: -1,
          max: 1,
          step: 0.01,
          get: () => settings.brightnessTrim,
          set: (v) => update({ brightnessTrim: v }),
          format: trimText,
        }),
        slider({
          label: 'Jawari (bridge buzz)',
          min: -1,
          max: 1,
          step: 0.01,
          get: () => settings.jawariTrim,
          set: (v) => update({ jawariTrim: v }),
          format: trimText,
        }),
        slider({
          label: 'Sustain',
          min: 0.5,
          max: 2,
          step: 0.01,
          get: () => settings.decayScale,
          set: (v) => update({ decayScale: v }),
          format: (v) => `${v.toFixed(2)}×`,
        }),
        slider({
          label: 'Room',
          min: 0,
          max: 0.6,
          step: 0.005,
          get: () => effectiveReverbMix(settings),
          set: (v) => update({ reverbMix: v }),
          format: (v) => `${Math.round((v / 0.6) * 100)}%`,
        }),
        slider({
          label: 'Human feel',
          min: 0,
          max: 1,
          step: 0.01,
          get: () => settings.humanize,
          set: (v) => update({ humanize: v }),
          format: (v) => (v < 0.02 ? 'off' : `${Math.round(v * 100)}%`),
        }),
        hint(
          'Human feel varies the timing and strength of each pluck. Turn it off for ' +
            'a perfectly even reference drone.',
        ),
      ],
    }),
  );

  const stringChildren = [];
  const count = patternById(settings.patternId).offsets.length;
  const labels = patternById(settings.patternId).labels;
  for (let i = 0; i < count; i++) {
    const index = i;
    stringChildren.push(
      slider({
        label: labels[index] || `String ${index + 1}`,
        min: 0,
        max: 1.2,
        step: 0.01,
        get: () => (settings.stringGains[index] === undefined ? 1 : settings.stringGains[index]),
        set: (v) => setStringGain(index, v),
        format: (v) => `${Math.round(v * 100)}%`,
        disabled: () => !!settings.stringMuted[index],
      }),
    );
    stringChildren.push(
      toggleRow({
        label: `${labels[index]} sounding`,
        get: () => !settings.stringMuted[index],
        set: (on) => setStringMuted(index, !on),
      }),
    );
  }
  stringChildren.push(hint('Mute the first string to hear only Sa, the way a shruti box would.'));
  cards.push(card({ title: 'Strings', collapsible: true, children: stringChildren }));

  return cards;
}

function trimText(v) {
  if (v > 0.01) return `+${Math.round(v * 100)}`;
  if (v < -0.01) return `${Math.round(v * 100)}`;
  return '0';
}

function tunerPanel() {
  const grid = h('div', { class: 'tone-grid' });
  const cells = TONE_GRID.map((entry) => {
    const btn = h(
      'button',
      { type: 'button', class: 'tone-cell' },
      h('strong', { text: entry.label }),
      h('small', {}),
    );
    btn.addEventListener('click', () =>
      setReferenceTone(transport.refSemitone === entry.semitone ? null : entry.semitone),
    );
    grid.append(btn);
    return { btn, entry };
  });

  const readout = h('p', { class: 'tone-readout' });

  const gridCard = {
    el: h('section', { class: 'card' },
      h('div', { class: 'card-head' }, h('h2', { class: 'card-title', text: 'Reference tone' })),
      h('div', { class: 'card-body' }, readout, grid,
        h('button', { type: 'button', class: 'btn btn-ghost btn-full', text: 'Stop tone', onClick: () => setReferenceTone(null) })),
    ),
    refresh: () => {
      const active = transport.refSemitone;
      if (active === null) {
        readout.textContent = 'Tap a swara to sound it';
        readout.classList.remove('is-on');
      } else {
        const entry = TONE_GRID.find((t) => t.semitone === active);
        const freq = frequencyFromSemitone(active, settings.saMidi, settings.fineCents, settings.a4Hz);
        readout.textContent = `${entry.label}  ·  ${freq.toFixed(2)} Hz`;
        readout.classList.add('is-on');
      }
      for (const { btn, entry } of cells) {
        btn.classList.toggle('is-on', active === entry.semitone);
        btn.querySelector('small').textContent = noteName(settings.saMidi + entry.semitone);
      }
    },
  };
  gridCard.refresh();

  const presetRow = h('div', { class: 'row' });
  for (const hz of [432, 440, 442, 444]) {
    const b = h('button', { type: 'button', class: 'btn btn-ghost', text: String(hz) });
    b.addEventListener('click', () => update({ a4Hz: hz }));
    presetRow.append(b);
  }

  return [
    gridCard,
    card({
      title: 'Concert pitch',
      children: [
        slider({
          label: 'A4 reference',
          min: 415,
          max: 466,
          step: 1,
          get: () => settings.a4Hz,
          set: (v) => update({ a4Hz: Math.round(v) }),
          format: (v) => `${Math.round(v)} Hz`,
        }),
        hint(
          'Set this to match the harmonium or keyboard you play with. Everything ' +
            'else follows it.',
        ),
        { el: presetRow, refresh: () => {} },
      ],
    }),
  ];
}

function audioPanel() {
  const fileInput = h('input', { type: 'file', accept: 'audio/*', class: 'visually-hidden' });
  fileInput.addEventListener('change', () => {
    const file = fileInput.files && fileInput.files[0];
    if (file) loopPlayer.load(file);
  });

  const status = h('p', { class: 'hint' });

  const sourceCard = {
    el: h('section', { class: 'card' },
      h('div', { class: 'card-head' }, h('h2', { class: 'card-title', text: 'Sound source' })),
      h('div', { class: 'card-body' },
        sourceRow('synth', 'Built-in tanpura', 'Modelled strings with jawari. Any Sa, perfectly in tune.'),
        sourceRow('loop', 'Loop your own recording', 'Play any long tanpura recording on repeat.'),
      ),
    ),
    refresh: () => {
      for (const [value, el] of Object.entries(sourceRows)) {
        el.classList.toggle('is-on', transport.source === value);
        el.querySelector('input').checked = transport.source === value;
      }
    },
  };

  const loopCard = card({
    title: 'Your recording',
    children: [
      hint(
        'For a continuous tanpura recording of any length. The file is streamed ' +
          'from your device and looped - it is never uploaded anywhere.',
      ),
      { el: status, refresh: () => {
        status.textContent = loopPlayer.name
          ? `Loaded: ${loopPlayer.name}`
          : 'No recording chosen yet.';
      } },
      button({
        text: 'Choose a recording',
        full: true,
        onClick: () => fileInput.click(),
      }),
      slider({
        label: 'Pitch shift',
        min: -1200,
        max: 1200,
        step: 1,
        get: () => settings.loopPitchCents || 0,
        set: (v) => update({ loopPitchCents: Math.round(v) }),
        format: (v) => formatCents(v),
        disabled: () => !loopPlayer.el,
      }),
      hint('Shifting the pitch of a recording also changes its speed, like a tape machine.'),
      button({
        text: 'Remove recording',
        variant: 'ghost',
        onClick: () => {
          loopPlayer.dispose();
          transport.source = 'synth';
          refreshAll();
        },
      }),
      { el: fileInput, refresh: () => {} },
    ],
  });

  return [
    sourceCard,
    loopCard,
    card({
      title: 'About the sound',
      children: [
        hint(
          'The built-in tanpura is synthesised, not sampled: a plucked-string model ' +
            'with a jawari bridge. That is why it is in tune at any pitch, never ' +
            'loops, and needs no audio downloads.',
        ),
        hint(
          'Recordings you load stay on your device. If you want to share a build ' +
            'with recordings included, use audio you made yourself or have a licence for.',
        ),
      ],
    }),
  ];
}

const sourceRows = {};

function sourceRow(value, title, subtitle) {
  const input = h('input', { type: 'radio', name: 'source', value });
  const el = h(
    'label',
    { class: 'source-row' },
    input,
    h('span', {}, h('strong', { text: title }), h('small', { text: subtitle })),
  );
  input.addEventListener('change', () => {
    if (value === 'loop' && !loopPlayer.el) {
      toast('Choose a recording first.');
      refreshAll();
      return;
    }
    if (transport.playing) pause();
    transport.source = value;
    refreshAll();
  });
  sourceRows[value] = el;
  return el;
}

function settingsPanel() {
  return [
    card({
      title: 'Session',
      children: [
        toggleRow({
          label: 'Keep the screen awake',
          sub: 'Useful when the phone is propped up during riyaaz.',
          get: () => wakeLock.wanted,
          set: (on) => wakeLock.set(on),
        }),
        hint(
          'Playback continues in the background on most browsers, and appears in ' +
            'your notification shade so you can pause it from the lock screen.',
        ),
      ],
    }),
    card({
      title: 'Install',
      children: [
        hint(
          'This works as an app: on Android use Chrome’s "Add to Home screen", ' +
            'on iPhone use Share → "Add to Home Screen". It then opens full screen ' +
            'and works offline.',
        ),
      ],
    }),
    card({
      title: 'Reset',
      children: [
        button({
          text: 'Reset tone and tuning to defaults',
          variant: 'ghost',
          full: true,
          onClick: () => {
            settings = { ...DEFAULT_SETTINGS };
            audio.post({ type: 'settings', settings });
            persist();
            refreshAll();
            toast('Settings reset.');
          },
        }),
      ],
    }),
    card({
      title: 'About',
      children: [
        hint(
          'A tanpura for riyaaz: pick your Sa, pick the first-string tuning the ' +
            'raga wants, and let it run. Each string is a physically modelled ' +
            'plucked string with a jawari bridge, tuned to within a fraction of a ' +
            'cent at every pitch.',
        ),
        hint('There is a native Android version of this app too, with true background playback.'),
      ],
    }),
  ];
}

/** Screen Wake Lock, where the browser supports it. */
const wakeLock = {
  wanted: false,
  sentinel: null,
  async set(on) {
    this.wanted = on;
    if (!('wakeLock' in navigator)) {
      if (on) toast('This browser cannot keep the screen awake.');
      this.wanted = false;
      refreshAll();
      return;
    }
    try {
      if (on) {
        this.sentinel = await navigator.wakeLock.request('screen');
        this.sentinel.addEventListener('release', () => {
          this.sentinel = null;
        });
      } else if (this.sentinel) {
        await this.sentinel.release();
        this.sentinel = null;
      }
    } catch {
      this.wanted = false;
      toast('The browser refused the wake lock.');
    }
    refreshAll();
  },
};

// ---------------------------------------------------------------------------
// Modals
// ---------------------------------------------------------------------------

function openModal(title, buildBody) {
  const body = h('div', { class: 'modal-body' });
  const close = h('button', { type: 'button', class: 'icon-btn', text: '✕', 'aria-label': 'Close' });
  const sheet = h(
    'div',
    { class: 'modal-sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('div', { class: 'modal-head' }, h('h2', { text: title }), close),
    body,
  );
  const backdrop = h('div', { class: 'modal' }, sheet);
  const dismiss = () => backdrop.remove();
  close.addEventListener('click', dismiss);
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) dismiss();
  });
  document.body.append(backdrop);
  requestAnimationFrame(() => backdrop.classList.add('is-in'));
  buildBody(body, dismiss);
  return dismiss;
}

function openPresets() {
  openModal('Presets', (body, dismiss) => {
    const nameInput = h('input', {
      type: 'text',
      class: 'text-input',
      placeholder: 'Preset name',
      maxlength: '40',
    });
    nameInput.value = `${voiceById(settings.voiceId).label} · ${noteName(settings.saMidi)}`;

    const list = h('div', { class: 'preset-list' });

    const renderList = () => {
      list.textContent = '';
      if (!presets.length) {
        list.append(h('p', { class: 'hint', text: 'No presets yet.' }));
        return;
      }
      for (const preset of presets) {
        const applyBtn = h('button', {
          type: 'button',
          class: 'preset-apply',
          onClick: () => {
            update(preset.patch);
            dismiss();
          },
        },
          h('strong', { text: preset.name }),
          h('small', { text: summarise(preset.patch) }),
        );
        const del = h('button', {
          type: 'button',
          class: 'icon-btn',
          text: '🗑',
          'aria-label': `Delete ${preset.name}`,
          onClick: () => {
            presets = presets.filter((p) => p.id !== preset.id);
            persistPresets();
            renderList();
          },
        });
        list.append(h('div', { class: 'preset-row' }, applyBtn, del));
      }
    };
    renderList();

    const save = h('button', {
      type: 'button',
      class: 'btn btn-primary',
      text: 'Save',
      onClick: () => {
        const name = nameInput.value.trim() || 'Untitled';
        presets = [
          ...presets,
          {
            id: `user-${Date.now()}`,
            name,
            patch: {
              saMidi: settings.saMidi,
              fineCents: settings.fineCents,
              a4Hz: settings.a4Hz,
              patternId: settings.patternId,
              voiceId: settings.voiceId,
              cycleSeconds: settings.cycleSeconds,
              brightnessTrim: settings.brightnessTrim,
              jawariTrim: settings.jawariTrim,
              decayScale: settings.decayScale,
              reverbMix: settings.reverbMix,
              humanize: settings.humanize,
            },
          },
        ];
        persistPresets();
        renderList();
        toast(`Saved "${name}".`);
      },
    });

    body.append(
      h('div', { class: 'row' }, nameInput, save),
      h('p', { class: 'hint', text: 'Saves the current pitch, tuning, instrument and tone.' }),
      list,
    );
  });
}

function summarise(patch) {
  const parts = [];
  if (patch.saMidi !== undefined) parts.push(`Sa ${noteName(patch.saMidi)}`);
  if (patch.patternId) parts.push(patternById(patch.patternId).label);
  if (patch.cycleSeconds) parts.push(`${patch.cycleSeconds.toFixed(1)} s`);
  return parts.join(' · ');
}

function openTimer() {
  openModal('Practice timer', (body, dismiss) => {
    let minutes = transport.timer.minutes;
    let fade = transport.timer.fade;

    const readout = h('p', { class: 'hint' });
    const quick = h('div', { class: 'chips' });
    const quickButtons = [];
    for (const m of [5, 10, 15, 20, 30, 45, 60, 90]) {
      const b = h('button', { type: 'button', class: 'chip', text: `${m} min` });
      b.addEventListener('click', () => {
        minutes = m;
        sync();
      });
      quick.append(b);
      quickButtons.push({ b, m });
    }

    const minuteSlider = slider({
      label: 'Duration',
      min: 1,
      max: 180,
      step: 1,
      get: () => minutes,
      set: (v) => {
        minutes = Math.round(v);
        sync();
      },
      format: () => `${minutes} min`,
    });

    const fadeSlider = slider({
      label: 'Fade-out',
      min: 0,
      max: 60,
      step: 1,
      get: () => fade,
      set: (v) => {
        fade = Math.round(v);
        sync();
      },
      format: () => (fade < 1 ? 'none' : `${fade} s`),
    });

    const startBtn = h('button', {
      type: 'button',
      class: 'btn btn-primary btn-full',
      text: transport.timer.running ? 'Restart' : 'Start',
      onClick: () => {
        startTimer(minutes, fade);
        dismiss();
      },
    });

    const cancelBtn = h('button', {
      type: 'button',
      class: 'btn btn-ghost btn-full',
      text: 'Cancel timer',
      onClick: () => {
        cancelTimer();
        refreshAll();
        dismiss();
      },
    });

    function sync() {
      readout.textContent = transport.timer.running
        ? `${formatDuration(transport.timer.remaining)} remaining`
        : 'The drone fades out and stops when the time is up.';
      for (const { b, m } of quickButtons) b.classList.toggle('is-on', m === minutes);
      minuteSlider.refresh();
      fadeSlider.refresh();
    }
    sync();

    body.append(readout, quick, minuteSlider.el, fadeSlider.el, startBtn);
    if (transport.timer.running) body.append(cancelBtn);
  });
}

// ---------------------------------------------------------------------------
// Layout and wiring
// ---------------------------------------------------------------------------

const TABS = [
  { id: 'player', label: 'Tanpura', icon: '♪' },
  { id: 'tuner', label: 'Tuner', icon: '◎' },
  { id: 'audio', label: 'Audio', icon: '≡' },
  { id: 'settings', label: 'Settings', icon: '⚙' },
];

function buildPanel() {
  const panel = document.querySelector('.panel');
  panel.textContent = '';
  const builders = { player: playerPanel, tuner: tunerPanel, audio: audioPanel, settings: settingsPanel };
  const cards = builders[activeTab]();
  for (const c of cards) panel.append(c.el);
  panelRefresh = () => cards.forEach((c) => c.refresh && c.refresh());
  panelRefresh();
  panel.scrollTop = 0;
}

function setTab(id) {
  activeTab = id;
  for (const btn of document.querySelectorAll('[data-tab]')) {
    const on = btn.dataset.tab === id;
    btn.classList.toggle('is-on', on);
    btn.setAttribute('aria-selected', on ? 'true' : 'false');
  }
  buildPanel();
}

function refreshAll() {
  refreshStage();
  panelRefresh();
  updateMediaSession();
}

function buildTabButtons(container, withIcons) {
  for (const tab of TABS) {
    const btn = h('button', {
      type: 'button',
      class: withIcons ? 'tab' : 'topbar-tab',
      role: 'tab',
      dataset: { tab: tab.id },
      onClick: () => setTab(tab.id),
    });
    if (withIcons) btn.append(h('span', { class: 'tab-icon', text: tab.icon }));
    btn.append(h('span', { text: tab.label }));
    container.append(btn);
  }
}

function bindKeyboard() {
  window.addEventListener('keydown', (e) => {
    if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
    switch (e.key) {
      case ' ':
        e.preventDefault();
        toggle();
        break;
      case 'ArrowLeft':
        update({ saMidi: Math.max(MIN_SA, settings.saMidi - 1) });
        break;
      case 'ArrowRight':
        update({ saMidi: Math.min(MAX_SA, settings.saMidi + 1) });
        break;
      case 'ArrowUp':
        e.preventDefault();
        update({ masterVolume: Math.min(1, settings.masterVolume + 0.05) });
        break;
      case 'ArrowDown':
        e.preventDefault();
        update({ masterVolume: Math.max(0, settings.masterVolume - 0.05) });
        break;
      default:
        break;
    }
  });
}

function init() {
  const app = document.querySelector('#app');
  const topTabs = h('nav', { class: 'topbar-tabs', role: 'tablist' });
  const topbar = h(
    'header',
    { class: 'topbar' },
    h('span', { class: 'brand', text: 'Tanpura' }),
    topTabs,
  );
  const panel = h('div', { class: 'panel', role: 'tabpanel' });
  const tabbar = h('nav', { class: 'tabbar', role: 'tablist' });

  app.append(topbar, buildStage(), panel, tabbar);
  buildTabButtons(topTabs, false);
  buildTabButtons(tabbar, true);

  setTab('player');
  refreshAll();
  bindKeyboard();

  // Re-assert the wake lock after the page comes back to the foreground: the
  // browser drops it whenever the document is hidden.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && wakeLock.wanted && !wakeLock.sentinel) {
      wakeLock.set(true);
    }
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js').catch(() => {
        // Offline support is a bonus, not a requirement.
      });
    });
  }
}

init();
