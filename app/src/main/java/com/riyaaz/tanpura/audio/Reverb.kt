package com.riyaaz.tanpura.audio

/**
 * Freeverb-style reverberator: a bank of damped comb filters into a chain of
 * allpass diffusers, mono in / stereo out with a channel offset for width.
 *
 * A tanpura is almost always heard in a room, and the tail is what glues the
 * overlapping string decays into one continuous drone, so this is not optional
 * decoration.
 */
class Reverb(sampleRate: Int) {

    private companion object {
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)
        const val STEREO_SPREAD = 23
        const val REFERENCE_RATE = 44100f
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(4))
        private var idx = 0
        private var store = 0f
        var feedback = 0.84f
        var damp = 0.2f

        fun clear() {
            buf.fill(0f); store = 0f
        }

        fun process(input: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = input + store * feedback
            idx++
            if (idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size.coerceAtLeast(4))
        private var idx = 0
        val feedback = 0.5f

        fun clear() = buf.fill(0f)

        fun process(input: Float): Float {
            val bufOut = buf[idx]
            buf[idx] = input + bufOut * feedback
            idx++
            if (idx >= buf.size) idx = 0
            return -input + bufOut
        }
    }

    private val scale = sampleRate / REFERENCE_RATE

    private val combsL = Array(COMB_TUNING.size) { Comb((COMB_TUNING[it] * scale).toInt()) }
    private val combsR = Array(COMB_TUNING.size) { Comb(((COMB_TUNING[it] + STEREO_SPREAD) * scale).toInt()) }
    private val apL = Array(ALLPASS_TUNING.size) { Allpass((ALLPASS_TUNING[it] * scale).toInt()) }
    private val apR = Array(ALLPASS_TUNING.size) { Allpass(((ALLPASS_TUNING[it] + STEREO_SPREAD) * scale).toInt()) }

    private var wet = 0.2f
    private var width = 0.85f

    init {
        setRoom(0.86f, 0.28f)
    }

    fun clear() {
        combsL.forEach { it.clear() }
        combsR.forEach { it.clear() }
        apL.forEach { it.clear() }
        apR.forEach { it.clear() }
    }

    /**
     * @param size 0..1, apparent room size (feedback).
     * @param damping 0..1, how quickly high frequencies die in the tail.
     */
    fun setRoom(size: Float, damping: Float) {
        val fb = 0.70f + 0.28f * Dsp.clamp01(size)
        val dmp = 0.4f * Dsp.clamp01(damping)
        for (c in combsL) { c.feedback = fb; c.damp = dmp }
        for (c in combsR) { c.feedback = fb; c.damp = dmp }
    }

    /** @param mix 0 = fully dry, 1 = fully wet. */
    fun setMix(mix: Float) {
        wet = Dsp.clamp01(mix)
    }

    fun mix(): Float = wet

    /**
     * Reads [frames] mono samples from [mono] and writes the dry+wet result into
     * the interleaved stereo buffer [out] at frame [outFrame].
     */
    fun process(mono: FloatArray, frames: Int, out: FloatArray, outFrame: Int) {
        val w = wet
        val dry = 1f - w * 0.65f
        val wet1 = w * (width / 2f + 0.5f)
        val wet2 = w * ((1f - width) / 2f)

        for (i in 0 until frames) {
            val input = mono[i] * 0.22f
            var l = 0f
            var r = 0f
            for (c in combsL) l += c.process(input)
            for (c in combsR) r += c.process(input)
            for (a in apL) l = a.process(l)
            for (a in apR) r = a.process(r)

            val o = outFrame + i * 2
            out[o] = mono[i] * dry + l * wet1 + r * wet2
            out[o + 1] = mono[i] * dry + r * wet1 + l * wet2
        }
    }
}
