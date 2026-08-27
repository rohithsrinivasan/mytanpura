package com.riyaaz.tanpura.model

/** One resonance of the instrument body, applied to the summed strings. */
data class BodyPeak(val freqHz: Float, val q: Float, val gainDb: Float)

/**
 * The "character" of an instrument: how big the gourd is, how the jawari is set,
 * how long the strings ring. These are the knobs the app's Male / Female /
 * Instrumental choices actually change.
 */
data class TanpuraVoice(
    val id: String,
    val label: String,
    val description: String,
    val brightness: Float,
    val decaySeconds: Float,
    val jawari: Float,
    val pluckPosition: Float,
    val tension: Float,
    val bodyPeaks: List<BodyPeak>,
    val highPassHz: Float,
    val lowShelfDb: Float,
    /** Relative pluck strength per string, first string first. */
    val stringVelocities: List<Float>,
    /** Relative output level per string. */
    val stringGains: List<Float>,
    /** Per-string decay multiplier: the thick low brass string rings longest. */
    val stringDecayScale: List<Float>,
    val suggestedSaMidi: Int,
    val reverbSize: Float,
    val reverbMix: Float,
) {
    /**
     * Stretches a per-string list to [count] entries. The last value always maps
     * to the low brass string; any extra strings on a five-string tanpura repeat
     * the middle-string value.
     */
    fun perString(values: List<Float>, count: Int): FloatArray {
        val out = FloatArray(count) { 1f }
        if (values.isEmpty() || count <= 0) return out
        val midIndex = (values.size - 2).coerceAtLeast(0)
        for (i in 0 until count - 1) {
            out[i] = values[i.coerceAtMost(midIndex)]
        }
        out[count - 1] = values.last()
        return out
    }
}

object TanpuraVoices {

    val MALE = TanpuraVoice(
        id = "male",
        label = "Male",
        description = "Large Tanjore-style tanpura. Deep, slow, long sustain.",
        brightness = 0.40f,
        decaySeconds = 11.5f,
        jawari = 0.62f,
        pluckPosition = 0.24f,
        tension = 0.65f,
        bodyPeaks = listOf(
            BodyPeak(104f, 0.9f, 5.0f),
            BodyPeak(248f, 1.2f, 3.0f),
            BodyPeak(615f, 1.5f, 2.0f),
            BodyPeak(1850f, 1.1f, -2.5f),
        ),
        highPassHz = 45f,
        lowShelfDb = 2.5f,
        stringVelocities = listOf(0.72f, 0.80f, 0.80f, 0.95f),
        stringGains = listOf(0.85f, 0.95f, 0.95f, 1.0f),
        stringDecayScale = listOf(0.85f, 1.0f, 1.0f, 1.35f),
        suggestedSaMidi = 49, // C#3
        reverbSize = 0.88f,
        reverbMix = 0.24f,
    )

    val FEMALE = TanpuraVoice(
        id = "female",
        label = "Female",
        description = "Miraj-style tanpura. Brighter, tighter, more shimmer.",
        brightness = 0.56f,
        decaySeconds = 8.5f,
        jawari = 0.58f,
        pluckPosition = 0.20f,
        tension = 0.55f,
        bodyPeaks = listOf(
            BodyPeak(152f, 1.0f, 4.5f),
            BodyPeak(340f, 1.3f, 3.0f),
            BodyPeak(830f, 1.5f, 2.5f),
            BodyPeak(2400f, 1.0f, -2.0f),
        ),
        highPassHz = 60f,
        lowShelfDb = 1.5f,
        stringVelocities = listOf(0.74f, 0.82f, 0.82f, 0.92f),
        stringGains = listOf(0.88f, 0.95f, 0.95f, 1.0f),
        stringDecayScale = listOf(0.9f, 1.0f, 1.0f, 1.3f),
        suggestedSaMidi = 56, // G#3
        reverbSize = 0.84f,
        reverbMix = 0.22f,
    )

    val INSTRUMENTAL = TanpuraVoice(
        id = "instrumental",
        label = "Instrumental",
        description = "Very deep tanpura for sitar, sarod and bansuri riyaaz.",
        brightness = 0.34f,
        decaySeconds = 13.5f,
        jawari = 0.70f,
        pluckPosition = 0.27f,
        tension = 0.75f,
        bodyPeaks = listOf(
            BodyPeak(88f, 0.85f, 6.0f),
            BodyPeak(210f, 1.1f, 3.5f),
            BodyPeak(540f, 1.5f, 1.5f),
            BodyPeak(1600f, 1.1f, -3.0f),
        ),
        highPassHz = 38f,
        lowShelfDb = 3.5f,
        stringVelocities = listOf(0.70f, 0.78f, 0.78f, 1.0f),
        stringGains = listOf(0.82f, 0.92f, 0.92f, 1.0f),
        stringDecayScale = listOf(0.85f, 1.0f, 1.0f, 1.4f),
        suggestedSaMidi = 45, // A2
        reverbSize = 0.92f,
        reverbMix = 0.28f,
    )

    val SHRUTI = TanpuraVoice(
        id = "shruti",
        label = "Soft",
        description = "Gentle, almost buzz-free drone. Easy on the ears for long sittings.",
        brightness = 0.46f,
        decaySeconds = 9.5f,
        jawari = 0.28f,
        pluckPosition = 0.33f,
        tension = 0.35f,
        bodyPeaks = listOf(
            BodyPeak(130f, 0.9f, 3.5f),
            BodyPeak(290f, 1.2f, 2.0f),
            BodyPeak(700f, 1.6f, 1.0f),
            BodyPeak(2000f, 0.9f, -4.0f),
        ),
        highPassHz = 50f,
        lowShelfDb = 2.0f,
        stringVelocities = listOf(0.70f, 0.75f, 0.75f, 0.85f),
        stringGains = listOf(0.9f, 0.95f, 0.95f, 1.0f),
        stringDecayScale = listOf(0.95f, 1.0f, 1.0f, 1.2f),
        suggestedSaMidi = 52, // E3
        reverbSize = 0.80f,
        reverbMix = 0.20f,
    )

    val all: List<TanpuraVoice> = listOf(MALE, FEMALE, INSTRUMENTAL, SHRUTI)

    fun byId(id: String): TanpuraVoice = all.firstOrNull { it.id == id } ?: MALE
}
