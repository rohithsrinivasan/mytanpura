package com.riyaaz.tanpura.verify

import com.riyaaz.tanpura.audio.Dsp
import com.riyaaz.tanpura.audio.PitchDetector
import com.riyaaz.tanpura.audio.StringVoice
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraVoices
import kotlin.math.abs

/**
 * Prints the measured tuning error of the synthesised string across the app's
 * whole pitch range and every instrument voice.
 *
 * Run with tools/verify/run.sh --report. This is a measurement tool, not a test:
 * the pass/fail bound lives in StringVoiceTest.
 */
object TuningReport {

    private const val SAMPLE_RATE = 48_000

    @JvmStatic
    fun main(args: Array<String>) {
        println("voice        note   target Hz   measured Hz   error (cents)")
        println("-".repeat(62))

        var worst = 0f
        var worstLabel = ""
        var count = 0
        var sum = 0f

        for (voice in TanpuraVoices.all) {
            // Every semitone from the lowest brass string to the highest side string.
            var midi = Pitch.MIN_SA - 12
            while (midi <= Pitch.MAX_SA) {
                val target = Pitch.frequency(midi)
                if (target < 45f) {
                    midi += 1
                    continue
                }
                val measured = measure(target, voice.brightness, voice.decaySeconds, voice.jawari)
                if (measured == null) {
                    println("${voice.id.padEnd(13)}${Pitch.noteName(midi).padEnd(7)}  no pitch detected")
                    midi += 1
                    continue
                }
                val cents = PitchDetector.cents(target, measured)
                sum += abs(cents)
                count++
                if (abs(cents) > worst) {
                    worst = abs(cents)
                    worstLabel = "${voice.id} ${Pitch.noteName(midi)}"
                }
                if (abs(cents) > 1.5f) {
                    println(
                        voice.id.padEnd(13) +
                            Pitch.noteName(midi).padEnd(7) +
                            format(target).padStart(9) + "   " +
                            format(measured).padStart(11) + "   " +
                            format(cents).padStart(8)
                    )
                }
                midi += 1
            }
        }

        println("-".repeat(62))
        println("measurements: $count")
        println("mean |error|: ${format(sum / count)} cents")
        println("worst error : ${format(worst)} cents  ($worstLabel)")
    }

    private fun format(v: Float): String = String.format("%.3f", v)

    private fun measure(
        freq: Float,
        brightness: Float,
        decay: Float,
        jawari: Float,
    ): Float? {
        val voice = StringVoice(SAMPLE_RATE)
        voice.setCharacter(brightness, decay, jawari, 0.22f, 0.5f)
        voice.reset()
        voice.setFrequency(freq, glide = false)
        voice.gain = 1f
        voice.pluck(1f)

        val total = SAMPLE_RATE
        val out = FloatArray(total)
        var done = 0
        while (done < total) {
            val n = minOf(Dsp.CONTROL_BLOCK, total - done)
            voice.render(out, done, n)
            done += n
        }
        return PitchDetector.detect(
            out,
            SAMPLE_RATE,
            minHz = freq * 0.7f,
            maxHz = freq * 1.4f,
            offset = (SAMPLE_RATE * 0.45f).toInt(),
            length = (SAMPLE_RATE * 0.35f).toInt(),
        )
    }
}
