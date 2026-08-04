package com.whispertype.android.core.audio

import com.whispertype.android.core.model.AudioChunk

/**
 * Host-testable PCM16 frame assembler (PRD FR-5).
 *
 * Frames raw mono 16-bit PCM bytes into exact [frameMillis]-millisecond chunks,
 * handling partial reads. The chunk size is derived from the sample rate and the
 * frame duration — `bytesPerFrame = frameMillis * sampleRateHz * 2 / 1000` — never
 * from a magic byte count.
 *
 * The producer [push]es arbitrary-size byte arrays (simulating partial reads from an
 * [AudioRecord]); the assembler copies each input, accumulates the leftover, and emits
 * exactly one [AudioChunk] per complete frame, with a monotonically increasing
 * [AudioChunk.sequence] starting at 0.
 *
 * Remainder handling: [drainRemainder] emits any leftover (< one full frame) as a final,
 * undersized [AudioChunk] and returns `null` when there is no remainder. The caller is
 * expected to follow such a final short chunk with the single activity-end boundary.
 */
class Pcm16FrameAssembler(
    /** Sample rate in Hz. Defaults to 24000 Hz (a Gemini Live supported rate). */
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    /** Frame duration in milliseconds. Defaults to 20 ms per FR-5. */
    val frameMillis: Int = DEFAULT_FRAME_MILLIS,
) {
    /**
     * Bytes per full frame: `frameMillis / 1000 * sampleRate * 16-bit(2 bytes)`.
     * Derived from configuration, never a magic byte count.
     */
    val expectedBytesPerFrame: Int = frameMillis * sampleRateHz * CHAR_BYTES / 1000

    init {
        require(expectedBytesPerFrame > 0) {
            "expectedBytesPerFrame must be positive, got $expectedBytesPerFrame " +
                "(sampleRateHz=$sampleRateHz, frameMillis=$frameMillis)"
        }
    }

    private var store = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var sequence = 0L

    /** Number of accumulated bytes not yet emitted as a full frame. */
    val pendingByteCount: Int get() = size

    /**
     * Feeds [bytes] (which is copied, never retained) into the assembler and returns the
     * zero or more complete [AudioChunk]s produced. Chunks are emitted in order with
     * monotonic, zero-based [AudioChunk.sequence].
     */
    fun push(bytes: ByteArray): List<AudioChunk> {
        if (bytes.isEmpty()) return emptyList()
        ensureCapacity(bytes.size)
        bytes.copyInto(store, destinationOffset = size)
        size += bytes.size

        val bpe = expectedBytesPerFrame
        val chunks = ArrayList<AudioChunk>()
        while (size >= bpe) {
            val frame = ByteArray(bpe)
            store.copyInto(frame, destinationOffset = 0, startIndex = 0, endIndex = bpe)
            // Shift the unconsumed bytes to the front, reusing the buffer.
            store.copyInto(store, destinationOffset = 0, startIndex = bpe, endIndex = size)
            size -= bpe
            chunks.add(AudioChunk(sequence = sequence++, pcm16Bytes = frame, sampleRateHz = sampleRateHz, frameMillis = frameMillis))
        }
        return chunks
    }

    /**
     * Drains any accumulated remainder smaller than one full frame and emits it as a final
     * undersized [AudioChunk]. Returns `null` when there is no remainder. The caller should
     * follow a returned tail chunk with the single activity-end boundary.
     */
    fun drainRemainder(): AudioChunk? {
        if (size == 0) return null
        val tail = store.copyOfRange(0, size)
        size = 0
        return AudioChunk(sequence = sequence++, pcm16Bytes = tail, sampleRateHz = sampleRateHz, frameMillis = frameMillis)
    }

    private fun ensureCapacity(extra: Int) {
        val needed = size + extra
        if (needed <= store.size) return
        var capacity = store.size
        while (capacity < needed) capacity = capacity * 2 + 1
        store = store.copyOf(capacity)
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        const val DEFAULT_FRAME_MILLIS = 20
        const val CHAR_BYTES = 2
        const val INITIAL_CAPACITY = 1024
    }
}
