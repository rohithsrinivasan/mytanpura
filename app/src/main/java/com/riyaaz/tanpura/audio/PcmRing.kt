package com.riyaaz.tanpura.audio

/**
 * Single-producer / single-consumer float ring buffer.
 *
 * The decoder thread writes, the audio thread reads, and neither ever blocks or
 * allocates. Capacity is rounded up to a power of two so the index wrap is a mask.
 */
class PcmRing(requestedCapacity: Int) {

    private val capacity: Int = Integer.highestOneBit(requestedCapacity.coerceAtLeast(2) - 1) * 2
    private val mask: Int = capacity - 1
    private val buf = FloatArray(capacity)

    @Volatile
    private var writeIdx = 0L

    @Volatile
    private var readIdx = 0L

    fun capacity(): Int = capacity

    fun available(): Int = (writeIdx - readIdx).toInt().coerceAtLeast(0)

    fun space(): Int = capacity - available()

    fun clear() {
        readIdx = 0L
        writeIdx = 0L
        buf.fill(0f)
    }

    /** Producer side. Returns the number of floats actually written. */
    fun write(src: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, space())
        var w = writeIdx
        for (i in 0 until n) {
            buf[(w and mask.toLong()).toInt()] = src[offset + i]
            w++
        }
        writeIdx = w
        return n
    }

    /** Consumer side. Returns the number of floats actually read. */
    fun read(dst: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, available())
        var r = readIdx
        for (i in 0 until n) {
            dst[offset + i] = buf[(r and mask.toLong()).toInt()]
            r++
        }
        readIdx = r
        return n
    }

    /** Consumer side, one float. Returns [fallback] on underrun. */
    fun readOne(fallback: Float): Float {
        if (writeIdx - readIdx <= 0) return fallback
        val v = buf[(readIdx and mask.toLong()).toInt()]
        readIdx++
        return v
    }
}
