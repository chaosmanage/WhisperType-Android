package com.whispertype.android.core.audio

/**
 * 0.7.0: byte transport for the PCA-reduced WebRTC VAD feature matrix used by
 * the Groq dialoging audio path. Encodes a `frames x features` float matrix
 * (the pickle contents of the live VAD pickle) as a compact binary payload.
 *
 * Layout: magic (4 bytes) | version (1) | frames (u16) | features (u16) |
 * float32 LE samples (features * frames).
 *
 * The feature matrix is tiny (tens of bytes per 30 ms frame), so no
 * compression layer is applied. Pure JVM (java.nio) so it stays unit-testable
 * in core/.
 */
class AudioPickleCodec(
    private val magic: ByteArray = MAGIC,
    private val version: Byte = VERSION,
) {

    /**
     * Encodes [frames] rows of [frameFeatureCount] feature values into a
     * single payload usable as a WebSocket binary message. Rows wider than
     * the declared [frameFeatureCount] are truncated to it; every row must
     * carry at least one sample.
     */
    fun encode(frames: Array<FloatArray>, frameFeatureCount: Int): ByteArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        val count = minOf(frames[0].size, frameFeatureCount)
        val capacity = HEADER_SIZE + frames.size * count * 4
        val buffer = java.nio.ByteBuffer.allocate(capacity).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic)
        buffer.put(version)
        buffer.putShort(frames.size.toShort())
        buffer.putShort(count.toShort())
        val row = FloatArray(count)
        frames.forEach { frame ->
            for (i in 0 until count) row[i] = frame.getOrElse(i) { 0f }
            buffer.asFloatBuffer().put(row)
            buffer.position(buffer.position() + count * 4)
        }
        return buffer.array()
    }

    /**
     * Decodes a payload produced by [encode]. Returns null when the payload
     * does not match the header (bad magic, unsupported version, malformed
     * length) so a corrupt or foreign frame is never interpreted.
     */
    fun decode(payload: ByteArray): PickledAudioFrame? {
        if (payload.size < HEADER_SIZE) return null
        val buffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val readMagic = ByteArray(magic.size)
        buffer.get(readMagic)
        if (!readMagic.contentEquals(magic)) return null
        if (buffer.get().toInt() != version.toInt()) return null
        val frameCount = buffer.getShort().toInt() and MAX_U16
        val features = buffer.getShort().toInt() and MAX_U16
        if (frameCount <= 0 || features <= 0) return null
        val expected = HEADER_SIZE + frameCount * features * 4
        if (payload.size != expected) return null
        val matrix = Array(frameCount) { FloatArray(features) }
        val row = FloatArray(features)
        for (frameIndex in 0 until frameCount) {
            buffer.asFloatBuffer().get(row)
            buffer.position(buffer.position() + features * 4)
            row.copyInto(matrix[frameIndex])
        }
        return PickledAudioFrame(matrix, frameFeatureCount = features)
    }

    companion object {
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val VERSION: Byte = 1
        private const val MAX_U16 = 0xFFFF
        val HEADER_SIZE: Int = 4 + 1 + 2 + 2
        private val singleton = AudioPickleCodec()

        /** Stateless convenience: encode with the default header. */
        fun encode(frames: Array<FloatArray>, frameFeatureCount: Int): ByteArray =
            singleton.encode(frames, frameFeatureCount)

        /** Stateless convenience: decode with the default header. */
        fun decode(payload: ByteArray): PickledAudioFrame? = singleton.decode(payload)
    }
}

/** 0.7.0: a decoded frame-of-reference feature matrix (the VAD pickle). */
data class PickledAudioFrame(
    val frames: Array<FloatArray>,
    val frameFeatureCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickledAudioFrame) return false
        return frameFeatureCount == other.frameFeatureCount && frames.contentDeepEquals(other.frames)
    }

    override fun hashCode(): Int = frames.contentDeepHashCode() * 31 + frameFeatureCount
}