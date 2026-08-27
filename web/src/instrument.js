// The animated tanpura drawing: pegbox and neck at the top, gourd at the bottom,
// strings running the full length. Each string flexes when it is struck and the
// strum marker sweeps across in time with the sequencer, so you can see the cycle
// you are hearing.
//
// Port of the Android Compose canvas, same proportions and palette.

const COLORS = {
  brass: '#e8b04b',
  brassDim: '#b8873a',
  wood: '#6b4a28',
  woodDark: '#3a2717',
  soundboard: '#2a1c0f',
  string: '#f6e3b8',
  muted: '#b4a48b',
};

/**
 * Owns a canvas, keeps it sharp on high-DPI screens, and redraws on every frame
 * while the drone is running.
 */
export class InstrumentView {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.phase = 0;
    this.lastTime = 0;
    this.running = false;
    this.frameHandle = 0;

    // Interpolated locally between meter messages, which arrive at ~20 Hz.
    this.flash = [0, 0, 0, 0, 0];
    this.level = 0;
    this.strum = 0;
    this.stringCount = 4;
    this.labels = ['Pa', 'Sa', 'Sa', 'Sa↓'];

    this.resize();
    this.observer = new ResizeObserver(() => {
      this.resize();
      if (!this.running) this.drawOnce();
    });
    this.observer.observe(canvas);
  }

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const rect = this.canvas.getBoundingClientRect();
    const w = Math.max(1, Math.round(rect.width));
    const h = Math.max(1, Math.round(rect.height));
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.cssWidth = w;
    this.cssHeight = h;
    this.dpr = dpr;
  }

  /** Applies a meter message from the audio thread. */
  setMeter({ level, strum, flash, stringCount }) {
    this.level = level;
    this.strum = strum;
    if (flash) this.flash = flash;
    if (stringCount) this.stringCount = stringCount;
  }

  setLabels(labels) {
    this.labels = labels;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.lastTime = 0;
    const tick = (now) => {
      if (!this.running) return;
      const dt = this.lastTime ? (now - this.lastTime) / 1000 : 0;
      this.lastTime = now;
      this.phase = (this.phase + dt * 9) % 1;
      // Decay the flash locally so strings settle smoothly between meters.
      for (let i = 0; i < this.flash.length; i++) this.flash[i] *= Math.pow(0.2, dt);
      this.draw();
      this.frameHandle = requestAnimationFrame(tick);
    };
    this.frameHandle = requestAnimationFrame(tick);
  }

  stop() {
    this.running = false;
    if (this.frameHandle) cancelAnimationFrame(this.frameHandle);
    this.frameHandle = 0;
    this.flash = this.flash.map(() => 0);
    this.level = 0;
    this.drawOnce();
  }

  drawOnce() {
    this.draw();
  }

  /**
   * The instrument is drawn in a fixed 100 x 160 virtual space and then scaled to
   * fit, letterboxed and centred.
   *
   * A tanpura is a tall, slim instrument. Deriving the geometry from the canvas's
   * own width and height instead makes it stretch with the container: on a wide,
   * short canvas the gourd flattens into a disc and the neck turns into a stump.
   * A fixed aspect ratio keeps the proportions right at every size.
   */
  draw() {
    const ctx = this.ctx;
    const w = this.cssWidth;
    const h = this.cssHeight;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const VW = 100;
    const VH = 160;
    const scale = Math.min(w / VW, h / VH);
    const offX = (w - VW * scale) / 2;
    const offY = (h - VH * scale) / 2;
    const X = (vx) => offX + vx * scale;
    const Y = (vy) => offY + vy * scale;
    const S = (v) => v * scale;

    const count = Math.min(5, Math.max(1, this.stringCount));
    const cx = 50;
    const stringTop = 25;
    const stringBottom = 138;
    const bridgeY = 112;
    const gourdCy = 120;
    const gourdR = 27;

    // Resonance glow, driven by output level.
    const glow = Math.min(1, Math.max(0, this.level));
    if (glow > 0.01) {
      const r = S(gourdR * 2.6);
      const grad = ctx.createRadialGradient(X(cx), Y(gourdCy), 0, X(cx), Y(gourdCy), r);
      grad.addColorStop(0, `rgba(232, 176, 75, ${0.2 * glow})`);
      grad.addColorStop(1, 'rgba(232, 176, 75, 0)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, w, h);
    }

    // Gourd (tumba).
    const gourdGrad = ctx.createLinearGradient(0, Y(gourdCy - gourdR), 0, Y(gourdCy + gourdR));
    gourdGrad.addColorStop(0, COLORS.wood);
    gourdGrad.addColorStop(1, COLORS.woodDark);
    ellipse(ctx, X(cx), Y(gourdCy), S(gourdR), S(gourdR), gourdGrad);

    // Neck (dandi), drawn after the gourd so it sits on top of the joint.
    const neckGrad = ctx.createLinearGradient(X(42), 0, X(58), 0);
    neckGrad.addColorStop(0, COLORS.woodDark);
    neckGrad.addColorStop(0.45, COLORS.wood);
    neckGrad.addColorStop(1, COLORS.woodDark);
    roundRect(ctx, X(42), Y(22), S(16), S(86), S(4), neckGrad);

    // Soundboard (tabli).
    ellipse(ctx, X(cx), Y(114), S(18), S(11.5), COLORS.soundboard);

    // Rim highlight.
    ctx.beginPath();
    ctx.ellipse(X(cx), Y(gourdCy), S(gourdR), S(gourdR), 0, 0, Math.PI * 2);
    ctx.strokeStyle = 'rgba(232, 176, 75, 0.25)';
    ctx.lineWidth = Math.max(1, S(0.5));
    ctx.stroke();

    // Pegbox.
    const pegGrad = ctx.createLinearGradient(0, Y(13), 0, Y(26));
    pegGrad.addColorStop(0, COLORS.wood);
    pegGrad.addColorStop(1, COLORS.woodDark);
    roundRect(ctx, X(33), Y(13), S(34), S(13), S(3), pegGrad);

    // Tuning pegs (khunti), alternating sides.
    for (let i = 0; i < count; i++) {
      const left = i % 2 === 0;
      const vy = 16 + 4.5 * Math.floor(i / 2);
      roundRect(ctx, X(left ? 22 : 66), Y(vy), S(12), S(3.2), S(1.6), COLORS.brassDim);
    }

    // Strings run over the neck, inside its width.
    const spread = 5.6;
    const step = count > 1 ? (spread * 2) / (count - 1) : 0;

    /*
     * Labels are spread in screen pixels, not virtual units. The instrument is
     * letterboxed to the canvas height, so on a wide canvas its virtual width
     * maps to a narrow strip - spacing the labels by string position there would
     * jam four of them into ~30px.
     */
    const instrumentPx = VW * scale;
    // Just wide enough to read: about 30px per label, never wider than the canvas.
    const labelSpan = Math.min(w - 16, Math.max(count * 30, 110));
    const labelX = (i) =>
      count > 1 ? offX + instrumentPx / 2 - labelSpan / 2 + (labelSpan / (count - 1)) * i : X(cx);

    ctx.font = `500 ${Math.max(11, Math.min(15, S(8)))}px system-ui, -apple-system, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';

    for (let i = 0; i < count; i++) {
      const vx = count > 1 ? cx - spread + step * i : cx;
      const act = Math.min(1, Math.max(0, this.flash[i] || 0));
      const amp = act * 2.6; // virtual units
      const isBrass = i === count - 1;

      ctx.beginPath();
      const segments = 32;
      for (let s = 0; s <= segments; s++) {
        const t = s / segments;
        const vy = stringTop + (stringBottom - stringTop) * t;
        // Fixed at both ends, maximum displacement near the middle.
        const envelope = Math.sin(Math.PI * t);
        const wiggle = Math.sin((t * 3 + this.phase) * 2 * Math.PI);
        const px = X(vx + amp * envelope * wiggle);
        if (s === 0) ctx.moveTo(px, Y(vy));
        else ctx.lineTo(px, Y(vy));
      }
      ctx.strokeStyle = withAlpha(isBrass ? COLORS.brass : COLORS.string, 0.5 + 0.5 * act);
      ctx.lineWidth = Math.max(1, S(isBrass ? 1.4 : 0.85));
      ctx.lineCap = 'round';
      ctx.stroke();

      // Swara label above the pegbox, clear of the instrument.
      const label = this.labels[i];
      if (label) {
        ctx.fillStyle = act > 0.15 ? COLORS.brass : COLORS.muted;
        ctx.fillText(label, labelX(i), Y(0));
      }
    }

    // Bridge (jawari), on top of the strings.
    roundRect(ctx, X(38), Y(bridgeY - 2), S(24), S(4), S(1.2), withAlpha(COLORS.string, 0.9));

    // Strum marker sweeping across the strings.
    if (this.level > 0.005) {
      const t = Math.min(1, Math.max(0, this.strum) / 0.72);
      ctx.beginPath();
      ctx.arc(X(cx - spread + t * spread * 2), Y(103), Math.max(2, S(1.6)), 0, Math.PI * 2);
      ctx.fillStyle = withAlpha(COLORS.brass, 0.85);
      ctx.fill();
    }
  }
}

function ellipse(ctx, cx, cy, rx, ry, fill) {
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.fillStyle = fill;
  ctx.fill();
}

function roundRect(ctx, x, y, w, h, r, fill) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
  ctx.fillStyle = fill;
  ctx.fill();
}

function withAlpha(hex, alpha) {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
