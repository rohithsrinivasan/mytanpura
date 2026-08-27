package com.riyaaz.tanpura

import com.riyaaz.tanpura.audio.PitchDetector
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.StringPatterns
import com.riyaaz.tanpura.model.Swara
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TanpuraVoices
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchTest {

    @Test
    fun `concert A is where it should be`() {
        assertEquals(440f, Pitch.frequency(69), 1e-3f)
        assertEquals(220f, Pitch.frequency(57), 1e-3f)
        assertEquals(261.6256f, Pitch.frequency(60), 1e-3f)
    }

    @Test
    fun `note names follow scientific pitch notation`() {
        assertEquals("C4", Pitch.noteName(60))
        assertEquals("A4", Pitch.noteName(69))
        assertEquals("C#3", Pitch.noteName(49))
        assertEquals("Db3", Pitch.noteName(49, useFlats = true))
    }

    @Test
    fun `cents offset shifts pitch by the right ratio`() {
        val base = Pitch.frequency(60)
        val up = Pitch.frequency(60, cents = 100f)
        assertEquals(Pitch.frequency(61), up, 1e-2f)
        assertEquals(100f, PitchDetector.cents(base, up), 0.01f)
    }

    @Test
    fun `a4 reference scales everything`() {
        assertEquals(442f, Pitch.frequency(69, a4Hz = 442f), 1e-3f)
        val ratio = Pitch.frequency(49, a4Hz = 442f) / Pitch.frequency(49, a4Hz = 440f)
        assertEquals(442f / 440f, ratio, 1e-5f)
    }

    @Test
    fun `string offsets are measured from the middle Sa`() {
        // With Sa = C4, the Pa side string is the G below it, not the G above.
        val sa = 60
        val paOffset = StringPatterns.PA.semitoneOffsets[0]
        assertEquals(-5, paOffset)
        assertEquals("G3", Pitch.noteName(sa + paOffset))
        assertEquals("F3", Pitch.noteName(sa + StringPatterns.MA.semitoneOffsets[0]))
        assertEquals("B3", Pitch.noteName(sa + StringPatterns.NI.semitoneOffsets[0]))
        assertEquals("A3", Pitch.noteName(sa + StringPatterns.DHA.semitoneOffsets[0]))
        assertEquals("C3", Pitch.noteName(sa + StringPatterns.PA.semitoneOffsets[3]))
    }

    @Test
    fun `komal swaras sit a semitone below their shuddh form`() {
        assertEquals(Swara.NI.semitone - 1, Swara.NI_KOMAL.semitone)
        assertEquals(Swara.DHA.semitone - 1, Swara.DHA_KOMAL.semitone)
        assertEquals(Swara.GA.semitone - 1, Swara.GA_KOMAL.semitone)
        assertEquals(7, Swara.PA.semitone)
        assertEquals(-5, Swara.PA.lowerOctaveOffset)
    }

    @Test
    fun `frequencyFromSemitone agrees with frequency`() {
        val direct = Pitch.frequency(49 - 5, 0f, 440f)
        val viaOffset = Pitch.frequencyFromSemitone(-5f, 49, 0f, 440f)
        assertEquals(direct, viaOffset, 1e-3f)
    }
}

class PatternTest {

    @Test
    fun `every pattern is internally consistent`() {
        for (pattern in StringPatterns.all) {
            assertEquals(
                "Pattern ${pattern.id} has mismatched labels and offsets",
                pattern.semitoneOffsets.size,
                pattern.swaraLabels.size,
            )
            assertTrue(
                "Pattern ${pattern.id} has too many strings",
                pattern.stringCount in 1..5,
            )
            assertEquals(
                "Pattern ${pattern.id} should end on the low Sa",
                -12,
                pattern.semitoneOffsets.last(),
            )
            for (offset in pattern.semitoneOffsets) {
                assertTrue("Offset $offset out of range in ${pattern.id}", offset in -12..0)
            }
        }
    }

    @Test
    fun `ids are unique and lookup falls back safely`() {
        val ids = StringPatterns.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(StringPatterns.PA, StringPatterns.byId("nonsense"))
        assertEquals(StringPatterns.MA, StringPatterns.byId("ma"))
    }
}

class VoiceTest {

    @Test
    fun `per-string values stretch to five strings without losing the low string`() {
        val voice = TanpuraVoices.MALE
        val four = voice.perString(voice.stringDecayScale, 4)
        assertEquals(4, four.size)
        assertEquals(voice.stringDecayScale[0], four[0], 1e-6f)
        assertEquals(voice.stringDecayScale.last(), four[3], 1e-6f)

        val five = voice.perString(voice.stringDecayScale, 5)
        assertEquals(5, five.size)
        assertEquals(voice.stringDecayScale[0], five[0], 1e-6f)
        // The extra string behaves like a middle Sa string.
        assertEquals(voice.stringDecayScale[2], five[3], 1e-6f)
        // The low brass string stays last.
        assertEquals(voice.stringDecayScale.last(), five[4], 1e-6f)
    }

    @Test
    fun `voice ids are unique and lookup falls back to male`() {
        val ids = TanpuraVoices.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(TanpuraVoices.MALE, TanpuraVoices.byId("nope"))
    }

    @Test
    fun `suggested pitches sit inside the selectable range`() {
        for (voice in TanpuraVoices.all) {
            assertTrue(
                "${voice.id} suggests an unreachable Sa",
                voice.suggestedSaMidi in Pitch.MIN_SA..Pitch.MAX_SA,
            )
        }
    }
}

class SettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round-trips through json`() {
        val original = TanpuraSettings(
            saMidi = 51,
            fineCents = -7.5f,
            patternId = "ni_komal",
            voiceId = "female",
            cycleSeconds = 2.75f,
            stringGains = listOf(0.8f, 1f, 1f, 0.9f, 1f),
            stringMuted = listOf(true, false, false, false, false),
            samplePackUris = listOf("content://a", "content://b"),
            loopPitchCents = 45f,
        )
        val text = json.encodeToString(TanpuraSettings.serializer(), original)
        val restored = json.decodeFromString(TanpuraSettings.serializer(), text)
        assertEquals(original, restored)
    }

    @Test
    fun `older saved blobs still load`() {
        // A blob written before samplePackUris and jawariTrim existed.
        val legacy = """{"saMidi":45,"patternId":"ma","voiceId":"instrumental"}"""
        val restored = json.decodeFromString(TanpuraSettings.serializer(), legacy)
        assertEquals(45, restored.saMidi)
        assertEquals("ma", restored.patternId)
        assertEquals(0f, restored.jawariTrim, 0f)
        assertTrue(restored.samplePackUris.isEmpty())
    }

    @Test
    fun `mute reports zero gain and survives a short list`() {
        val settings = TanpuraSettings(stringGains = listOf(0.5f), stringMuted = listOf(false))
        assertEquals(0.5f, settings.stringGain(0), 1e-6f)
        assertEquals(1f, settings.stringGain(4), 1e-6f)
        val muted = settings.withStringMuted(2, true)
        assertEquals(0f, muted.stringGain(2), 1e-6f)
    }

    @Test
    fun `reverb mix falls back to the voice default until it is set`() {
        val defaults = TanpuraSettings(voiceId = "instrumental")
        assertEquals(TanpuraVoices.INSTRUMENTAL.reverbMix, defaults.effectiveReverbMix, 1e-6f)
        val explicit = defaults.copy(reverbMix = 0.4f)
        assertEquals(0.4f, explicit.effectiveReverbMix, 1e-6f)
    }
}

class PitchDetectorTest {

    @Test
    fun `finds the fundamental of a synthetic tone`() {
        val rate = 48_000
        val targets = floatArrayOf(82.41f, 146.83f, 261.63f, 440f)
        for (target in targets) {
            val samples = FloatArray(rate / 2) { i ->
                val t = i.toDouble() / rate
                (Math.sin(2 * Math.PI * target * t) +
                    0.4 * Math.sin(4 * Math.PI * target * t) +
                    0.2 * Math.sin(6 * Math.PI * target * t)).toFloat() * 0.3f
            }
            val detected = PitchDetector.detectInWindow(samples, rate)
            assertTrue("Nothing detected for $target", detected != null)
            assertTrue(
                "Detected $detected instead of $target",
                abs(PitchDetector.cents(target, detected!!)) < 5f,
            )
        }
    }

    @Test
    fun `returns null for silence`() {
        assertEquals(null, PitchDetector.detectInWindow(FloatArray(48_000), 48_000))
    }
}
