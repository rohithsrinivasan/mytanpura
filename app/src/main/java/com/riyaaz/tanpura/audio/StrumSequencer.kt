package com.riyaaz.tanpura.audio

/**
 * Drives the repeating right-hand strum: string 1, 2, 3, 4, pause, repeat.
 *
 * A metronomic strum sounds like a machine, so each cycle gets fresh timing and
 * velocity jitter. The amount is controllable because a completely steady strum
 * is sometimes what you want for tuning.
 */
class StrumSequencer(private val sampleRate: Int) {

    companion object {
        const val MAX_STRINGS = 5

        /** Fraction of the cycle occupied by the strum itself; the rest is the rest. */
        const val STRUM_SPREAD = 0.72f
    }

    private val noise = Noise(0x2B7E_1516)

    private val triggerTimes = FloatArray(MAX_STRINGS)
    private val triggerVels = FloatArray(MAX_STRINGS)

    private var phase = 0f
    private var nextIndex = 0
    private var cycleLen = 1f

    var stringCount: Int = 4
        set(value) {
            field = value.coerceIn(1, MAX_STRINGS)
        }

    /** Seconds for one full strum cycle including the rest. */
    var cycleSeconds: Float = 3.2f
        set(value) {
            field = Dsp.clamp(value, 0.35f, 20f)
        }

    /** 0 = machine-perfect, 1 = loose human hand. */
    var humanize: Float = 0.35f
        set(value) {
            field = Dsp.clamp01(value)
        }

    /** Per-string pluck strength before humanisation. */
    val baseVelocities = FloatArray(MAX_STRINGS) { 0.8f }

    /** Called by the engine to know which string was struck most recently (for the UI). */
    var lastStruckString: Int = -1
        private set

    fun reset(startImmediately: Boolean = true) {
        phase = 0f
        lastStruckString = -1
        beginCycle()
        if (!startImmediately) {
            // Skip past the first trigger so nothing fires until the next cycle.
            nextIndex = stringCount
        }
    }

    private fun beginCycle() {
        cycleLen = (cycleSeconds * sampleRate).coerceAtLeast(64f)
        val count = stringCount
        val span = cycleLen * STRUM_SPREAD
        val interval = if (count > 1) span / (count - 1) else span
        val timeJitter = humanize * 0.10f * interval
        for (i in 0 until count) {
            val t = i * interval + noise.nextBipolar() * timeJitter
            triggerTimes[i] = t.coerceIn(0f, cycleLen - 1f)
            val velJitter = 1f + noise.nextBipolar() * humanize * 0.16f
            triggerVels[i] = Dsp.clamp(baseVelocities[i] * velJitter, 0.02f, 1.4f)
        }
        // Timing jitter can reorder adjacent strings; keep the sweep monotonic.
        for (i in 1 until count) {
            if (triggerTimes[i] < triggerTimes[i - 1]) triggerTimes[i] = triggerTimes[i - 1]
        }
        nextIndex = 0
    }

    /**
     * Advances the sequencer by [frames] samples, invoking [onPluck] for every
     * string that should be struck during that span. Called once per control
     * block, so the lambda cost is irrelevant.
     */
    fun advance(frames: Int, onPluck: (stringIndex: Int, velocity: Float) -> Unit) {
        phase += frames
        var guard = 0
        while (guard++ < 8) {
            while (nextIndex < stringCount && triggerTimes[nextIndex] <= phase) {
                val idx = nextIndex
                nextIndex++
                lastStruckString = idx
                onPluck(idx, triggerVels[idx])
            }
            if (phase < cycleLen) break
            phase -= cycleLen
            beginCycle()
        }
    }

    /** 0..1 position within the current strum cycle, for the UI. */
    fun cyclePosition(): Float = if (cycleLen > 0f) phase / cycleLen else 0f
}
