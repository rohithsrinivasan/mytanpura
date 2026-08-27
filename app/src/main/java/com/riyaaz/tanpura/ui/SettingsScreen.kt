package com.riyaaz.tanpura.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.riyaaz.tanpura.BuildConfig
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.playback.PlaybackController
import com.riyaaz.tanpura.ui.components.HintText
import com.riyaaz.tanpura.ui.components.LabeledSlider
import com.riyaaz.tanpura.ui.components.SectionCard
import com.riyaaz.tanpura.ui.components.VSpace
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    controller: PlaybackController,
    settings: TanpuraSettings,
    modifier: Modifier = Modifier,
) {
    var keepScreenOn by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        VSpace(10)

        SectionCard(title = "Tuning reference") {
            LabeledSlider(
                label = "A4",
                value = settings.a4Hz,
                valueRange = 415f..466f,
                valueText = "${settings.a4Hz.roundToInt()} Hz",
                onValueChange = { v -> controller.update { it.copy(a4Hz = v.roundToInt().toFloat()) } },
            )
            HintText(
                "Sa is currently ${Pitch.noteName(settings.saMidi)} = " +
                    "${String.format("%.2f", settings.saFrequency)} Hz."
            )
        }

        VSpace(12)

        SectionCard(title = "Session") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep the screen on", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Useful when the phone is propped up during riyaaz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it })
            }
            VSpace(6)
            HintText(
                "Playback continues when you leave the app or lock the screen. " +
                    "Use the notification to pause it."
            )
        }

        VSpace(12)

        SectionCard(title = "Reset") {
            OutlinedButton(
                onClick = {
                    controller.update { current ->
                        // Keep imported audio; reset everything musical.
                        TanpuraSettings(
                            samplePackUris = current.samplePackUris,
                            samplePackNames = current.samplePackNames,
                            samplePackBaseHz = current.samplePackBaseHz,
                            loopUri = current.loopUri,
                            loopName = current.loopName,
                            mode = current.mode,
                        )
                    }
                    controller.message("Settings reset to defaults.")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset tone and tuning to defaults")
            }
        }

        VSpace(12)

        SectionCard(title = "About") {
            Text("Tanpura ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            VSpace(6)
            HintText(
                "A tanpura for riyaaz: pick your Sa, pick the first-string tuning " +
                    "for the raga, and let it run. The built-in instrument is a " +
                    "physical model of a plucked string with a jawari bridge, so it " +
                    "is in tune at any pitch and never loops."
            )
            VSpace(8)
            HintText(
                "Strings are struck in order with a little timing and strength " +
                    "variation, the way a hand does it. Turn \"Human feel\" down to " +
                    "zero on the player screen if you want a mechanically even drone."
            )
        }

        VSpace(28)
    }
}
