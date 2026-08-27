package com.riyaaz.tanpura.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.riyaaz.tanpura.model.Preset
import com.riyaaz.tanpura.model.PresetLibrary
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TimerConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tanpura")

/**
 * Persists the current setup, the saved presets and the timer choice.
 *
 * Everything is stored as JSON in a single preferences file. `ignoreUnknownKeys`
 * plus default values on every field means an older saved blob still loads after
 * the model grows a new setting.
 */
class SettingsStore(private val context: Context) {

    private companion object {
        const val TAG = "TanpuraStore"
        val KEY_SETTINGS = stringPreferencesKey("settings")
        val KEY_PRESETS = stringPreferencesKey("presets")
        val KEY_TIMER = stringPreferencesKey("timer")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun loadSettings(): TanpuraSettings = read(KEY_SETTINGS, TanpuraSettings()) {
        json.decodeFromString(TanpuraSettings.serializer(), it)
    }

    suspend fun saveSettings(settings: TanpuraSettings) {
        write(KEY_SETTINGS, json.encodeToString(TanpuraSettings.serializer(), settings))
    }

    suspend fun loadPresets(): List<Preset> = read(KEY_PRESETS, defaultPresets()) {
        json.decodeFromString(PresetLibrary.serializer(), it).presets
    }

    suspend fun savePresets(presets: List<Preset>) {
        write(KEY_PRESETS, json.encodeToString(PresetLibrary.serializer(), PresetLibrary(presets)))
    }

    suspend fun loadTimer(): TimerConfig = read(KEY_TIMER, TimerConfig()) {
        json.decodeFromString(TimerConfig.serializer(), it)
    }

    suspend fun saveTimer(config: TimerConfig) {
        write(KEY_TIMER, json.encodeToString(TimerConfig.serializer(), config))
    }

    private suspend fun <T> read(key: Preferences.Key<String>, fallback: T, parse: (String) -> T): T {
        val raw = try {
            context.dataStore.data.first()[key]
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $key", e)
            null
        } ?: return fallback
        return try {
            parse(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt value for $key, using defaults", e)
            fallback
        }
    }

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        try {
            context.dataStore.edit { it[key] = value }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $key", e)
        }
    }

    /** Starter presets so the app is useful on first launch. */
    private fun defaultPresets(): List<Preset> = listOf(
        Preset(
            id = "builtin-male-csharp",
            name = "Male voice · C#",
            settings = TanpuraSettings(saMidi = 49, voiceId = "male", patternId = "pa", cycleSeconds = 3.4f),
        ),
        Preset(
            id = "builtin-female-gsharp",
            name = "Female voice · G#",
            settings = TanpuraSettings(saMidi = 56, voiceId = "female", patternId = "pa", cycleSeconds = 3.0f),
        ),
        Preset(
            id = "builtin-malkauns",
            name = "Malkauns · Ma tuning",
            settings = TanpuraSettings(saMidi = 48, voiceId = "male", patternId = "ma", cycleSeconds = 3.8f),
        ),
        Preset(
            id = "builtin-todi",
            name = "Todi · komal Ni",
            settings = TanpuraSettings(saMidi = 51, voiceId = "female", patternId = "ni_komal", cycleSeconds = 3.2f),
        ),
        Preset(
            id = "builtin-bansuri",
            name = "Bansuri riyaaz · A2",
            settings = TanpuraSettings(saMidi = 45, voiceId = "instrumental", patternId = "pa5", cycleSeconds = 4.2f),
        ),
    )
}
