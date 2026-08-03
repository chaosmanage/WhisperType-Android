package com.whispertype.android.audio

/**
 * One 20 ms chunk of mono PCM16 audio.
 * [sequenceNumber] is monotonically increasing per capture session.
 */
data class AudioChunk(
    val sequenceNumber: Long,
    val pcm16Bytes: ByteArray,
    val capturedAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sequenceNumber == other.sequenceNumber &&
            capturedAtMillis == other.capturedAtMillis &&
            pcm16Bytes.contentEquals(other.pcm16Bytes)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + pcm16Bytes.contentHashCode()
        result = 31 * result + capturedAtMillis.hashCode()
        return result
    }
}
