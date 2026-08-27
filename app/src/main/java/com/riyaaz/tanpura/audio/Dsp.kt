package com.riyaaz.tanpura.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Small DSP helpers shared by the tanpura voices.
 *
 * Everything in this file is deliberately free of Android imports so the engine
 * can be exercised from plain JVM unit tests.
 */
object Dsp {

    /** Samples per control block. Parameter smoothing happens at this rate. */
    const val CONTROL_BLOCK = 64

    /**
     * Pade [7/6] approximation of tanh: accurate to better than 1e-3 over the
     * whole usable range, odd-symmetric, monotonic, and strictly bounded by 1.
     *
     * That last property is load-bearing. This is the jawari waveshaper and it
     * sits *inside* the string's feedback loop, so any overshoot past unity gain
     * would make the string self-oscillate.
     */
    fun fastTanh(x: Float): Float {
        if (x > 4.5f) return 1f
        if (x < -4.5f) return -1f
        val x2 = x * x
        val num = x * (10395f + x2 * (1260f + x2 * 21f))
        val den = 10395f + x2 * (4725f + x2 * (210f + x2))
        return num / den
    }

    fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Linear interpolation between two dB-ish gain endpoints on a curved taper. */
    fun taper(t: Float, lo: Float, hi: Float, curve: Float = 1f): Float {
        val x = clamp01(t)
        val shaped = if (curve == 1f) x else Math.pow(x.toDouble(), curve.toDouble()).toFloat()
        return lo + (hi - lo) * shaped
    }

    /** One-pole smoothing coefficient for a given time constant. */
    fun smoothingCoef(timeSeconds: Float, sampleRate: Int, blockSize: Int = 1): Float {
        if (timeSeconds <= 0f) return 1f
        val dt = blockSize.toFloat() / sampleRate
        return 1f - exp(-dt / timeSeconds)
    }

    fun isBad(v: Float): Boolean = v.isNaN() || v.isInfinite() || abs(v) > 1000f
}

/** A parameter that ramps towards its target instead of jumping (no zipper noise). */
class Smoothed(initial: Float, private val coef: Float) {
    var target: Float = initial
    var value: Float = initial
        private set

    fun step(): Float {
        value += (target - value) * coef
        return value
    }

    fun snap(v: Float) {
        target = v
        value = v
    }
}

/** Deterministic, allocation-free noise source (xorshift32). */
class Noise(seed: Int = 0x1234_5678) {
    private var state: Int = if (seed == 0) 1 else seed

    fun nextBits(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }

    /** Uniform in [-1, 1). */
    fun nextBipolar(): Float = nextBits() * (1f / Int.MAX_VALUE.toFloat())

    /** Uniform in [0, 1). */
    fun nextUnit(): Float = (nextBits() ushr 8) * (1f / (1 shl 24).toFloat())
}

/** One-pole lowpass, y[n] = y[n-1] + a * (x[n] - y[n-1]). */
class OnePole(private var a: Float = 0.5f) {
    private var y = 0f

    fun setCoef(coef: Float) {
        a = Dsp.clamp(coef, 0.0001f, 1f)
    }

    fun coef(): Float = a

    fun process(x: Float): Float {
        y += a * (x - y)
        return y
    }

    fun reset() {
        y = 0f
    }
}

/**
 * Direct-form-1 biquad. Coefficients are computed with the standard Audio EQ
 * Cookbook formulas.
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun setPeaking(freq: Float, q: Float, gainDb: Float, sampleRate: Int) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = (2.0 * Math.PI * freq / sampleRate).toFloat()
        val cosW = Math.cos(w0.toDouble()).toFloat()
        val alpha = (Math.sin(w0.toDouble()) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha / a) / a0
    }

    fun setHighPass(freq: Float, q: Float, sampleRate: Int) {
        val w0 = (2.0 * Math.PI * freq / sampleRate).toFloat()
        val cosW = Math.cos(w0.toDouble()).toFloat()
        val alpha = (Math.sin(w0.toDouble()) / (2.0 * q)).toFloat()
        val a0 = 1f + alpha
        b0 = ((1f + cosW) / 2f) / a0
        b1 = (-(1f + cosW)) / a0
        b2 = ((1f + cosW) / 2f) / a0
        a1 = (-2f * cosW) / a0
        a2 = (1f - alpha) / a0
    }

    fun setLowShelf(freq: Float, gainDb: Float, sampleRate: Int) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = (2.0 * Math.PI * freq / sampleRate).toFloat()
        val cosW = Math.cos(w0.toDouble()).toFloat()
        val alpha = (Math.sin(w0.toDouble()) / 2.0 * Math.sqrt((a + 1f / a) * 1.4f + 2.0)).toFloat()
        val ap1 = a + 1f
        val am1 = a - 1f
        val twoSqrtAAlpha = 2f * Math.sqrt(a.toDouble()).toFloat() * alpha
        val a0 = ap1 + am1 * cosW + twoSqrtAAlpha
        b0 = (a * (ap1 - am1 * cosW + twoSqrtAAlpha)) / a0
        b1 = (2f * a * (am1 - ap1 * cosW)) / a0
        b2 = (a * (ap1 - am1 * cosW - twoSqrtAAlpha)) / a0
        a1 = (-2f * (am1 + ap1 * cosW)) / a0
        a2 = (ap1 + am1 * cosW - twoSqrtAAlpha) / a0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}
