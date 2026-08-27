package com.riyaaz.tanpura.audio

import kotlin.math.sin

/**
 * A sustained reference tone for the tuner page. Not a pure sine: a handful of
 * decaying harmonics make it far easier to match by ear against an instrument.
 */
class RefTone(private val sampleRate: Int) {

    private companion object {
        val HARMONIC_LEVELS = floatArrayOf(1.0f, 0.34f, 0.16f, 0.07f, 0.03f)
    }

    private val phases = DoubleArray(HARMONIC_LEVELS.size)
    private var increment = 0.0
    private var env = 0f
    private var envTarget = 0f
    private val attackCoef = Dsp.smoothingCoef(0.05f, sampleRate)
    private val releaseCoef = Dsp.smoothingCoef(0.18f, sampleRate)

    val isSounding: Boolean get() = env > 1e-4f || envTarget > 0f

    fun setFrequency(freqHz: Float) {
        increment = 2.0 * Math.PI * freqHz.coerceIn(20f, sampleRate / 3f) / sampleRate
    }

    fun noteOn() {
        envTarget = 1f
    }

    fun noteOff() {
        envTarget = 0f
    }

    fun reset() {
        env = 0f
        envTarget = 0f
        phases.fill(0.0)
    }

    /** Adds into [out]; returns without touching the buffer when fully silent. */
    fun render(out: FloatArray, offset: Int, frames: Int, level: Float) {
        if (!isSounding) return
        val coef = if (envTarget > env) attackCoef else releaseCoef
        for (i in 0 until frames) {
            env += (envTarget - env) * coef
            var s = 0f
            for (h in HARMONIC_LEVELS.indices) {
                phases[h] += increment * (h + 1)
                if (phases[h] > 2.0 * Math.PI) phases[h] -= 2.0 * Math.PI
                s += sin(phases[h]).toFloat() * HARMONIC_LEVELS[h]
            }
            out[offset + i] += s * env * level * 0.28f
        }
    }
}
