package com.whispertype.android.core.model

/**
 * One immutable audio frame in the pipeline. Chunks are derived from the
 * sample rate and a 20 ms frame duration, never from a magic byte count.
 */
class AudioChunk(
    val sequence: Long,
    val pcm16Bytes: ByteArray,
    val sampleRateHz: Int,
    val frameMillis: Int = 20,
) {
    /** Expected bytes per frame: frameMillis / 1000 * sampleRate * 16-bit (2 bytes). */
    val expectedByteCount: Int get() = frameMillis * sampleRateHz * CHAR_BYTES / 1000
    val byteCount: Int get() = pcm16Bytes.size

    override fun equals(other: Any?): Boolean =
        other is AudioChunk && other.sequence == sequence && other.frameMillis == frameMillis &&
            other.sampleRateHz == sampleRateHz && other.byteCount == byteCount

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + frameMillis
        result = 31 * result + sampleRateHz
        result = 31 * result + byteCount
        return result
    }

    private companion object {
        const val CHAR_BYTES = 2
    }
}