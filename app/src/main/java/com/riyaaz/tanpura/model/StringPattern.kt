package com.riyaaz.tanpura.model

/**
 * A tanpura tuning. Offsets are in semitones relative to the middle Sa.
 *
 * On a real four-string tanpura the strings are, from the player's first string
 * inwards: a "side" string tuned to Pa (or Ma, or Ni, depending on the raga),
 * two middle strings both at Sa, and a thick brass string at the Sa an octave
 * below. Because the side string sounds *below* the middle Sa, Pa is -5
 * semitones (a fourth down), not +7.
 */
data class StringPattern(
    val id: String,
    val label: String,
    val swaraLabels: List<String>,
    val semitoneOffsets: List<Int>,
    val note: String = "",
) {
    val stringCount: Int get() = semitoneOffsets.size
}

object StringPatterns {

    private fun fourString(
        id: String,
        firstSwara: Swara,
        note: String = "",
    ): StringPattern {
        val offset = firstSwara.lowerOctaveOffset
        return StringPattern(
            id = id,
            label = "${firstSwara.label} – Sa – Sa – Sa",
            swaraLabels = listOf(firstSwara.label, "Sa", "Sa", "Sa↓"),
            semitoneOffsets = listOf(offset, 0, 0, -12),
            note = note,
        )
    }

    val PA = fourString("pa", Swara.PA, "Standard tuning, works for most ragas")
    val MA = fourString("ma", Swara.MA, "For ragas that omit Pa (Malkauns, Chandrakauns)")
    val MA_TEEVRA = fourString("ma_teevra", Swara.MA_TEEVRA, "For Marwa, Puriya, Sohini")
    val NI = fourString("ni", Swara.NI, "For Bhairav-family and Lalit")
    val NI_KOMAL = fourString("ni_komal", Swara.NI_KOMAL, "For Todi, Miyan ki Todi")
    val DHA = fourString("dha", Swara.DHA, "Occasionally used for Bihag and Kalyan")
    val GA = fourString("ga", Swara.GA, "Rare; for ragas built around Ga")

    val SA_ONLY = StringPattern(
        id = "sa",
        label = "Sa – Sa – Sa – Sa",
        swaraLabels = listOf("Sa", "Sa", "Sa", "Sa↓"),
        semitoneOffsets = listOf(0, 0, 0, -12),
        note = "Pure Sa drone, no side string",
    )

    val PA_FIVE = StringPattern(
        id = "pa5",
        label = "Pa – Sa – Sa – Sa – Sa",
        swaraLabels = listOf("Pa", "Sa", "Sa", "Sa", "Sa↓"),
        semitoneOffsets = listOf(-5, 0, 0, 0, -12),
        note = "Five-string tanpura, fuller wash",
    )

    val NI_PA_FIVE = StringPattern(
        id = "nipa5",
        label = "Pa – Ni – Sa – Sa – Sa",
        swaraLabels = listOf("Pa", "Ni", "Sa", "Sa", "Sa↓"),
        semitoneOffsets = listOf(-5, -1, 0, 0, -12),
        note = "Five-string with both Pa and Ni side strings",
    )

    val all: List<StringPattern> = listOf(
        PA, MA, MA_TEEVRA, NI, NI_KOMAL, DHA, GA, SA_ONLY, PA_FIVE, NI_PA_FIVE,
    )

    fun byId(id: String): StringPattern = all.firstOrNull { it.id == id } ?: PA
}
