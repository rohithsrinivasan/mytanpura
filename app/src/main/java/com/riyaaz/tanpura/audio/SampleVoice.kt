package com.riyaaz.tanpura.audio

/** A decoded, mono, one-shot recording of a single plucked string. */
class SampleData(
    val frames: FloatArray,
    val sampleRate: Int,
    val baseFrequencyHz: Float,
    val name: String = "",
) {
    val durationSeconds: Float get() = frames.size.toFloat() / sampleRate
}

/**
 * Plays back an imported string recording, resampled to whatever Sa the user
 * picked.
 *
 * Two independent players run round-robin so that re-striking a string that is
 * still ringing crossfades instead of cutting, which is what actually happens on
 * the instrument.
 */
class SampleVoice(private val engineRate: Int) {

    private companion object {
        const val PLAYERS = 3
        const val RELEASE_SECONDS = 0.45f
    }

    private class Player {
        var pos = 0.0
        var rate = 1.0
        var gain = 0f
        var releaseStep = 0f
        var releasing = false
        var active = false
    }

    private val players = Array(PLAYERS) { Player() }
    private var next = 0

    var data: SampleData? = null
        set(value) {
            field = value
            silence()
            recomputeRate()
        }

    private var targetFrequency = 220f
    private var rate = 1.0

    private val gainSm = Smoothed(1f, Dsp.smoothingCoef(0.02f, engineRate))

    var gain: Float
        get() = gainSm.target
        set(value) {
            gainSm.target = value
        }

    val isRinging: Boolean
        get() = players.any { it.active }

    fun silence() {
        players.forEach { it.active = false; it.gain = 0f; it.releasing = false }
        next = 0
    }

    fun setFrequency(freqHz: Float) {
        targetFrequency = freqHz.coerceAtLeast(1f)
        recomputeRate()
    }

    private fun recomputeRate() {
        val d = data ?: return
        val pitchRatio = targetFrequency / d.baseFrequencyHz.coerceAtLeast(1f)
        rate = (pitchRatio * d.sampleRate / engineRate).toDouble().coerceIn(0.05, 8.0)
    }

    fun pluck(velocity: Float) {
        if (data == null) return
        // Fade whatever is currently sounding on this string, then start fresh.
        for (p in players) {
            if (p.active && !p.releasing) {
                p.releasing = true
                p.releaseStep = 1f / (RELEASE_SECONDS * engineRate)
            }
        }
        val p = players[next]
        next = (next + 1) % PLAYERS
        p.pos = 0.0
        p.rate = rate
        p.gain = velocity.coerceIn(0f, 1.5f)
        p.releasing = false
        p.releaseStep = 0f
        p.active = true
    }

    /** Adds [frames] samples into [out] starting at [offset]. */
    fun render(out: FloatArray, offset: Int, frames: Int) {
        val d = data ?: run {
            // Still advance the gain ramp so it stays in step with the engine.
            repeat(frames) { gainSm.step() }
            return
        }
        val buf = d.frames
        val last = buf.size - 2
        if (last < 1) return

        for (i in 0 until frames) {
            var sum = 0f
            for (p in players) {
                if (!p.active) continue
                val idx = p.pos.toInt()
                if (idx >= last) {
                    p.active = false
                    continue
                }
                val frac = (p.pos - idx).toFloat()
                sum += (buf[idx] * (1f - frac) + buf[idx + 1] * frac) * p.gain
                p.pos += p.rate
                if (p.releasing) {
                    p.gain -= p.releaseStep
                    if (p.gain <= 0f) {
                        p.gain = 0f
                        p.active = false
                    }
                }
            }
            out[offset + i] += sum * gainSm.step()
        }
    }
}
