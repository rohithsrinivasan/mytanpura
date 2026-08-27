package com.riyaaz.tanpura.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM decoded from a user-supplied file, downmixed to mono float. */
class DecodedAudio(
    val frames: FloatArray,
    val sampleRate: Int,
    val truncated: Boolean,
)

/**
 * Converts decoder output buffers to mono float. One instance per decoding
 * thread; it owns its scratch buffer so nothing is shared.
 */
class PcmConverter {

    var buffer: FloatArray = FloatArray(8192)
        private set

    /** Number of valid mono frames in [buffer] after the last [convert]. */
    var count: Int = 0
        private set

    fun convert(buf: ByteBuffer, pcmEncoding: Int, channels: Int) {
        val ch = channels.coerceAtLeast(1)
        val src = buf.order(ByteOrder.LITTLE_ENDIAN)

        val frames = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> src.remaining() / 4 / ch
            AudioFormat.ENCODING_PCM_8BIT -> src.remaining() / ch
            else -> src.remaining() / 2 / ch
        }
        if (frames <= 0) {
            count = 0
            return
        }
        ensure(frames)
        val out = buffer

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = src.asFloatBuffer()
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get()
                    out[i] = sum / ch
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += ((src.get().toInt() and 0xFF) - 128) / 128f
                    out[i] = sum / ch
                }
            }

            else -> {
                val sb = src.asShortBuffer()
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get() / 32768f
                    out[i] = sum / ch
                }
            }
        }
        count = frames
    }

    private fun ensure(size: Int) {
        if (buffer.size < size) buffer = FloatArray(size)
    }
}

/**
 * Decodes any audio format the device can play (m4a, mp3, ogg, wav, flac...) into
 * mono float PCM, for short per-string sample files.
 *
 * Long recordings go through `MediaLoopSource` instead, which streams rather than
 * filling the heap.
 */
object AudioDecoder {

    private const val TAG = "TanpuraDecoder"
    private const val TIMEOUT_US = 10_000L

    /**
     * @param maxSeconds hard cap, so picking a three-hour file by mistake cannot
     * exhaust memory. Anything longer is truncated and flagged.
     */
    fun decodeToMono(context: Context, uri: Uri, maxSeconds: Float = 20f): DecodedAudio? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = findAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var outRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var outChannels = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = inputFormat.optInt(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            var capacity = (outRate * maxSeconds).toInt().coerceAtLeast(1024)
            var mono = FloatArray(minOf(capacity, outRate * 3))
            var monoLen = 0
            var truncated = false

            val converter = PcmConverter()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
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
                    outRate = f.optInt(MediaFormat.KEY_SAMPLE_RATE, outRate)
                    outChannels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, outChannels)
                    pcmEncoding = f.optInt(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    capacity = (outRate * maxSeconds).toInt().coerceAtLeast(1024)
                } else if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        converter.convert(buf, pcmEncoding, outChannels)
                        val n = converter.count
                        val room = capacity - monoLen
                        if (n > room) truncated = true
                        val toCopy = minOf(n, room)
                        if (toCopy > 0) {
                            if (monoLen + toCopy > mono.size) {
                                val newSize = minOf(capacity, maxOf(mono.size * 2, monoLen + toCopy))
                                mono = mono.copyOf(newSize)
                            }
                            System.arraycopy(converter.buffer, 0, mono, monoLen, toCopy)
                            monoLen += toCopy
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (monoLen >= capacity) outputDone = true
                }
            }

            if (monoLen == 0) return null
            return DecodedAudio(mono.copyOf(monoLen), outRate, truncated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode $uri", e)
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
                // Codec was never started or is already dead.
            }
            codec?.release()
            extractor?.release()
        }
    }

    fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}

/** [MediaFormat.getInteger] with a default, because these keys are often absent. */
fun MediaFormat.optInt(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default
