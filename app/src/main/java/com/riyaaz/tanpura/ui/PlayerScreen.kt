package com.riyaaz.tanpura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.StringPatterns
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TanpuraVoices
import com.riyaaz.tanpura.playback.PlaybackController
import com.riyaaz.tanpura.playback.TransportState
import com.riyaaz.tanpura.ui.components.ChoiceChips
import com.riyaaz.tanpura.ui.components.ExpandableSection
import com.riyaaz.tanpura.ui.components.HintText
import com.riyaaz.tanpura.ui.components.LabeledSlider
import com.riyaaz.tanpura.ui.components.PitchSelector
import com.riyaaz.tanpura.ui.components.SectionCard
import com.riyaaz.tanpura.ui.components.TanpuraGraphic
import com.riyaaz.tanpura.ui.components.VSpace
import com.riyaaz.tanpura.ui.components.rememberInstrumentVisual
import kotlin.math.roundToInt

/** Slowest and fastest strum cycle offered by the speed slider, in seconds. */
private const val SLOWEST_CYCLE = 8.0f
private const val FASTEST_CYCLE = 0.9f

@Composable
fun PlayerScreen(
    controller: PlaybackController,
    settings: TanpuraSettings,
    transport: TransportState,
    onOpenPresets: () -> Unit,
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual by rememberInstrumentVisual(controller.engine, animate = transport.isPlaying)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        VSpace(8)

        PitchSelector(
            saMidi = settings.saMidi,
            fineCents = settings.fineCents,
            onSaChange = { midi -> controller.update { it.copy(saMidi = midi) } },
        )

        TanpuraGraphic(
            visual = visual,
            stringLabels = settings.pattern.swaraLabels,
            heightDp = 230,
        )

        TransportRow(
            isPlaying = transport.isPlaying,
            transport = transport,
            onToggle = { controller.toggle() },
            onOpenPresets = onOpenPresets,
            onOpenTimer = onOpenTimer,
        )

        VSpace(14)

        if (settings.mode != EngineMode.SYNTH) {
            ModeBanner(settings, onSwitchToSynth = { controller.setMode(EngineMode.SYNTH) })
            VSpace(12)
        }

        SectionCard(title = "Fine tuning") {
            LabeledSlider(
                label = "Offset from equal temperament",
                value = settings.fineCents,
                valueRange = -50f..50f,
                valueText = Pitch.formatCents(settings.fineCents),
                onValueChange = { v ->
                    controller.update { it.copy(fineCents = (v * 2f).roundToInt() / 2f) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HintText(
                    "Sa sounds at ${String.format("%.2f", settings.saFrequency)} Hz " +
                        "(A4 = ${settings.a4Hz.roundToInt()} Hz)",
                    modifier = Modifier.weight(1f),
                )
                if (settings.fineCents != 0f) {
                    AssistChip(
                        onClick = { controller.update { it.copy(fineCents = 0f) } },
                        label = { Text("Reset") },
                    )
                }
            }
        }

        VSpace(12)

        SectionCard(title = "Tuning · first string") {
            ChoiceChips(
                options = StringPatterns.all,
                selected = settings.pattern,
                label = { it.label },
                key = { it.id },
                onSelect = { pattern -> controller.update { it.copy(patternId = pattern.id) } },
            )
            VSpace(8)
            HintText(settings.pattern.note)
        }

        VSpace(12)

        SectionCard(title = "Instrument") {
            ChoiceChips(
                options = TanpuraVoices.all,
                selected = settings.voice,
                label = { it.label },
                key = { it.id },
                onSelect = { voice ->
                    controller.update { it.copy(voiceId = voice.id) }
                },
            )
            VSpace(8)
            HintText(settings.voice.description)
            VSpace(6)
            AssistChip(
                onClick = {
                    controller.update { it.copy(saMidi = settings.voice.suggestedSaMidi) }
                },
                label = { Text("Use suggested Sa (${Pitch.noteName(settings.voice.suggestedSaMidi)})") },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        VSpace(12)

        SectionCard(title = "Performance") {
            val speedFraction = (SLOWEST_CYCLE - settings.cycleSeconds) /
                (SLOWEST_CYCLE - FASTEST_CYCLE)
            LabeledSlider(
                label = "Strum speed",
                value = speedFraction.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                valueText = "${String.format("%.1f", settings.cycleSeconds)} s / cycle",
                onValueChange = { fraction ->
                    val cycle = SLOWEST_CYCLE - fraction * (SLOWEST_CYCLE - FASTEST_CYCLE)
                    controller.update { it.copy(cycleSeconds = cycle) }
                },
            )
            LabeledSlider(
                label = "Volume",
                value = settings.masterVolume,
                valueRange = 0f..1f,
                valueText = "${(settings.masterVolume * 100).roundToInt()}%",
                onValueChange = { v -> controller.update { it.copy(masterVolume = v) } },
            )
        }

        VSpace(12)

        ExpandableSection(title = "Tone") {
            LabeledSlider(
                label = "Brightness",
                value = settings.brightnessTrim,
                valueRange = -1f..1f,
                valueText = trimText(settings.brightnessTrim),
                onValueChange = { v -> controller.update { it.copy(brightnessTrim = v) } },
            )
            LabeledSlider(
                label = "Jawari (bridge buzz)",
                value = settings.jawariTrim,
                valueRange = -1f..1f,
                valueText = trimText(settings.jawariTrim),
                onValueChange = { v -> controller.update { it.copy(jawariTrim = v) } },
            )
            LabeledSlider(
                label = "Sustain",
                value = settings.decayScale,
                valueRange = 0.5f..2f,
                valueText = "${String.format("%.2f", settings.decayScale)}x",
                onValueChange = { v -> controller.update { it.copy(decayScale = v) } },
            )
            LabeledSlider(
                label = "Room",
                value = settings.effectiveReverbMix,
                valueRange = 0f..0.6f,
                valueText = "${(settings.effectiveReverbMix * 167).roundToInt()}%",
                onValueChange = { v -> controller.update { it.copy(reverbMix = v) } },
            )
            LabeledSlider(
                label = "Human feel",
                value = settings.humanize,
                valueRange = 0f..1f,
                valueText = if (settings.humanize < 0.02f) "off" else "${(settings.humanize * 100).roundToInt()}%",
                onValueChange = { v -> controller.update { it.copy(humanize = v) } },
            )
            HintText(
                "Human feel varies the timing and strength of each pluck. Turn it " +
                    "off for a perfectly even reference drone."
            )
        }

        VSpace(12)

        ExpandableSection(title = "Strings") {
            val count = settings.pattern.stringCount
            for (i in 0 until count) {
                val label = settings.pattern.swaraLabels.getOrElse(i) { "String ${i + 1}" }
                val muted = settings.stringMuted.getOrElse(i) { false }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (muted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.width(46.dp),
                        textAlign = TextAlign.Start,
                    )
                    Box(Modifier.weight(1f)) {
                        LabeledSlider(
                            label = "",
                            value = settings.stringGains.getOrElse(i) { 1f },
                            valueRange = 0f..1.2f,
                            valueText = "${(settings.stringGains.getOrElse(i) { 1f } * 100).roundToInt()}%",
                            enabled = !muted,
                            onValueChange = { v ->
                                controller.update { it.withStringGain(i, v) }
                            },
                        )
                    }
                    Switch(
                        checked = !muted,
                        onCheckedChange = { on ->
                            controller.update { it.withStringMuted(i, !on) }
                        },
                    )
                }
            }
            HintText("Mute the first string to hear only Sa, the way a shruti box would.")
        }

        VSpace(28)
    }
}

private fun trimText(value: Float): String = when {
    value > 0.01f -> "+${(value * 100).roundToInt()}"
    value < -0.01f -> "${(value * 100).roundToInt()}"
    else -> "0"
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    transport: TransportState,
    onToggle: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenTimer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AssistChip(
            onClick = onOpenPresets,
            label = { Text("Presets") },
            leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
        )

        Box(
            modifier = Modifier
                .size(84.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        )
                    ),
                    shape = CircleShape,
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(42.dp),
            )
        }

        AssistChip(
            onClick = onOpenTimer,
            label = {
                Text(
                    if (transport.timerRunning) {
                        formatDuration(transport.timerRemainingSeconds)
                    } else {
                        "Timer"
                    }
                )
            },
            leadingIcon = { Icon(Icons.Filled.Timer, contentDescription = null) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = if (transport.timerRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        )
    }
}

@Composable
private fun ModeBanner(settings: TanpuraSettings, onSwitchToSynth: () -> Unit) {
    SectionCard(
        title = "Audio source",
        trailing = {
            AssistChip(onClick = onSwitchToSynth, label = { Text("Use built-in") })
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = when (settings.mode) {
                    EngineMode.SAMPLES -> "Playing your imported string recordings."
                    EngineMode.LOOP -> "Looping \"${settings.loopName ?: "imported recording"}\"."
                    EngineMode.SYNTH -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

fun formatDuration(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val minutes = s / 60
    val seconds = s % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
