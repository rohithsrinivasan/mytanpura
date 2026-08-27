package com.riyaaz.tanpura.audio

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One tanpura string, modelled as an extended Karplus-Strong waveguide:
 *
 *   pluck -> delay line -> one-pole loop filter -> jawari waveshaper -> back into
 *   the delay line
 *
 * The three things that make this sound like a tanpura rather than a generic
 * plucked string are:
 *
 *  1. **Jawari.** On a real tanpura a cotton thread sits between the string and
 *     the curved bridge, so a loud string grazes the bridge and folds its own
 *     waveform. That is modelled as a soft, amplitude-dependent waveshaper
 *     inside the feedback loop: harmonics bloom on the attack and melt away as
 *     the string decays. The shaper is normalised so its small-signal gain is
 *     exactly 1, which guarantees the loop can never grow.
 *  2. **Tension modulation.** A loud string is stretched slightly further, so it
 *     starts a touch sharp and settles down. A tiny amplitude-driven change to
 *     the delay length reproduces that.
 *  3. **Pluck-position comb.** The excitation is comb filtered, which notches out
 *     the harmonics that a finger at that position cannot excite.
 *
 * Tuning is exact rather than approximate: the loop filter's phase delay at the
 * fundamental is computed in closed form and subtracted from the delay length,
 * so the rendered pitch lands within a fraction of a cent of the target.
 */
class StringVoice(private val sampleRate: Int) {

    companion object {
        private const val MIN_FREQ = 38f
        private const val SILENCE_THRESHOLD = 2e-5f
    }

    private val maxDelay: Int = ceil(sampleRate / MIN_FREQ).toInt() + 4
    private val buffer = FloatArray(maxDelay)
    private val scratch = FloatArray(maxDelay)
    private var writeIdx = 0

    private val noise = Noise(0x51F0_A731)
    private val loopFilter = OnePole(0.5f)
    private val exciteFilter = OnePole(0.5f)

    /** Target and current (smoothed) loop delay in samples. */
    private var delayTarget = 200f
    private var delay = 200f

    private var loopGain = 0.999f
    private var frequency = 220f

    // Character
    private var brightness = 0.5f
    private var decaySeconds = 8f
    private var jawariAmount = 0.55f
    private var pluckPosition = 0.22f
    private var tension = 0.5f

    // Running state
    private var envelope = 0f
    private var jawariDrive = 1f
    private var jawariInvDrive = 1f
    private var silentBlocks = 0

    private val gainSm = Smoothed(1f, Dsp.smoothingCoef(0.02f, sampleRate))

    var gain: Float
        get() = gainSm.target
        set(value) {
            gainSm.target = value
        }

    val isRinging: Boolean
        get() = silentBlocks < 64

    fun reset() {
        buffer.fill(0f)
        writeIdx = 0
        envelope = 0f
        silentBlocks = 1024
        loopFilter.reset()
        exciteFilter.reset()
        delay = delayTarget
    }

    /** Sets the pitch. Safe to call while the string is ringing (glides). */
    fun setFrequency(freqHz: Float, glide: Boolean = true) {
        frequency = Dsp.clamp(freqHz, MIN_FREQ + 1f, sampleRate / 4f)
        recomputeLoop()
        if (!glide) delay = delayTarget
    }

    /**
     * @param brightness 0 = dark and woolly, 1 = bright and metallic.
     * @param decaySeconds time for the fundamental to fall by 60 dB.
     * @param jawari 0 = plain plucked string, 1 = heavy bridge buzz.
     * @param pluckPosition 0..0.5, fraction of the string length.
     * @param tension 0..1, amount of amplitude-driven pitch bend on the attack.
     */
    fun setCharacter(
        brightness: Float,
        decaySeconds: Float,
        jawari: Float,
        pluckPosition: Float,
        tension: Float,
    ) {
        this.brightness = Dsp.clamp01(brightness)
        this.decaySeconds = Dsp.clamp(decaySeconds, 0.2f, 40f)
        this.jawariAmount = Dsp.clamp01(jawari)
        this.pluckPosition = Dsp.clamp(pluckPosition, 0.03f, 0.5f)
        this.tension = Dsp.clamp01(tension)

        // Loop filter cutoff. Higher coefficient = more high frequency retained.
        // Scaled with frequency so that high and low strings keep a similar tone.
        val base = Dsp.taper(this.brightness, 0.12f, 0.92f, 0.7f)
        loopFilter.setCoef(base)
        exciteFilter.setCoef(Dsp.taper(this.brightness, 0.10f, 0.85f, 0.8f))
        recomputeLoop()
    }

    /**
     * Recomputes delay length and loop gain from frequency, brightness and decay.
     *
     * The loop is `z^-D * H(z)` where H is the one-pole lowpass. For the string to
     * sound at f0 the total loop delay must be fs/f0, so D = fs/f0 - phaseDelay(H).
     */
    private fun recomputeLoop() {
        val period = sampleRate / frequency
        val w = (2.0 * Math.PI * frequency / sampleRate)
        val a = loopFilter.coef()
        val p = (1f - a).toDouble() // pole radius of y += a*(x-y)

        // Phase delay of the one-pole at the fundamental, in samples.
        val denomRe = 1.0 - p * cos(w)
        val numIm = p * sin(w)
        val phaseDelay = if (w > 1e-9) atan(numIm / denomRe) / w else p / (1.0 - p)

        // Magnitude response at the fundamental.
        val mag = ((1.0 - p) / sqrt(denomRe * denomRe + numIm * numIm)).toFloat()

        delayTarget = Dsp.clamp((period - phaseDelay).toFloat(), 2.5f, maxDelay - 3f)

        // Per-period round-trip attenuation needed for the requested T60.
        val periodsInT60 = (frequency * decaySeconds).toDouble().coerceAtLeast(1.0)
        val wanted = 0.001.pow(1.0 / periodsInT60).toFloat()
        loopGain = Dsp.clamp(wanted / mag, 0.5f, 0.99995f)
    }

    /** Excites the string. [velocity] is 0..1 and maps to peak string amplitude. */
    fun pluck(velocity: Float) {
        val v = Dsp.clamp(velocity, 0f, 1.5f)
        if (v <= 0f) return

        val n = floor(delayTarget).toInt().coerceIn(4, maxDelay - 1)
        val combOffset = (pluckPosition * n).toInt().coerceIn(1, n - 1)

        // One period of lowpassed noise: the transverse displacement of the
        // string right after the finger releases it.
        for (i in 0 until n) {
            scratch[i] = exciteFilter.process(noise.nextBipolar())
        }
        // Comb filter for pluck position, plus a short raised-cosine fade at both
        // ends so re-plucking a ringing string does not produce a step.
        var sum = 0f
        val fade = (n / 12).coerceAtLeast(2)
        for (i in 0 until n) {
            val combed = scratch[i] - (if (i >= combOffset) scratch[i - combOffset] else 0f)
            val w = when {
                i < fade -> 0.5f - 0.5f * cos(Math.PI * i / fade).toFloat()
                i >= n - fade -> 0.5f - 0.5f * cos(Math.PI * (n - 1 - i) / fade).toFloat()
                else -> 1f
            }
            val s = combed * w
            scratch[i] = s
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms < 1e-9f) return
        val scale = v * 0.30f / rms

        var idx = writeIdx - n
        while (idx < 0) idx += maxDelay
        for (i in 0 until n) {
            buffer[idx] += scratch[i] * scale
            idx++
            if (idx >= maxDelay) idx = 0
        }
        silentBlocks = 0
    }

    /**
     * Renders [frames] samples and *adds* them into [out] starting at [offset].
     * Call with `frames <= Dsp.CONTROL_BLOCK`; control-rate updates happen once
     * per call.
     */
    fun render(out: FloatArray, offset: Int, frames: Int) {
        // ---- control rate ----
        delay += (delayTarget - delay) * 0.02f

        // Amplitude-driven bridge behaviour, updated once per block.
        val drive = 1f + jawariAmount * 26f * envelope
        jawariDrive = drive
        jawariInvDrive = 1f / drive

        // Tension modulation: a hotter string reads a marginally shorter delay.
        val effectiveDelay = delay * (1f - tension * 0.0025f * envelope)
        val readDelay = Dsp.clamp(effectiveDelay, 2.5f, maxDelay - 3f)

        val jw = jawariAmount
        var env = envelope
        var peak = 0f

        val baseIdx = floor(readDelay).toInt()
        val frac = readDelay - baseIdx

        for (i in 0 until frames) {
            // buffer[writeIdx - k] is the sample delayed by k, so a delay of
            // (baseIdx + frac) interpolates between delay baseIdx and baseIdx+1,
            // which is the index *below* r0. Reading the index above instead
            // would give a delay of (baseIdx - frac) and tune every string sharp.
            var r0 = writeIdx - baseIdx
            if (r0 < 0) r0 += maxDelay
            var r1 = r0 - 1
            if (r1 < 0) r1 += maxDelay

            val delayed = buffer[r0] * (1f - frac) + buffer[r1] * frac

            // Loop filter (string damping / bridge losses).
            var y = loopFilter.process(delayed)

            // Jawari: compressive odd-symmetric shaper. Small-signal gain is 1,
            // so |shaped| <= |y| and the feedback loop is unconditionally stable.
            if (jw > 0f) {
                val shaped = Dsp.fastTanh(y * jawariDrive) * jawariInvDrive
                y += jw * (shaped - y)
            }

            y *= loopGain

            if (y.isNaN() || y > 4f || y < -4f) y = 0f

            buffer[writeIdx] = y
            writeIdx++
            if (writeIdx >= maxDelay) writeIdx = 0

            val a = abs(y)
            if (a > peak) peak = a
            env += (a - env) * 0.0012f

            out[offset + i] += y * gainSm.step()
        }

        envelope = env
        if (peak < SILENCE_THRESHOLD) {
            if (silentBlocks < Int.MAX_VALUE - 1) silentBlocks++
        } else {
            silentBlocks = 0
        }
    }
}
