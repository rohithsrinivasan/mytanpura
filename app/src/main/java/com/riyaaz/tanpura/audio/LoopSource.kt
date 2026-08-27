package com.riyaaz.tanpura.audio

/**
 * A continuously looping mono audio source, used by [EngineMode.LOOP] to play a
 * long recording the user imported. Implementations stream from disk rather than
 * decoding into memory, because these files can be hours long.
 */
interface LoopSource {

    /** True once enough audio is buffered to start playing without a dropout. */
    val ready: Boolean

    /** Underrun count, exposed so the UI can warn about a file it cannot keep up with. */
    val underruns: Int

    /**
     * Writes [frames] mono samples into [out] starting at [offset].
     * @param rate playback speed multiplier; 1.0 = original pitch and tempo.
     */
    fun read(out: FloatArray, offset: Int, frames: Int, rate: Float)

    fun release()
}

/** Stand-in used whenever loop mode is selected but no file is loaded. */
object EmptyLoopSource : LoopSource {
    override val ready: Boolean = false
    override val underruns: Int = 0
    override fun read(out: FloatArray, offset: Int, frames: Int, rate: Float) = Unit
    override fun release() = Unit
}
