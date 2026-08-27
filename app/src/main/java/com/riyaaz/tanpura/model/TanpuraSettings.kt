package com.riyaaz.tanpura.model

import kotlinx.serialization.Serializable

enum class EngineMode {
    /** Physically modelled tanpura that ships with the app. */
    SYNTH,

    /** Per-string audio files the user imported, played by the strum sequencer. */
    SAMPLES,

    /** A long recording streamed and looped as-is. */
    LOOP,
}

/**
 * Everything that defines how the tanpura sounds right now. This is the single
 * source of truth: it is what gets persisted, what gets saved into presets, and
 * what gets handed to the audio engine.
 */
@Serializable
data class TanpuraSettings(
    val mode: EngineMode = EngineMode.SYNTH,

    // ---- pitch ----
    val saMidi: Int = Pitch.DEFAULT_SA,
    val fineCents: Float = 0f,
    val a4Hz: Float = 440f,
    val patternId: String = StringPatterns.PA.id,

    // ---- instrument ----
    val voiceId: String = TanpuraVoices.MALE.id,
    /** -1..+1 offset applied on top of the voice's own brightness. */
    val brightnessTrim: Float = 0f,
    /** -1..+1 offset applied on top of the voice's own jawari amount. */
    val jawariTrim: Float = 0f,
    /** 0.5x .. 2x multiplier on the voice's decay time. */
    val decayScale: Float = 1f,

    // ---- performance ----
    val cycleSeconds: Float = 3.2f,
    val humanize: Float = 0.35f,

    // ---- mix ----
    val masterVolume: Float = 0.78f,
    val reverbMix: Float = -1f, // < 0 means "use the voice default"
    val stringGains: List<Float> = List(5) { 1f },
    val stringMuted: List<Boolean> = List(5) { false },

    // ---- imported audio ----
    /** One file per string, first string first. Empty means "no pack loaded". */
    val samplePackUris: List<String> = emptyList(),
    val samplePackNames: List<String> = emptyList(),
    /** Detected natural pitch of each sample file, in Hz; 0 means "not detected". */
    val samplePackBaseHz: List<Float> = emptyList(),
    val loopUri: String? = null,
    val loopName: String? = null,
    val loopPitchCents: Float = 0f,
) {
    val pattern: StringPattern get() = StringPatterns.byId(patternId)
    val voice: TanpuraVoice get() = TanpuraVoices.byId(voiceId)

    val saFrequency: Float get() = Pitch.frequency(saMidi, fineCents, a4Hz)

    val effectiveReverbMix: Float get() = if (reverbMix < 0f) voice.reverbMix else reverbMix

    fun stringGain(index: Int): Float =
        if (stringMuted.getOrElse(index) { false }) 0f else stringGains.getOrElse(index) { 1f }

    fun withStringGain(index: Int, gain: Float): TanpuraSettings {
        val list = stringGains.toMutableList()
        while (list.size <= index) list.add(1f)
        list[index] = gain.coerceIn(0f, 1.5f)
        return copy(stringGains = list)
    }

    fun withStringMuted(index: Int, muted: Boolean): TanpuraSettings {
        val list = stringMuted.toMutableList()
        while (list.size <= index) list.add(false)
        list[index] = muted
        return copy(stringMuted = list)
    }
}

/** A named, saved setup. */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val settings: TanpuraSettings,
    val createdAtMillis: Long = 0L,
)

@Serializable
data class PresetLibrary(val presets: List<Preset> = emptyList())

/** Countdown practice timer. */
@Serializable
data class TimerConfig(
    val minutes: Int = 30,
    val fadeSeconds: Int = 15,
)
