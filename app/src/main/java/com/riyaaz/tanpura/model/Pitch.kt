package com.riyaaz.tanpura.model

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pitch helpers. Sa is stored as a MIDI note number plus a cents offset, and the
 * concert-A reference is adjustable because a lot of players tune to 442 Hz (or
 * to whatever their harmonium happens to be).
 */
object Pitch {

    val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Lowest / highest Sa the app offers: C2 .. C5. */
    const val MIN_SA = 36
    const val MAX_SA = 72

    /** Default Sa: C#3, the classic male-voice tanpura pitch. */
    const val DEFAULT_SA = 49

    fun frequency(midi: Int, cents: Float = 0f, a4Hz: Float = 440f): Float {
        val semitonesFromA4 = midi - 69 + cents / 100f
        return a4Hz * 2f.pow(semitonesFromA4 / 12f)
    }

    fun frequencyFromSemitone(semitonesFromSa: Float, saMidi: Int, cents: Float, a4Hz: Float): Float {
        val semitonesFromA4 = saMidi + semitonesFromSa - 69 + cents / 100f
        return a4Hz * 2f.pow(semitonesFromA4 / 12f)
    }

    fun octave(midi: Int): Int = midi / 12 - 1

    fun pitchClass(midi: Int): Int = ((midi % 12) + 12) % 12

    /** "C#3" */
    fun noteName(midi: Int, useFlats: Boolean = false): String {
        val names = if (useFlats) FLAT_NAMES else SHARP_NAMES
        return names[pitchClass(midi)] + octave(midi)
    }

    /** "C#" without the octave number. */
    fun noteNameNoOctave(midi: Int, useFlats: Boolean = false): String =
        (if (useFlats) FLAT_NAMES else SHARP_NAMES)[pitchClass(midi)]

    /** Formats a cents offset the way a tuner would: "+12 c", "0 c", "-4 c". */
    fun formatCents(cents: Float): String {
        val c = cents.roundToInt()
        return when {
            c > 0 -> "+$c c"
            c < 0 -> "$c c"
            else -> "0 c"
        }
    }
}

/**
 * The twelve swaras of a saptak, as offsets from Sa. Used to label the strings
 * and the tuner reference tones.
 */
enum class Swara(val semitone: Int, val label: String, val fullName: String) {
    SA(0, "Sa", "Shadja"),
    RE_KOMAL(1, "re", "Komal Rishabh"),
    RE(2, "Re", "Shuddh Rishabh"),
    GA_KOMAL(3, "ga", "Komal Gandhar"),
    GA(4, "Ga", "Shuddh Gandhar"),
    MA(5, "Ma", "Shuddh Madhyam"),
    MA_TEEVRA(6, "Ma♯", "Teevra Madhyam"),
    PA(7, "Pa", "Pancham"),
    DHA_KOMAL(8, "dha", "Komal Dhaivat"),
    DHA(9, "Dha", "Shuddh Dhaivat"),
    NI_KOMAL(10, "ni", "Komal Nishad"),
    NI(11, "Ni", "Shuddh Nishad"),
    ;

    /** Offset when this swara is sounded in the octave *below* the middle Sa. */
    val lowerOctaveOffset: Int get() = semitone - 12
}
