package com.riyaaz.tanpura.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/**
 * Streams a (possibly very long) audio file and loops it forever.
 *
 * The decoder runs on its own thread and fills a several-second ring buffer; the
 * audio thread reads from the ring with linear-interpolated resampling, which is
 * also how the pitch offset is applied. Nothing about the file is held in memory
 * beyond that ring, so a three-hour recording costs the same as a three-second
 * one.
 *
 * At the loop point the stream is torn down and reopened rather than flushed,
 * because `flush()`-after-EOS behaviour varies between devices, and reopening
 * costs a few tens of milliseconds hidden behind a full ring buffer.
 */
class MediaLoopSource(
    private val context: Context,
    private val uri: Uri,
    private val engineRate: Int,
) : LoopSource {

    private companion object {
        const val TAG = "TanpuraLoop"
        const val TIMEOUT_US = 10_000L
        const val FADE_FRAMES = 2048
        const val RING_SECONDS = 6
    }

    private val ring = PcmRing(engineRate * RING_SECONDS)

    @Volatile
    private var nativeRate: Int = engineRate

    @Volatile
    private var running = false

    @Volatile
    private var prefilled = false

    @Volatile
    override var underruns: Int = 0
        private set

    /** Set when the file cannot be opened at all, so the UI can say so. */
    @Volatile
    var failed: Boolean = false
        private set

    @Volatile
    var durationSeconds: Float = 0f
        private set

    private var thread: Thread? = null

    // Consumer-side resampler state (audio thread only).
    private var prev = 0f
    private var cur = 0f
    private var frac = 0.0

    override val ready: Boolean
        get() = prefilled && !failed

    fun start() {
        if (running) return
        running = true
        val t = Thread({ decodeLoop() }, "tanpura-loop-decoder")
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun release() {
        running = false
        thread?.let {
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    override fun read(out: FloatArray, offset: Int, frames: Int, rate: Float) {
        if (!prefilled) {
            underruns++
            return
        }
        // Combine the pitch shift with sample-rate conversion.
        val step = (rate.toDouble() * nativeRate / engineRate).coerceIn(0.05, 8.0)
        var starved = false
        for (i in 0 until frames) {
            out[offset + i] += prev + (cur - prev) * frac.toFloat()
            frac += step
            while (frac >= 1.0) {
                prev = cur
                val next = ring.readOne(Float.NaN)
                if (next.isNaN()) {
                    starved = true
                    // Hold the last sample rather than emitting a click.
                    cur = prev
                } else {
                    cur = next
                }
                frac -= 1.0
            }
        }
        if (starved) underruns++
    }

    private fun decodeLoop() {
        val converter = PcmConverter()
        var emptyPasses = 0
        while (running) {
            val emitted = openAndPump(converter)
            if (emitted < 0) {
                failed = true
                prefilled = false
                return
            }
            // Guard against a zero-length or unreadable file spinning this thread.
            if (emitted < 1024) {
                emptyPasses++
                if (emptyPasses >= 3) {
                    failed = true
                    prefilled = false
                    return
                }
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } else {
                emptyPasses = 0
            }
            // Reached the end of the file: go round again.
        }
    }

    /**
     * Opens the file, decodes it to the end while feeding the ring, then closes.
     * @return frames emitted, or -1 if the file could not be opened.
     */
    private fun openAndPump(converter: PcmConverter): Long {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var emitted = 0L
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = AudioDecoder.findAudioTrack(extractor) ?: return -1L
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return -1L

            var rate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = format.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            nativeRate = rate

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            durationSeconds = durationUs / 1_000_000f
            var totalFrames = if (durationUs > 0) (durationUs * rate / 1_000_000L) else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false

            while (running) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val size = if (inBuf == null) -1 else extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.optInt(MediaFormat.KEY_SAMPLE_RATE, rate)
                    channels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    pcmEncoding = f.optInt(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    nativeRate = rate
                    if (durationUs > 0) totalFrames = durationUs * rate / 1_000_000L
                } else if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        converter.convert(buf, pcmEncoding, channels)
                        applySeamFade(converter, emitted, totalFrames)
                        emitted += converter.count
                        feedRing(converter.buffer, converter.count)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return emitted
        } catch (e: Exception) {
            Log.e(TAG, "Loop decode failed for $uri", e)
            return -1L
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // Already dead.
            }
            codec?.release()
            extractor?.release()
        }
    }

    /**
     * Fades the first and last few thousand frames of each pass so the loop seam
     * is a brief dip rather than a click.
     */
    private fun applySeamFade(
        converter: PcmConverter,
        emittedBefore: Long,
        totalFrames: Long,
    ) {
        val buf = converter.buffer
        val n = converter.count
        for (i in 0 until n) {
            val pos = emittedBefore + i
            var g = 1f
            if (pos < FADE_FRAMES) {
                g *= pos.toFloat() / FADE_FRAMES
            }
            if (totalFrames > FADE_FRAMES) {
                val remaining = totalFrames - pos
                if (remaining in 0 until FADE_FRAMES.toLong()) {
                    g *= remaining.toFloat() / FADE_FRAMES
                }
            }
            if (g != 1f) buf[i] *= g
        }
    }

    /** Blocks (politely) until the ring has room, so we never drop audio. */
    private fun feedRing(src: FloatArray, count: Int) {
        var written = 0
        while (written < count && running) {
            val n = ring.write(src, written, count - written)
            written += n
            if (n == 0) {
                try {
                    Thread.sleep(4)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (!prefilled && ring.available() > engineRate / 3) prefilled = true
        }
    }
}
