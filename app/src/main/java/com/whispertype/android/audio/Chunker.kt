package com.whispertype.android.audio

import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.AudioChunk

/**
 * Pure audio accumulator that frames raw PCM16 bytes into exact 20 ms
 * [AudioChunk]s. Partial reads are never dropped: bytes are buffered until a
 * complete frame is available. Frame size is derived from the sample rate and
 * the fixed 20 ms duration, never from a hard-coded byte count.
 */
class Chunker(private val sampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ) {

    private val bytesPerFrame: Int =
        GemAudioFormat.FRAME_MILLIS * sampleRateHz * GemAudioFormat.CHAR_BYTES / 1000

    private var pending = ByteArray(0)
    private var pendingSize = 0
    private var nextSequence = 0L

    init {
        require(bytesPerFrame > 0) { "sampleRateHz must admit a positive frame size" }
    }

    /**
     * Accumulates [pcm16] and returns every complete [AudioChunk] produced.
     * Any partial trailing bytes are retained for the next [push] or are
     * forced out by [remaining]. Returns an empty list when the buffer does
     * not yet hold a full frame. Empty input is a no-op.
     */
    fun push(pcm16: ByteArray): List<AudioChunk> {
        if (pcm16.isEmpty() && pendingSize == 0) return emptyList()

        val totalSize = pendingSize + pcm16.size
        val combined = ByteArray(totalSize)
        if (pendingSize > 0) pending.copyInto(combined, 0, 0, pendingSize)
        if (pcm16.isNotEmpty()) pcm16.copyInto(combined, pendingSize)

        val result = ArrayList<AudioChunk>(totalSize / bytesPerFrame)
        var offset = 0
        while (totalSize - offset >= bytesPerFrame) {
            val frame = ByteArray(bytesPerFrame)
            combined.copyInto(frame, 0, offset, offset + bytesPerFrame)
            result.add(AudioChunk(nextSequence++, frame, sampleRateHz))
            offset += bytesPerFrame
        }

        val remainder = totalSize - offset
        pending = if (remainder > 0) combined.copyOfRange(offset, totalSize) else ByteArray(0)
        pendingSize = remainder
        return result
    }

    /**
     * Forces any incomplete final frame out as a complete frame, zero-padding
     * the trailing bytes. Returns null when there is nothing buffered. This is
     * how capture finalization avoids dropping a partial read.
     */
    fun remaining(): AudioChunk? {
        if (pendingSize == 0) return null
        val frame = ByteArray(bytesPerFrame)
        pending.copyInto(frame, 0, 0, pendingSize)
        pending = ByteArray(0)
        pendingSize = 0
        return AudioChunk(nextSequence++, frame, sampleRateHz)
    }
}
