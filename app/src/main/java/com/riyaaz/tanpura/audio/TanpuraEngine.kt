package com.riyaaz.tanpura.audio

import com.riyaaz.tanpura.model.EngineMode
import com.riyaaz.tanpura.model.Pitch
import com.riyaaz.tanpura.model.TanpuraSettings
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * The whole signal chain, driven from one audio thread.
 *
 * Threading contract:
 *  - [render] runs on the audio thread and never allocates, blocks or locks.
 *  - Everything else is called from the UI/service thread. Parameter changes are
 *    published through an [AtomicReference] and picked up at the next block
 *    boundary, so a slider drag can never tear a coefficient set.
 */
class TanpuraEngine(val sampleRate: Int) {

    companion object {
        const val MAX_STRINGS = StrumSequencer.MAX_STRINGS
        private const val LIMIT_KNEE = 0.90f
    }

    private val strings = Array(MAX_STRINGS) { StringVoice(sampleRate) }
    private val samplers = Array(MAX_STRINGS) { SampleVoice(sampleRate) }
    private val sequencer = StrumSequencer(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val refTone = RefTone(sampleRate)

    private val bodyPeaks = Array(4) { Biquad() }
    private var activeBodyPeaks = 0
    private val highPass = Biquad()
    private val lowShelf = Biquad()

    private val monoBus = FloatArray(Dsp.CONTROL_BLOCK)

    private val pending = AtomicReference<TanpuraSettings?>(null)
    private var settings = TanpuraSettings()
    private var appliedMode = EngineMode.SYNTH
    private var stringCount = 4
    private var loopRate = 1f

    private val masterSm = Smoothed(0f, Dsp.smoothingCoef(0.22f, sampleRate))

    /**
     * True once a pause has faded all the way out and the voices have been
     * silenced. Until then the strings keep ringing (inaudibly) so that an
     * immediate un-pause resumes rather than restarts.
     */
    private var quiesced = true

    @Volatile
    private var playRequested = false

    /**
     * Extra gain multiplier owned by the controller, used for audio-focus ducking
     * and the practice timer's fade-out. Kept separate from the user's volume so
     * neither one clobbers the other.
     */
    @Volatile
    var externalGain: Float = 1f

    @Volatile
    var loopSource: LoopSource = EmptyLoopSource

    // ---- UI feedback (written on the audio thread, read on the UI thread) ----
    @Volatile
    var outputLevel: Float = 0f
        private set

    @Volatile
    var strumPosition: Float = 0f
        private set

    private val stringFlash = FloatArray(MAX_STRINGS)

    /** 0..1 "this string was just struck" for the animated instrument view. */
    fun stringActivity(index: Int): Float =
        if (index in 0 until MAX_STRINGS) stringFlash[index] else 0f

    val activeStringCount: Int get() = stringCount

    /** True when the drone has fully faded out and nothing is ringing. */
    val isIdle: Boolean
        get() = !playRequested &&
            masterSm.value < 1e-4f &&
            !refTone.isSounding &&
            strings.none { it.isRinging } &&
            samplers.none { it.isRinging }

    /** True when the audio device needs to stay open. */
    val needsAudio: Boolean get() = playRequested || !isIdle

    init {
        applySettings(settings, force = true)
    }

    // ------------------------------------------------------------------
    // Control-thread API
    // ------------------------------------------------------------------

    /** Publishes new settings; picked up by the audio thread at the next block. */
    fun submit(newSettings: TanpuraSettings) {
        pending.set(newSettings)
    }

    /** Snapshot of the settings the audio thread is currently rendering with. */
    fun currentSettings(): TanpuraSettings = settings

    fun setPlaying(playing: Boolean) {
        playRequested = playing
        if (playing) {
            // Start the strum from the top so the first string you hear is string 1.
            sequencer.reset(startImmediately = true)
        }
    }

    val isPlaying: Boolean get() = playRequested

    fun setSampleData(index: Int, data: SampleData?) {
        if (index in 0 until MAX_STRINGS) samplers[index].data = data
    }

    fun clearSampleData() {
        for (s in samplers) s.data = null
    }

    fun hasSampleData(): Boolean = samplers.any { it.data != null }

    fun setReferenceTone(freqHz: Float?) {
        if (freqHz == null) {
            refTone.noteOff()
        } else {
            refTone.setFrequency(freqHz)
            refTone.noteOn()
        }
    }

    fun reset() {
        for (s in strings) s.reset()
        for (s in samplers) s.silence()
        reverb.clear()
        refTone.reset()
        masterSm.snap(0f)
        sequencer.reset(startImmediately = true)
        stringFlash.fill(0f)
    }

    // ------------------------------------------------------------------
    // Audio thread
    // ------------------------------------------------------------------

    /**
     * Renders [frames] interleaved stereo frames into [out] (so it writes
     * `frames * 2` floats). Overwrites, does not accumulate.
     */
    fun render(out: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { applySettings(it) }

        var done = 0
        var peak = 0f
        val refLevel = settings.masterVolume

        while (done < frames) {
            val n = min(Dsp.CONTROL_BLOCK, frames - done)
            java.util.Arrays.fill(monoBus, 0, n, 0f)

            if (playRequested) {
                quiesced = false
            } else if (!quiesced && masterSm.value < 1e-4f) {
                // The fade-out has finished. Free the voices so the engine can
                // report itself idle and the audio device can be released.
                for (s in strings) s.reset()
                for (s in samplers) s.silence()
                quiesced = true
            }

            if (quiesced) {
                // Voices are asleep. The reverb tail and the tuner tone still play.
                val q = finishBlock(out, done, n, refLevel)
                if (q > peak) peak = q
                done += n
                continue
            }

            when (appliedMode) {
                EngineMode.SYNTH -> {
                    sequencer.advance(n) { idx, vel ->
                        strings[idx].pluck(vel)
                        stringFlash[idx] = 1f
                    }
                    for (i in 0 until stringCount) strings[i].render(monoBus, 0, n)
                    applyBody(n)
                }

                EngineMode.SAMPLES -> {
                    sequencer.advance(n) { idx, vel ->
                        samplers[idx].pluck(vel)
                        stringFlash[idx] = 1f
                    }
                    for (i in 0 until stringCount) samplers[i].render(monoBus, 0, n)
                }

                EngineMode.LOOP -> {
                    loopSource.read(monoBus, 0, n, loopRate)
                }
            }

            val blockPeak = finishBlock(out, done, n, refLevel)
            if (blockPeak > peak) peak = blockPeak

            // Visual decay for the string-pluck animation.
            for (s in stringFlash.indices) stringFlash[s] *= 0.995f

            done += n
        }

        strumPosition = sequencer.cyclePosition()
        outputLevel = outputLevel * 0.7f + peak * 0.3f
    }

    /**
     * Master fade, tuner tone, reverb and limiter for one control block.
     *
     * The master gain is applied to the dry bus *before* the reverb, so pausing
     * fades the room out with the strings instead of leaving a tail hanging.
     *
     * @return the peak absolute sample in this block.
     */
    private fun finishBlock(out: FloatArray, frameOffset: Int, n: Int, refLevel: Float): Float {
        val ext = Dsp.clamp01(externalGain)
        masterSm.target = if (playRequested) settings.masterVolume * ext else 0f
        for (i in 0 until n) monoBus[i] *= masterSm.step()

        // The tuner tone bypasses the drone fade so it works while paused.
        refTone.render(monoBus, 0, n, refLevel)

        reverb.process(monoBus, n, out, frameOffset * 2)

        // Gentle limiter: transparent below the knee, soft above it.
        var peak = 0f
        val end = (frameOffset + n) * 2
        var i = frameOffset * 2
        while (i < end) {
            var v = out[i]
            if (v > LIMIT_KNEE || v < -LIMIT_KNEE) {
                val sign = if (v < 0f) -1f else 1f
                val over = abs(v) - LIMIT_KNEE
                v = sign * (LIMIT_KNEE + (1f - LIMIT_KNEE) * Dsp.fastTanh(over / (1f - LIMIT_KNEE)))
            }
            if (v.isNaN()) v = 0f
            out[i] = v
            val a = abs(v)
            if (a > peak) peak = a
            i++
        }
        return peak
    }

    private fun applyBody(n: Int) {
        for (i in 0 until n) {
            var v = highPass.process(monoBus[i])
            v = lowShelf.process(v)
            for (p in 0 until activeBodyPeaks) v = bodyPeaks[p].process(v)
            monoBus[i] = v
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private fun applySettings(s: TanpuraSettings, force: Boolean = false) {
        val previous = settings
        settings = s

        val voice = s.voice
        val pattern = s.pattern
        val count = pattern.stringCount.coerceIn(1, MAX_STRINGS)

        // Fall back to the built-in synth if samples were selected but never loaded.
        val mode = if (s.mode == EngineMode.SAMPLES && !hasSampleData()) EngineMode.SYNTH else s.mode

        val modeChanged = force || mode != appliedMode
        if (modeChanged) {
            when (appliedMode) {
                EngineMode.SYNTH -> strings.forEach { it.reset() }
                EngineMode.SAMPLES -> samplers.forEach { it.silence() }
                EngineMode.LOOP -> Unit
            }
            appliedMode = mode
        }

        val countChanged = force || count != stringCount
        if (countChanged) {
            for (i in count until MAX_STRINGS) {
                strings[i].reset()
                samplers[i].silence()
            }
        }
        stringCount = count

        val brightness = Dsp.clamp01(voice.brightness + s.brightnessTrim * 0.35f)
        val jawari = Dsp.clamp01(voice.jawari + s.jawariTrim * 0.40f)
        val decayScale = s.decayScale.coerceIn(0.4f, 2.5f)
        val velocities = voice.perString(voice.stringVelocities, count)
        val voiceGains = voice.perString(voice.stringGains, count)
        val decays = voice.perString(voice.stringDecayScale, count)

        for (i in 0 until count) {
            val semis = pattern.semitoneOffsets[i].toFloat()
            val freq = Pitch.frequencyFromSemitone(semis, s.saMidi, s.fineCents, s.a4Hz)
            val glide = !force && !modeChanged && sameTuningShape(previous, s)

            strings[i].setCharacter(
                brightness = brightness,
                decaySeconds = voice.decaySeconds * decayScale * decays[i],
                jawari = jawari,
                pluckPosition = voice.pluckPosition,
                tension = voice.tension,
            )
            strings[i].setFrequency(freq, glide = glide)
            samplers[i].setFrequency(freq)

            val g = voiceGains[i] * s.stringGain(i)
            strings[i].gain = g
            samplers[i].gain = g
            sequencer.baseVelocities[i] = velocities[i]
        }

        sequencer.stringCount = count
        sequencer.cycleSeconds = s.cycleSeconds
        sequencer.humanize = s.humanize

        if (force || previous.voiceId != s.voiceId) {
            activeBodyPeaks = voice.bodyPeaks.size.coerceAtMost(bodyPeaks.size)
            for (i in 0 until activeBodyPeaks) {
                val p = voice.bodyPeaks[i]
                bodyPeaks[i].setPeaking(p.freqHz, p.q, p.gainDb, sampleRate)
            }
            highPass.setHighPass(voice.highPassHz, 0.707f, sampleRate)
            lowShelf.setLowShelf(180f, voice.lowShelfDb, sampleRate)
            reverb.setRoom(voice.reverbSize, 0.3f)
        }

        reverb.setMix(s.effectiveReverbMix)
        loopRate = 2f.pow(s.loopPitchCents / 1200f).coerceIn(0.25f, 4f)
    }

    /** True when only pitch changed, so strings should glide instead of restarting. */
    private fun sameTuningShape(a: TanpuraSettings, b: TanpuraSettings): Boolean =
        a.patternId == b.patternId && a.voiceId == b.voiceId
}
