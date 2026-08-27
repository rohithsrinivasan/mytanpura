package com.riyaaz.tanpura.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Owns the [AudioTrack] and the audio thread that pulls from [TanpuraEngine].
 *
 * A drone does not need low latency, it needs to never glitch. So the render
 * block is two device bursts (at least 256 frames), the AudioTrack buffer is
 * several blocks deep, and the thread runs at urgent-audio priority.
 */
class AudioOutput(
    private val engine: TanpuraEngine,
    private val onStoppedNaturally: () -> Unit = {},
) {

    companion object {
        private const val TAG = "TanpuraAudio"
        private const val MIN_BLOCK_FRAMES = 256

        /** The device's preferred output rate; falls back to 48 kHz. */
        fun preferredSampleRate(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val raw = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            return when (raw) {
                null, 0 -> 48_000
                else -> raw.coerceIn(22_050, 96_000)
            }
        }

        /** The device's native burst size, used to size our render block. */
        fun preferredBurstFrames(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val raw = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            return (raw ?: 256).coerceIn(64, 2048)
        }
    }

    private var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    var isRunning: Boolean = false
        private set

    private var blockFrames = 512

    fun configure(context: Context) {
        blockFrames = (preferredBurstFrames(context) * 2).coerceAtLeast(MIN_BLOCK_FRAMES)
    }

    @Synchronized
    fun start() {
        // The audio thread releases the device by itself once the engine goes
        // idle, so "already started" is not the same as "still alive".
        val existing = thread
        if (existing != null && existing.isAlive && !stopRequested) return
        if (existing != null) {
            try {
                existing.join(800)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        stopRequested = false
        isRunning = true
        val t = Thread({ runLoop() }, "tanpura-audio")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        val t = thread ?: return
        thread = null
        try {
            t.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        isRunning = false
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val frames = blockFrames
        val floatsPerWrite = frames * 2
        val buffer = FloatArray(floatsPerWrite)

        val minBytes = AudioTrack.getMinBufferSize(
            engine.sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(floatsPerWrite * 4)
        val bufferBytes = (minBytes * 2).coerceAtLeast(floatsPerWrite * 4 * 3)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(engine.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open AudioTrack", e)
            isRunning = false
            return
        }

        try {
            track.play()
            // Pre-roll one buffer of silence so the very first strum is not clipped
            // by the device warming up.
            java.util.Arrays.fill(buffer, 0f)
            track.write(buffer, 0, floatsPerWrite, AudioTrack.WRITE_BLOCKING)

            var idleBlocks = 0
            while (!stopRequested) {
                engine.render(buffer, frames)
                val written = track.write(buffer, 0, floatsPerWrite, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write failed: $written")
                    break
                }
                // Once the engine has fully faded out, keep going briefly (so the
                // reverb tail lands) and then release the device.
                if (!engine.needsAudio) {
                    idleBlocks++
                    if (idleBlocks * frames > engine.sampleRate * 2) break
                } else {
                    idleBlocks = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio thread died", e)
        } finally {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: Exception) {
                // Already in a bad state; nothing useful to do.
            }
            track.release()
            isRunning = false
            if (!stopRequested) onStoppedNaturally()
        }
    }
}
