package com.riyaaz.tanpura.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Autocorrelation pitch detector, used for two things:
 *
 *  - working out the natural pitch of an audio file the user imported, so the
 *    sampler can retune it to the chosen Sa without the user having to know what
 *    key the recording is in;
 *  - verifying in unit tests that the synthesised string actually sounds at the
 *    frequency it was asked for.
 */
object PitchDetector {

    /**
     * @return the detected fundamental in Hz, or null if the signal is too quiet
     * or too noisy to call.
     */
    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        minHz: Float = 50f,
        maxHz: Float = 1200f,
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): Float? {
        if (length <= 0) return null
        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(length / 2 - 1)
        if (maxLag <= minLag) return null

        // Remove DC and check we have signal at all.
        var mean = 0f
        for (i in 0 until length) mean += samples[offset + i]
        mean /= length
        var energy = 0f
        for (i in 0 until length) {
            val v = samples[offset + i] - mean
            energy += v * v
        }
        val rms = sqrt(energy / length)
        if (rms < 1e-6f) return null

        // Normalised correlation for every candidate lag.
        val scores = FloatArray(maxLag + 1)
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            val score = normalizedCorr(samples, offset, length, mean, lag)
            scores[lag] = score
            if (score > bestScore) bestScore = score
        }
        if (bestScore < 0.35f) return null

        // Sub-harmonic rejection. A harmonically rich tone correlates almost as
        // well at two or three times its true period, and picking the global
        // maximum lands an octave (or a twelfth) too low. So take the *shortest*
        // lag that is both a local peak and nearly as good as the best one.
        val threshold = bestScore * 0.90f
        var bestLag = -1
        for (lag in (minLag + 1) until maxLag) {
            if (scores[lag] >= threshold &&
                scores[lag] >= scores[lag - 1] &&
                scores[lag] >= scores[lag + 1]
            ) {
                bestLag = lag
                break
            }
        }
        if (bestLag < 0) {
            // No interior peak cleared the bar; fall back to the global maximum.
            bestLag = (minLag..maxLag).maxByOrNull { scores[it] } ?: return null
        }

        // Parabolic interpolation around the peak for sub-sample precision.
        val refined = refineLag(samples, offset, length, mean, bestLag, minLag, maxLag)
        return sampleRate / refined
    }

    private fun refineLag(
        samples: FloatArray,
        offset: Int,
        length: Int,
        mean: Float,
        lag: Int,
        minLag: Int,
        maxLag: Int,
    ): Float {
        if (lag <= minLag || lag >= maxLag) return lag.toFloat()
        val ym = normalizedCorr(samples, offset, length, mean, lag - 1)
        val y0 = normalizedCorr(samples, offset, length, mean, lag)
        val yp = normalizedCorr(samples, offset, length, mean, lag + 1)
        val denom = 2f * (2f * y0 - ym - yp)
        if (abs(denom) < 1e-12f) return lag.toFloat()
        val delta = (yp - ym) / denom
        return lag + delta.coerceIn(-1f, 1f)
    }

    private fun normalizedCorr(
        samples: FloatArray,
        offset: Int,
        length: Int,
        mean: Float,
        lag: Int,
    ): Float {
        var corr = 0f
        var normA = 0f
        var normB = 0f
        val count = length - lag
        var i = 0
        while (i < count) {
            val a = samples[offset + i] - mean
            val b = samples[offset + i + lag] - mean
            corr += a * b
            normA += a * a
            normB += b * b
            i++
        }
        val denom = sqrt(normA * normB)
        return if (denom <= 0f) 0f else corr / denom
    }

    /**
     * Analyses a short window instead of the whole recording. Autocorrelation is
     * O(window x lags), so running it over a twenty-second file would take
     * seconds; a quarter of a second just after the attack is plenty and finishes
     * in tens of milliseconds.
     */
    fun detectInWindow(
        samples: FloatArray,
        sampleRate: Int,
        startSeconds: Float = 0.12f,
        windowSeconds: Float = 0.25f,
    ): Float? {
        if (samples.isEmpty()) return null
        val window = (windowSeconds * sampleRate).toInt().coerceAtLeast(1024)
        var start = (startSeconds * sampleRate).toInt()
        if (start + window > samples.size) start = 0
        val length = minOf(window, samples.size - start)
        if (length < 1024) return null
        return detect(samples, sampleRate, offset = start, length = length)
    }

    /** Difference between two frequencies in cents. */
    fun cents(from: Float, to: Float): Float =
        (1200.0 * ln((to / from).toDouble()) / ln(2.0)).toFloat()
}
