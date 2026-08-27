package com.riyaaz.tanpura.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.playback.LoopStatus
import com.riyaaz.tanpura.playback.PlaybackController
import com.riyaaz.tanpura.playback.TransportState
import com.riyaaz.tanpura.ui.components.HintText
import com.riyaaz.tanpura.ui.components.LabeledSlider
import com.riyaaz.tanpura.ui.components.SectionCard
import com.riyaaz.tanpura.ui.components.VSpace
import kotlin.math.roundToInt

/**
 * Where the sound comes from: the built-in modelled tanpura, per-string
 * recordings you supply, or a long recording looped as-is.
 *
 * Imported files are read straight from wherever they already live on the device
 * through the system file picker. Nothing is copied into the app and nothing
 * leaves the phone.
 */
@Composable
fun SourcesScreen(
    controller: PlaybackController,
    settings: TanpuraSettings,
    transport: TransportState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val pickSamples = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val kept = uris.take(5)
        kept.forEach { uri -> persist(context, uri) }
        controller.importSamplePack(kept, kept.map { displayName(context, it) })
    }

    val pickLoop = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(context, uri)
        controller.importLoop(uri, displayName(context, uri))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        VSpace(10)

        SectionCard(title = "Sound source") {
            ModeRow(
                selected = settings.mode == EngineMode.SYNTH,
                title = "Built-in tanpura",
                subtitle = "Modelled strings with jawari. Any Sa, perfectly in tune.",
                onClick = { controller.setMode(EngineMode.SYNTH) },
            )
            ModeRow(
                selected = settings.mode == EngineMode.SAMPLES,
                title = "Your string recordings",
                subtitle = if (settings.samplePackUris.isEmpty()) {
                    "Import one short recording per string."
                } else {
                    "${settings.samplePackUris.size} file(s) loaded, retuned to your Sa."
                },
                onClick = { controller.setMode(EngineMode.SAMPLES) },
            )
            ModeRow(
                selected = settings.mode == EngineMode.LOOP,
                title = "Loop a recording",
                subtitle = settings.loopName ?: "Play any long tanpura recording on repeat.",
                onClick = { controller.setMode(EngineMode.LOOP) },
            )
        }

        VSpace(12)

        SectionCard(title = "String recordings") {
            HintText(
                "Pick up to five short files — one per string, in strum order " +
                    "(first string, then the Sa strings, then the low Sa). The app " +
                    "detects each file's pitch and resamples it to whatever Sa you " +
                    "choose, so the strum, speed and tuning controls all keep working."
            )
            VSpace(10)
            if (settings.samplePackNames.isNotEmpty()) {
                settings.samplePackNames.forEachIndexed { index, name ->
                    val hz = settings.samplePackBaseHz.getOrNull(index) ?: 0f
                    Text(
                        text = "${index + 1}. $name" +
                            if (hz > 0f) "  ·  ${String.format("%.1f", hz)} Hz detected" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                VSpace(8)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { pickSamples.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !transport.busy,
                ) {
                    Text(if (settings.samplePackUris.isEmpty()) "Import files" else "Replace files")
                }
                if (settings.samplePackUris.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { controller.clearSamplePack() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear")
                    }
                }
            }
            if (transport.busy) {
                VSpace(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                    Text("Decoding…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        VSpace(12)

        SectionCard(title = "Long recording loop") {
            HintText(
                "For a continuous tanpura recording of any length. The file is " +
                    "streamed from storage and looped, so a three-hour recording " +
                    "uses no more memory than a three-second one."
            )
            VSpace(10)
            if (settings.loopName != null) {
                Text(
                    settings.loopName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when (transport.loopStatus) {
                        LoopStatus.LOADING -> "Opening…"
                        LoopStatus.PLAYING -> "Ready"
                        LoopStatus.FAILED -> "Could not decode this file"
                        LoopStatus.NONE -> "Not loaded"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (transport.loopStatus == LoopStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                VSpace(8)
                LabeledSlider(
                    label = "Pitch shift",
                    value = settings.loopPitchCents,
                    valueRange = -1200f..1200f,
                    valueText = Pitch.formatCents(settings.loopPitchCents),
                    onValueChange = { v ->
                        controller.update { it.copy(loopPitchCents = v.roundToInt().toFloat()) }
                    },
                )
                HintText(
                    "Shifting the pitch of a recording also changes its speed, " +
                        "the same way a tape machine would."
                )
                VSpace(8)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { pickLoop.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (settings.loopUri == null) "Pick recording" else "Change")
                }
                if (settings.loopUri != null) {
                    OutlinedButton(
                        onClick = { controller.clearLoop() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove")
                    }
                }
            }
        }

        VSpace(12)

        SectionCard(title = "About imported audio") {
            HintText(
                "The tanpura that ships with this app is synthesised, so it is free " +
                    "of any recording rights. Files you import are only read from " +
                    "your own device for your own practice — if you ever want to " +
                    "publish or share a build with recordings baked in, use audio " +
                    "you recorded yourself or that you have a licence for."
            )
        }

        VSpace(28)
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Keeps read access to the picked file across restarts. */
private fun persist(context: android.content.Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (e: SecurityException) {
        // Some providers do not offer persistable grants; the file still works
        // for this session.
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    DocumentFile.fromSingleUri(context, uri)?.name
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Recording"
