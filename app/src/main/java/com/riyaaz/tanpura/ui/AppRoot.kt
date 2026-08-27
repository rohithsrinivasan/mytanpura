package com.riyaaz.tanpura.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.playback.PlaybackController

private enum class Tab(val label: String, val icon: ImageVector) {
    PLAYER("Tanpura", Icons.Filled.MusicNote),
    TUNER("Tuner", Icons.Filled.GraphicEq),
    SOURCES("Audio", Icons.Filled.LibraryMusic),
    SETTINGS("Settings", Icons.Filled.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(controller: PlaybackController) {
    val settings by controller.settings.collectAsStateWithLifecycle()
    val transport by controller.transport.collectAsStateWithLifecycle()
    val presets by controller.presets.collectAsStateWithLifecycle()
    val timerConfig by controller.timerConfig.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.PLAYER) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var showTimer by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(transport.message) {
        val text = transport.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        controller.clearMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            Tab.PLAYER -> if (transport.isPlaying) {
                                "Playing · Sa ${Pitch.noteName(settings.saMidi)}"
                            } else {
                                "Tanpura"
                            }
                            else -> tab.label
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            when (tab) {
                Tab.PLAYER -> PlayerScreen(
                    controller = controller,
                    settings = settings,
                    transport = transport,
                    onOpenPresets = { showPresets = true },
                    onOpenTimer = { showTimer = true },
                )

                Tab.TUNER -> TunerScreen(
                    controller = controller,
                    settings = settings,
                    transport = transport,
                )

                Tab.SOURCES -> SourcesScreen(
                    controller = controller,
                    settings = settings,
                    transport = transport,
                )

                Tab.SETTINGS -> SettingsScreen(
                    controller = controller,
                    settings = settings,
                )
            }
        }
    }

    if (showPresets) {
        PresetsSheet(
            presets = presets,
            current = settings,
            onApply = { preset ->
                controller.applyPreset(preset)
                showPresets = false
            },
            onSave = { name -> controller.savePreset(name, System.currentTimeMillis()) },
            onDelete = { id -> controller.deletePreset(id) },
            onDismiss = { showPresets = false },
        )
    }

    if (showTimer) {
        TimerSheet(
            config = timerConfig,
            running = transport.timerRunning,
            remainingSeconds = transport.timerRemainingSeconds,
            onStart = { minutes, fade -> controller.startTimer(minutes, fade) },
            onCancel = { controller.cancelTimer() },
            onDismiss = { showTimer = false },
        )
    }
}
