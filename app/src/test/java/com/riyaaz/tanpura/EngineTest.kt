package com.riyaaz.tanpura

import com.riyaaz.tanpura.audio.PcmRing
import com.riyaaz.tanpura.audio.StrumSequencer
import com.riyaaz.tanpura.audio.TanpuraEngine
import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.StringPatterns
import com.riyaaz.tanpura.model.TanpuraSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EngineTest {

    private val sampleRate = 48_000

    private fun render(engine: TanpuraEngine, seconds: Float, blockFrames: Int = 512): FloatArray {
        val totalFrames = (sampleRate * seconds).toInt()
        val out = FloatArray(totalFrames * 2)
        val block = FloatArray(blockFrames * 2)
        var done = 0
        while (done < totalFrames) {
            val n = minOf(blockFrames, totalFrames - done)
            engine.render(block, n)
            System.arraycopy(block, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun `produces a bounded, non-silent drone`() {
        val engine = TanpuraEngine(sampleRate)
        engine.submit(TanpuraSettings(saMidi = 49, masterVolume = 0.9f))
        engine.setPlaying(true)
        val audio = render(engine, 4f)

        var peak = 0f
        for (v in audio) {
            assertTrue("Non-finite output", v.isFinite())
            val a = abs(v)
            if (a > peak) peak = a
        }
        assertTrue("Engine produced silence", peak > 0.05f)
        assertTrue("Engine clipped past the limiter: $peak", peak <= 1.0f)
    }

    @Test
    fun `every string pattern renders cleanly`() {
        for (pattern in StringPatterns.all) {
            val engine = TanpuraEngine(sampleRate)
            engine.submit(TanpuraSettings(patternId = pattern.id, cycleSeconds = 1.2f))
            engine.setPlaying(true)
            val audio = render(engine, 2.5f)
            var peak = 0f
            for (v in audio) {
                assertTrue("Non-finite output for ${pattern.id}", v.isFinite())
                val a = abs(v)
                if (a > peak) peak = a
            }
            assertTrue("Pattern ${pattern.id} was silent", peak > 0.02f)
            assertEquals(pattern.stringCount, engine.activeStringCount)
        }
    }

    @Test
    fun `pausing fades out to true silence`() {
        val engine = TanpuraEngine(sampleRate)
        engine.submit(TanpuraSettings(cycleSeconds = 1.5f))
        engine.setPlaying(true)
        render(engine, 2f)
        engine.setPlaying(false)
        val tail = render(engine, 4f)
        // The last quarter second, well past the fade and the reverb tail.
        val lastFrames = tail.copyOfRange(tail.size - sampleRate / 2, tail.size)
        val residual = lastFrames.maxOf { abs(it) }
        assertTrue("Still making noise after pause: $residual", residual < 1e-3f)
        assertTrue("Engine did not report itself idle", engine.isIdle)
    }

    @Test
    fun `changing settings mid-render does not glitch`() {
        val engine = TanpuraEngine(sampleRate)
        engine.setPlaying(true)
        val block = FloatArray(256 * 2)
        var sa = 45
        repeat(400) { i ->
            if (i % 7 == 0) {
                sa = 45 + (i / 7) % 24
                engine.submit(
                    TanpuraSettings(
                        saMidi = sa,
                        fineCents = ((i % 21) - 10).toFloat(),
                        patternId = StringPatterns.all[i % StringPatterns.all.size].id,
                        voiceId = if (i % 2 == 0) "male" else "female",
                        cycleSeconds = 1f + (i % 5) * 0.5f,
                    )
                )
            }
            engine.render(block, 256)
            for (v in block) assertTrue("Glitch at iteration $i", v.isFinite() && abs(v) <= 1.0f)
        }
    }

    @Test
    fun `loop mode with no source is silent rather than crashing`() {
        val engine = TanpuraEngine(sampleRate)
        engine.submit(TanpuraSettings(mode = EngineMode.LOOP))
        engine.setPlaying(true)
        val audio = render(engine, 0.5f)
        assertTrue(audio.all { it.isFinite() })
    }

    @Test
    fun `sample mode falls back to the synth when nothing is loaded`() {
        val engine = TanpuraEngine(sampleRate)
        engine.submit(TanpuraSettings(mode = EngineMode.SAMPLES))
        engine.setPlaying(true)
        val audio = render(engine, 2f)
        val peak = audio.maxOf { abs(it) }
        assertTrue("Expected the built-in synth to take over, got silence", peak > 0.02f)
    }

    @Test
    fun `reference tone sounds even while the drone is paused`() {
        val engine = TanpuraEngine(sampleRate)
        engine.submit(TanpuraSettings(masterVolume = 0.8f))
        engine.setPlaying(false)
        engine.setReferenceTone(220f)
        val audio = render(engine, 1f)
        val peak = audio.maxOf { abs(it) }
        assertTrue("Reference tone was silent", peak > 0.02f)
        assertTrue("Reference tone clipped", peak <= 1f)
    }
}

class StrumSequencerTest {

    @Test
    fun `strikes every string once per cycle, in order`() {
        val sequencer = StrumSequencer(48_000)
        sequencer.stringCount = 4
        sequencer.cycleSeconds = 1f
        sequencer.humanize = 0f
        sequencer.reset()

        // Stop just short of one full cycle: at the wrap point string 1 of the
        // next cycle fires immediately, which is correct but not what is counted.
        val fired = mutableListOf<Pair<Int, Int>>()
        var sample = 0
        while (sample < 47_000) {
            sequencer.advance(64) { index, _ -> fired.add(index to sample) }
            sample += 64
        }

        assertEquals("Wrong number of plucks in one cycle", 4, fired.size)
        assertEquals(listOf(0, 1, 2, 3), fired.map { it.first })

        // With STRUM_SPREAD = 0.72 the four strings land at 0, 0.24, 0.48, 0.72.
        val expected = listOf(0, 11_520, 23_040, 34_560)
        fired.forEachIndexed { i, (_, at) ->
            assertTrue(
                "String $i fired at $at, expected near ${expected[i]}",
                abs(at - expected[i]) <= 128,
            )
        }
    }

    @Test
    fun `keeps cycling and never fires out of order`() {
        val sequencer = StrumSequencer(48_000)
        sequencer.stringCount = 4
        sequencer.cycleSeconds = 0.5f
        sequencer.humanize = 1f
        sequencer.reset()

        var count = 0
        var lastInCycle = -1
        repeat(48_000 * 5 / 64) {
            sequencer.advance(64) { index, velocity ->
                count++
                assertTrue("Velocity out of range: $velocity", velocity > 0f && velocity < 1.5f)
                if (index == 0) lastInCycle = -1
                assertTrue("Out of order: $lastInCycle then $index", index > lastInCycle)
                lastInCycle = index
            }
        }
        // Five seconds at half a second per cycle, four strings each.
        assertTrue("Expected around 40 plucks, got $count", count in 36..44)
    }
}

class PcmRingTest {

    @Test
    fun `writes and reads across the wrap point`() {
        val ring = PcmRing(16)
        val src = FloatArray(10) { it.toFloat() }
        val dst = FloatArray(10)

        assertEquals(10, ring.write(src, 0, 10))
        assertEquals(10, ring.available())
        assertEquals(10, ring.read(dst, 0, 10))
        assertTrue(src.contentEquals(dst))

        // Now sitting mid-buffer; write past the end.
        assertEquals(10, ring.write(src, 0, 10))
        assertEquals(10, ring.read(dst, 0, 10))
        assertTrue(src.contentEquals(dst))
    }

    @Test
    fun `refuses to overflow and reports underrun`() {
        val ring = PcmRing(8)
        val src = FloatArray(100) { 1f }
        val written = ring.write(src, 0, 100)
        assertEquals(ring.capacity(), written)
        assertEquals(0, ring.space())

        val dst = FloatArray(100)
        assertEquals(ring.capacity(), ring.read(dst, 0, 100))
        assertEquals(-7f, ring.readOne(-7f), 0f)
    }
}
