package com.riyaaz.tanpura.data

import android.content.Context
import com.riyaaz.tanpura.model.Preset
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TimerConfig

/**
 * Signature-only stand-in for the real [SettingsStore], used by
 * tools/verify/run.sh so PlaybackController can be type-checked on machines that
 * cannot reach Google's Maven repo (the real store depends on AndroidX DataStore).
 *
 * This file is NOT part of the app - it lives under tools/ and is never compiled
 * into a build. If the real store's API changes, change it here too or the
 * offline type-check stops being meaningful.
 */
@Suppress("UNUSED_PARAMETER")
class SettingsStore(context: Context) {
    suspend fun loadSettings(): TanpuraSettings = TanpuraSettings()
    suspend fun saveSettings(settings: TanpuraSettings) = Unit
    suspend fun loadPresets(): List<Preset> = emptyList()
    suspend fun savePresets(presets: List<Preset>) = Unit
    suspend fun loadTimer(): TimerConfig = TimerConfig()
    suspend fun saveTimer(config: TimerConfig) = Unit
}
