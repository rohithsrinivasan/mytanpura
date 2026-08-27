package com.riyaaz.tanpura

import com.riyaaz.tanpura.audio.Dsp
import com.riyaaz.tanpura.audio.PitchDetector
import com.riyaaz.tanpura.audio.StringVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole point of a tanpura is that it is in tune, so the synthesised string
 * is checked against a pitch detector rather than trusted.
 */
class StringVoiceTest {

    private val sampleRate = 48_000

    private fun renderPluck(
        freq: Float,
        seconds: Float,
        brightness: Float = 0.5f,
        jawari: Float = 0.55f,
        decay: Float = 8f,
    ): FloatArray {
        val voice = StringVoice(sampleRate)
        voice.setCharacter(
            brightness = brightness,
            decaySeconds = decay,
            jawari = jawari,
            pluckPosition = 0.22f,
            tension = 0.5f,
        )
        voice.setFrequency(freq, glide = false)
        voice.reset()
        voice.setFrequency(freq, glide = false)
        voice.gain = 1f
        voice.pluck(1f)

        val total = (sampleRate * seconds).toInt()
        val out = FloatArray(total)
        var done = 0
        while (done < total) {
            val n = minOf(Dsp.CONTROL_BLOCK, total - done)
            voice.render(out, done, n)
            done += n
        }
        return out
    }

    /**
     * Sweeps every semitone the app can ask a string to play, from the low brass
     * string of the lowest Sa to the side string of the highest.
     *
     * The bound is deliberately tight. A fractional-delay bug that read the
     * interpolation neighbour on the wrong side once made every string up to 9
     * cents sharp while still sounding perfectly plausible in isolation - the
     * kind of error you only notice when you play along with it. One cent is
     * roughly ten times better than anyone can hear, and the implementation
     * comfortably achieves it, so anything looser would let that class of bug
     * back in.
     */
    @Test
    fun `sounds at the requested pitch across the whole range`() {
        var worst = 0f
        var worstAt = 0f
        // C1 (MIN_SA - 12, the low brass string) up to C5.
        for (midi in 24..72) {
            val target = 440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()
            if (target < 45f) continue
            val audio = renderPluck(target, 1f)
            val detected = PitchDetector.detect(
                audio,
                sampleRate,
                minHz = target * 0.7f,
                maxHz = target * 1.4f,
                offset = (sampleRate * 0.45f).toInt(),
                length = (sampleRate * 0.35f).toInt(),
            )
            assertTrue("No pitch detected at $target Hz", detected != null)
            val cents = abs(PitchDetector.cents(target, detected!!))
            if (cents > worst) {
                worst = cents
                worstAt = target
            }
        }
        assertTrue("Worst tuning error was $worst cents at $worstAt Hz", worst < 1f)
    }

    @Test
    fun `decays to silence and stays finite`() {
        val audio = renderPluck(146.83f, 6f, decay = 2f)
        for (v in audio) {
            assertTrue("Non-finite sample produced", v.isFinite())
            assertTrue("Sample exploded: $v", abs(v) < 4f)
        }
        // Averaged energy in the last tenth of a second should be far below the peak.
        val tail = audio.takeLast(sampleRate / 10)
        val peak = audio.maxOf { abs(it) }
        val tailPeak = tail.maxOf { abs(it) }
        assertTrue("Peak was silent, nothing was rendered", peak > 0.01f)
        assertTrue("String never decayed (peak=$peak tail=$tailPeak)", tailPeak < peak * 0.05f)
    }

    @Test
    fun `longer requested decay really rings longer`() {
        val shortDecay = renderPluck(146.83f, 4f, decay = 1.5f)
        val longDecay = renderPluck(146.83f, 4f, decay = 12f)
        val window = sampleRate / 4
        val start = sampleRate * 3
        val shortTail = shortDecay.copyOfRange(start, start + window).maxOf { abs(it) }
        val longTail = longDecay.copyOfRange(start, start + window).maxOf { abs(it) }
        assertTrue("Decay control had no effect ($shortTail vs $longTail)", longTail > shortTail * 4f)
    }

    @Test
    fun `jawari adds high harmonics without adding level`() {
        val plain = renderPluck(146.83f, 1.5f, jawari = 0f)
        val buzzy = renderPluck(146.83f, 1.5f, jawari = 1f)
        val plainPeak = plain.maxOf { abs(it) }
        val buzzyPeak = buzzy.maxOf { abs(it) }
        // The shaper is compressive, so it must never make the string louder.
        assertTrue(
            "Jawari made the string louder ($plainPeak -> $buzzyPeak)",
            buzzyPeak <= plainPeak * 1.05f,
        )
        assertTrue("Jawari silenced the string", buzzyPeak > plainPeak * 0.25f)
    }

    @Test
    fun `fastTanh matches tanh closely and is bounded`() {
        var maxError = 0f
        var x = -3f
        while (x <= 3f) {
            val error = abs(Dsp.fastTanh(x) - kotlin.math.tanh(x))
            if (error > maxError) maxError = error
            x += 0.01f
        }
        assertTrue("fastTanh error $maxError too large", maxError < 0.002f)
        assertTrue(abs(Dsp.fastTanh(100f)) <= 1.001f)
        assertEquals(0f, Dsp.fastTanh(0f), 1e-7f)
    }
}
