package com.riyaaz.tanpura.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.Preset
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TimerConfig
import com.riyaaz.tanpura.ui.components.ChoiceChips
import com.riyaaz.tanpura.ui.components.HintText
import com.riyaaz.tanpura.ui.components.LabeledSlider
import com.riyaaz.tanpura.ui.components.VSpace
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(
    presets: List<Preset>,
    current: TanpuraSettings,
    onApply: (Preset) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(defaultPresetName(current)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Presets", style = MaterialTheme.typography.headlineSmall)
            VSpace(12)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onSave(name)
                        name = defaultPresetName(current)
                    },
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Text("Save")
                }
            }
            HintText("Saves the current pitch, tuning, instrument and tone.")
            VSpace(12)
            HorizontalDivider()

            if (presets.isEmpty()) {
                VSpace(16)
                HintText("No presets yet.")
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                items(items = presets, key = { it.id }) { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                presetSummary(preset.settings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onApply(preset) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Apply ${preset.name}",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { onDelete(preset.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete ${preset.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            VSpace(16)
        }
    }
}

private fun defaultPresetName(settings: TanpuraSettings): String =
    "${settings.voice.label} · ${Pitch.noteName(settings.saMidi)}"

private fun presetSummary(settings: TanpuraSettings): String = buildString {
    append("Sa ${Pitch.noteName(settings.saMidi)}")
    if (settings.fineCents != 0f) append(" ${Pitch.formatCents(settings.fineCents)}")
    append(" · ${settings.pattern.label}")
    append(" · ${String.format("%.1f", settings.cycleSeconds)} s")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSheet(
    config: TimerConfig,
    running: Boolean,
    remainingSeconds: Int,
    onStart: (minutes: Int, fadeSeconds: Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var minutes by remember { mutableStateOf(config.minutes) }
    var fade by remember { mutableStateOf(config.fadeSeconds.toFloat()) }
    val quickPicks = remember { listOf(5, 10, 15, 20, 30, 45, 60, 90) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text("Practice timer", style = MaterialTheme.typography.headlineSmall)
            VSpace(4)
            if (running) {
                Text(
                    "${formatDuration(remainingSeconds)} remaining",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                HintText("The drone fades out and stops when the time is up.")
            }
            VSpace(14)

            ChoiceChips(
                options = quickPicks,
                selected = minutes.takeIf { quickPicks.contains(it) },
                label = { "$it min" },
                key = { it },
                onSelect = { minutes = it },
            )
            VSpace(10)
            LabeledSlider(
                label = "Duration",
                value = minutes.toFloat(),
                valueRange = 1f..180f,
                valueText = "$minutes min",
                onValueChange = { minutes = it.roundToInt().coerceIn(1, 180) },
            )
            LabeledSlider(
                label = "Fade-out",
                value = fade,
                valueRange = 0f..60f,
                valueText = if (fade < 1f) "none" else "${fade.roundToInt()} s",
                onValueChange = { fade = it },
            )
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onStart(minutes, fade.roundToInt())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (running) "Restart" else "Start")
                }
                if (running) {
                    OutlinedButton(
                        onClick = {
                            onCancel()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel timer")
                    }
                }
            }
            VSpace(20)
        }
    }
}
