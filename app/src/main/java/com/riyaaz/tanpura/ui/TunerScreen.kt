package com.riyaaz.tanpura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.playback.PlaybackController
import com.riyaaz.tanpura.playback.TransportState
import com.riyaaz.tanpura.ui.components.HintText
import com.riyaaz.tanpura.ui.components.LabeledSlider
import com.riyaaz.tanpura.ui.components.SectionCard
import com.riyaaz.tanpura.ui.components.VSpace
import kotlin.math.roundToInt

private data class ToneEntry(val label: String, val semitone: Int, val detail: String)

private val TONE_GRID: List<ToneEntry> = listOf(
    ToneEntry("Sa↓", -12, "mandra Sa"),
    ToneEntry("Ma↓", -7, "mandra Ma"),
    ToneEntry("Pa↓", -5, "mandra Pa"),
    ToneEntry("Ni↓", -1, "mandra Ni"),
    ToneEntry("Sa", 0, "Shadja"),
    ToneEntry("re", 1, "komal Re"),
    ToneEntry("Re", 2, "shuddh Re"),
    ToneEntry("ga", 3, "komal Ga"),
    ToneEntry("Ga", 4, "shuddh Ga"),
    ToneEntry("Ma", 5, "shuddh Ma"),
    ToneEntry("Ma♯", 6, "teevra Ma"),
    ToneEntry("Pa", 7, "Pancham"),
    ToneEntry("dha", 8, "komal Dha"),
    ToneEntry("Dha", 9, "shuddh Dha"),
    ToneEntry("ni", 10, "komal Ni"),
    ToneEntry("Ni", 11, "shuddh Ni"),
    ToneEntry("Sa↑", 12, "taar Sa"),
)

/**
 * Sustained reference tones for tuning an instrument, plus the concert-A
 * reference so the whole app can follow a harmonium that is not at 440.
 */
@Composable
fun TunerScreen(
    controller: PlaybackController,
    settings: TanpuraSettings,
    transport: TransportState,
    modifier: Modifier = Modifier,
) {
    val rows = remember { TONE_GRID.chunked(4) }
    val active = transport.referenceSemitone

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        VSpace(10)

        SectionCard(title = "Reference tone") {
            val freq = active?.let {
                Pitch.frequencyFromSemitone(it.toFloat(), settings.saMidi, settings.fineCents, settings.a4Hz)
            }
            Text(
                text = if (active == null) {
                    "Tap a swara to sound it"
                } else {
                    "${TONE_GRID.first { it.semitone == active }.label}  ·  ${String.format("%.2f", freq)} Hz"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (active == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            VSpace(10)

            for (row in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (entry in row) {
                        val selected = active == entry.semitone
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    controller.setReferenceTone(
                                        if (selected) null else entry.semitone
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    Pitch.noteName(settings.saMidi + entry.semitone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    // Pad the final short row so the cells keep their width.
                    repeat(4 - row.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }

            if (active != null) {
                Button(
                    onClick = { controller.setReferenceTone(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop tone")
                }
            }
        }

        VSpace(12)

        SectionCard(title = "Concert pitch") {
            LabeledSlider(
                label = "A4 reference",
                value = settings.a4Hz,
                valueRange = 415f..466f,
                valueText = "${settings.a4Hz.roundToInt()} Hz",
                onValueChange = { v ->
                    controller.update { it.copy(a4Hz = v.roundToInt().toFloat()) }
                },
            )
            HintText(
                "Set this to match the harmonium or keyboard you play with. " +
                    "Everything else in the app follows it."
            )
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in listOf(432, 440, 442, 444)) {
                    Button(
                        onClick = { controller.update { it.copy(a4Hz = preset.toFloat()) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("$preset")
                    }
                }
            }
        }

        VSpace(28)
    }
}
