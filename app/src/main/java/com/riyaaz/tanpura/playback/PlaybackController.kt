package com.riyaaz.tanpura.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import com.riyaaz.tanpura.audio.AudioDecoder
import com.riyaaz.tanpura.audio.AudioOutput
import com.riyaaz.tanpura.audio.EmptyLoopSource
import com.riyaaz.tanpura.audio.MediaLoopSource
import com.riyaaz.tanpura.audio.PitchDetector
import com.riyaaz.tanpura.audio.SampleData
import com.riyaaz.tanpura.audio.TanpuraEngine
import com.riyaaz.tanpura.data.SettingsStore
import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.Preset
import com.riyaaz.tanpura.model.TanpuraSettings
import com.riyaaz.tanpura.model.TimerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the UI needs to know that is not a setting. */
data class TransportState(
    val isPlaying: Boolean = false,
    val timerTotalSeconds: Int = 0,
    val timerRemainingSeconds: Int = 0,
    val timerRunning: Boolean = false,
    val referenceSemitone: Int? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val loopStatus: LoopStatus = LoopStatus.NONE,
)

enum class LoopStatus { NONE, LOADING, PLAYING, FAILED }

/**
 * App-scoped owner of the audio engine.
 *
 * The UI and the foreground service both drive playback through this one object,
 * which keeps the engine alive across configuration changes and lets the
 * notification and the screen never disagree about what is playing.
 */
class PlaybackController(
    private val context: Context,
    private val store: SettingsStore,
) {

    private companion object {
        private const val TAG = "TanpuraController"
        private const val SAVE_DEBOUNCE_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = TanpuraEngine(AudioOutput.preferredSampleRate(context))
    private val output = AudioOutput(engine) { onAudioStoppedItself() }

    private val _settings = MutableStateFlow(TanpuraSettings())
    val settings: StateFlow<TanpuraSettings> = _settings.asStateFlow()

    private val _transport = MutableStateFlow(TransportState())
    val transport: StateFlow<TransportState> = _transport.asStateFlow()

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _timerConfig = MutableStateFlow(TimerConfig())
    val timerConfig: StateFlow<TimerConfig> = _timerConfig.asStateFlow()

    /** Set by the service so playback changes can raise/lower the notification. */
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false
    private var duckGain = 1f
    private var fadeGain = 1f

    private var saveJob: Job? = null
    private var timerJob: Job? = null
    private var loopSourceRef: MediaLoopSource? = null

    @Volatile
    private var loaded = false

    init {
        output.configure(context)
        scope.launch { restore() }
    }

    // ------------------------------------------------------------------
    // Load / persist
    // ------------------------------------------------------------------

    private suspend fun restore() {
        val saved = store.loadSettings()
        _presets.value = store.loadPresets()
        _timerConfig.value = store.loadTimer()
        _settings.value = saved
        engine.submit(saved)
        loaded = true
        // Re-attach any imported audio the user was last using.
        when (saved.mode) {
            EngineMode.SAMPLES -> reloadSamplePack(saved)
            EngineMode.LOOP -> saved.loopUri?.let { attachLoop(Uri.parse(it)) }
            EngineMode.SYNTH -> Unit
        }
    }

    private fun scheduleSave() {
        if (!loaded) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            store.saveSettings(_settings.value)
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    fun update(transform: (TanpuraSettings) -> TanpuraSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        engine.submit(next)
        // A sounding tuner tone follows the pitch settings, so dragging fine-tune
        // while holding a reference tone does what you would expect.
        _transport.value.referenceSemitone?.let { semitone ->
            engine.setReferenceTone(
                Pitch.frequencyFromSemitone(
                    semitone.toFloat(), next.saMidi, next.fineCents, next.a4Hz,
                )
            )
        }
        scheduleSave()
    }

    fun setMode(mode: EngineMode) {
        when (mode) {
            EngineMode.LOOP -> {
                val uri = _settings.value.loopUri
                if (uri == null) {
                    message("Pick a recording first, in Audio sources.")
                    return
                }
                update { it.copy(mode = mode) }
                attachLoop(Uri.parse(uri))
            }

            EngineMode.SAMPLES -> {
                if (_settings.value.samplePackUris.isEmpty()) {
                    message("Import string recordings first, in Audio sources.")
                    return
                }
                update { it.copy(mode = mode) }
                reloadSamplePack(_settings.value)
            }

            EngineMode.SYNTH -> {
                update { it.copy(mode = mode) }
                detachLoop()
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    fun play() {
        if (!requestFocus()) {
            message("Another app is using the audio output.")
            return
        }
        fadeGain = 1f
        applyExternalGain()
        engine.setPlaying(true)
        output.start()
        _transport.value = _transport.value.copy(isPlaying = true)
        onPlaybackStateChanged?.invoke(true)
    }

    fun pause() {
        engine.setPlaying(false)
        cancelTimer()
        abandonFocus()
        _transport.value = _transport.value.copy(isPlaying = false)
        onPlaybackStateChanged?.invoke(false)
        // The audio thread stops itself once the fade-out and reverb tail finish.
    }

    fun toggle() {
        if (_transport.value.isPlaying) pause() else play()
    }

    fun stop() {
        pause()
        scope.launch {
            delay(400)
            output.stop()
            engine.reset()
        }
    }

    private fun onAudioStoppedItself() {
        // Engine went idle and released the device; make sure the UI agrees.
        if (!engine.isPlaying) {
            _transport.value = _transport.value.copy(isPlaying = false)
            onPlaybackStateChanged?.invoke(false)
        }
    }

    // ------------------------------------------------------------------
    // Practice timer
    // ------------------------------------------------------------------

    fun startTimer(minutes: Int, fadeSeconds: Int) {
        val config = TimerConfig(minutes.coerceIn(1, 480), fadeSeconds.coerceIn(0, 120))
        _timerConfig.value = config
        scope.launch { store.saveTimer(config) }

        cancelTimer()
        val total = config.minutes * 60
        _transport.value = _transport.value.copy(
            timerTotalSeconds = total,
            timerRemainingSeconds = total,
            timerRunning = true,
        )
        if (!_transport.value.isPlaying) play()

        timerJob = scope.launch {
            var remaining = total
            while (remaining > 0) {
                delay(1000)
                remaining--
                fadeGain = if (config.fadeSeconds > 0 && remaining < config.fadeSeconds) {
                    remaining.toFloat() / config.fadeSeconds
                } else {
                    1f
                }
                applyExternalGain()
                _transport.value = _transport.value.copy(timerRemainingSeconds = remaining)
            }
            withContext(Dispatchers.Main) {
                _transport.value = _transport.value.copy(
                    timerRunning = false,
                    timerRemainingSeconds = 0,
                    message = "Practice timer finished.",
                )
            }
            pause()
            fadeGain = 1f
            applyExternalGain()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        fadeGain = 1f
        applyExternalGain()
        _transport.value = _transport.value.copy(timerRunning = false, timerRemainingSeconds = 0)
    }

    private fun applyExternalGain() {
        engine.externalGain = duckGain * fadeGain
    }

    // ------------------------------------------------------------------
    // Tuner reference tones
    // ------------------------------------------------------------------

    /** @param semitonesFromSa null silences the reference tone. */
    fun setReferenceTone(semitonesFromSa: Int?) {
        if (semitonesFromSa == null) {
            engine.setReferenceTone(null)
            _transport.value = _transport.value.copy(referenceSemitone = null)
            return
        }
        if (!requestFocus()) return
        val s = _settings.value
        val freq = Pitch.frequencyFromSemitone(
            semitonesFromSa.toFloat(), s.saMidi, s.fineCents, s.a4Hz,
        )
        engine.setReferenceTone(freq)
        output.start()
        _transport.value = _transport.value.copy(referenceSemitone = semitonesFromSa)
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    fun savePreset(name: String, nowMillis: Long) {
        val trimmed = name.trim().ifEmpty { "Untitled" }
        val preset = Preset(
            id = "user-$nowMillis",
            name = trimmed,
            settings = _settings.value,
            createdAtMillis = nowMillis,
        )
        val next = _presets.value + preset
        _presets.value = next
        scope.launch { store.savePresets(next) }
        message("Saved \"$trimmed\".")
    }

    fun applyPreset(preset: Preset) {
        // Keep the imported-audio wiring from the current session; a preset is
        // about pitch and tone, not about which files you happen to have.
        val current = _settings.value
        val merged = preset.settings.copy(
            samplePackUris = current.samplePackUris,
            samplePackNames = current.samplePackNames,
            samplePackBaseHz = current.samplePackBaseHz,
            loopUri = current.loopUri,
            loopName = current.loopName,
        )
        _settings.value = merged
        engine.submit(merged)
        scheduleSave()
    }

    fun deletePreset(id: String) {
        val next = _presets.value.filterNot { it.id == id }
        _presets.value = next
        scope.launch { store.savePresets(next) }
    }

    fun renamePreset(id: String, name: String) {
        val next = _presets.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        _presets.value = next
        scope.launch { store.savePresets(next) }
    }

    // ------------------------------------------------------------------
    // Imported audio
    // ------------------------------------------------------------------

    /** Imports one file per string for [EngineMode.SAMPLES]. */
    fun importSamplePack(uris: List<Uri>, names: List<String>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy(true)
            val baseHz = ArrayList<Float>(uris.size)
            var loadedCount = 0
            for ((index, uri) in uris.withIndex()) {
                if (index >= TanpuraEngine.MAX_STRINGS) break
                val decoded = AudioDecoder.decodeToMono(context, uri, maxSeconds = 20f)
                if (decoded == null) {
                    baseHz.add(0f)
                    continue
                }
                val detected = PitchDetector.detectInWindow(decoded.frames, decoded.sampleRate)
                    ?: 220f
                baseHz.add(detected)
                engine.setSampleData(
                    index,
                    SampleData(
                        frames = decoded.frames,
                        sampleRate = decoded.sampleRate,
                        baseFrequencyHz = detected,
                        name = names.getOrElse(index) { "String ${index + 1}" },
                    ),
                )
                loadedCount++
            }
            update {
                it.copy(
                    samplePackUris = uris.map(Uri::toString),
                    samplePackNames = names,
                    samplePackBaseHz = baseHz,
                    mode = if (loadedCount > 0) EngineMode.SAMPLES else it.mode,
                )
            }
            busy(false)
            message(
                if (loadedCount == 0) "Could not decode any of those files."
                else "Loaded $loadedCount string recording${if (loadedCount == 1) "" else "s"}."
            )
        }
    }

    private fun reloadSamplePack(s: TanpuraSettings) {
        if (s.samplePackUris.isEmpty()) return
        scope.launch {
            busy(true)
            for ((index, raw) in s.samplePackUris.withIndex()) {
                if (index >= TanpuraEngine.MAX_STRINGS) break
                val decoded = AudioDecoder.decodeToMono(context, Uri.parse(raw), maxSeconds = 20f)
                    ?: continue
                val base = s.samplePackBaseHz.getOrNull(index)?.takeIf { it > 0f }
                    ?: PitchDetector.detectInWindow(decoded.frames, decoded.sampleRate)
                    ?: 220f
                engine.setSampleData(
                    index,
                    SampleData(decoded.frames, decoded.sampleRate, base, s.samplePackNames.getOrElse(index) { "" }),
                )
            }
            engine.submit(_settings.value)
            busy(false)
        }
    }

    fun clearSamplePack() {
        engine.clearSampleData()
        update {
            it.copy(
                samplePackUris = emptyList(),
                samplePackNames = emptyList(),
                samplePackBaseHz = emptyList(),
                mode = if (it.mode == EngineMode.SAMPLES) EngineMode.SYNTH else it.mode,
            )
        }
    }

    /** Selects a long recording for [EngineMode.LOOP]. */
    fun importLoop(uri: Uri, displayName: String) {
        update { it.copy(loopUri = uri.toString(), loopName = displayName, mode = EngineMode.LOOP) }
        attachLoop(uri)
    }

    fun clearLoop() {
        detachLoop()
        update {
            it.copy(
                loopUri = null,
                loopName = null,
                mode = if (it.mode == EngineMode.LOOP) EngineMode.SYNTH else it.mode,
            )
        }
    }

    private fun attachLoop(uri: Uri) {
        detachLoop()
        _transport.value = _transport.value.copy(loopStatus = LoopStatus.LOADING)
        val source = MediaLoopSource(context, uri, engine.sampleRate)
        loopSourceRef = source
        engine.loopSource = source
        source.start()
        scope.launch {
            // Give the decoder a moment, then report what happened.
            repeat(40) {
                delay(100)
                if (source.ready) {
                    _transport.value = _transport.value.copy(loopStatus = LoopStatus.PLAYING)
                    return@launch
                }
                if (source.failed) {
                    _transport.value = _transport.value.copy(
                        loopStatus = LoopStatus.FAILED,
                        message = "That file could not be decoded.",
                    )
                    return@launch
                }
            }
            _transport.value = _transport.value.copy(loopStatus = LoopStatus.FAILED)
        }
    }

    private fun detachLoop() {
        engine.loopSource = EmptyLoopSource
        loopSourceRef?.release()
        loopSourceRef = null
        _transport.value = _transport.value.copy(loopStatus = LoopStatus.NONE)
    }

    // ------------------------------------------------------------------
    // Audio focus
    // ------------------------------------------------------------------

    private fun requestFocus(): Boolean {
        focusRequest?.let { return true }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
            .build()
        val result = try {
            audioManager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            true
        } else {
            false
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            Log.w(TAG, "Abandoning audio focus failed", e)
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = false
                focusRequest = null
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_transport.value.isPlaying) {
                    pausedByFocusLoss = true
                    engine.setPlaying(false)
                    _transport.value = _transport.value.copy(isPlaying = false)
                    onPlaybackStateChanged?.invoke(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckGain = 0.25f
                applyExternalGain()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                duckGain = 1f
                applyExternalGain()
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    engine.setPlaying(true)
                    output.start()
                    _transport.value = _transport.value.copy(isPlaying = true)
                    onPlaybackStateChanged?.invoke(true)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    fun message(text: String?) {
        _transport.value = _transport.value.copy(message = text)
    }

    fun clearMessage() {
        if (_transport.value.message != null) message(null)
    }

    private fun busy(value: Boolean) {
        _transport.value = _transport.value.copy(busy = value)
    }

    fun release() {
        cancelTimer()
        detachLoop()
        output.stop()
        abandonFocus()
    }
}
