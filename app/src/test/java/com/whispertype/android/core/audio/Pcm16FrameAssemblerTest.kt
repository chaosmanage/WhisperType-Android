package com.whispertype.android.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Pcm16FrameAssembler] (PRD FR-5): exact 20 ms framing from a sample-rate
 * derived chunk size, partial-read handling, remainder draining, byte-copy safety, and
 * chunk-size derivation across multiple supported sample rates.
 */
class Pcm16FrameAssemblerTest {

    private val assembler = Pcm16FrameAssembler()

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    // ------------------------------------------------------------------
    // Exact chunking at the default 24000 Hz / 20 ms (960 bytes/frame)
    // ------------------------------------------------------------------

    @Test
    fun `default chunk size is 960 bytes for 24000 Hz at 20 ms`() {
        assertEquals(960, assembler.expectedBytesPerFrame)
    }

    @Test
    fun `push 2880 bytes yields exactly three 960-byte chunks with sequences 0 1 2 and no loss`() {
        val input = pattern(2880)
        val chunks = assembler.push(input)

        assertEquals(3, chunks.size)
        assertEquals(listOf(0L, 1L, 2L), chunks.map { it.sequence })
        assertTrue(chunks.all { it.byteCount == 960 && it.sampleRateHz == 24000 && it.frameMillis == 20 })

        val reassembled = chunks.flatMap { it.pcm16Bytes.toList() }.toByteArray()
        assertArrayEquals(input, reassembled)
        assertEquals(0, assembler.pendingByteCount)
    }

    // ------------------------------------------------------------------
    // Partial-read handling
    // ------------------------------------------------------------------

    @Test
    fun `partial reads emit nothing until a full 960 bytes accumulate then emit exact frames`() {
        assertTrue(assembler.push(pattern(100)).isEmpty()) // pending 100
        assertTrue(assembler.push(pattern(500)).isEmpty()) // pending 600
        val first = assembler.push(pattern(700)) // pending 1300 -> one full frame
        assertEquals(1, first.size)
        assertEquals(0L, first[0].sequence)
        assertEquals(960, first[0].byteCount)
        assertEquals(340, assembler.pendingByteCount)

        val second = assembler.push(pattern(620)) // pending 960 -> next full frame
        assertEquals(1, second.size)
        assertEquals(1L, second[0].sequence)
        assertEquals(960, second[0].byteCount)
        assertEquals(0, assembler.pendingByteCount)
    }

    @Test
    fun `sequence stays monotonic across many partial pushes`() {
        var last = 0L
        for (n in intArrayOf(100, 500, 700, 620, 960, 1, 959, 500)) {
            for (chunk in assembler.push(pattern(n))) {
                assertEquals(last, chunk.sequence)
                last++
            }
        }
        // Bytes: 100+500+700+620+960+1+959+500 = 4340 = 4 full frames + 500 remainder.
        assertEquals(4L, last)
        assertEquals(500, assembler.pendingByteCount)
    }


    // ------------------------------------------------------------------
    // Remainder draining
    // ------------------------------------------------------------------

    @Test
    fun `drainRemainder emits the leftover tail as a single undersized chunk`() {
        assembler.push(pattern(1000)) // one full 960 + 40 leftover
        val tail = assembler.drainRemainder()

        assertEquals(40, tail?.byteCount)
        assertEquals(1L, tail?.sequence)
        assertEquals(0, assembler.pendingByteCount)
    }

    @Test
    fun `drainRemainder returns null when there is no remainder`() {
        assertTrue(assembler.push(ByteArray(0)).isEmpty())
        assertNull(assembler.drainRemainder())
        assertEquals(0, assembler.pendingByteCount)
        // Draining nothing does not advance the sequence (next full frame is still 0).
        assertEquals(0L, assembler.push(pattern(960))[0].sequence)
    }

    @Test
    fun `remainder drain then exact framing keeps the sequence monotonic`() {
        assembler.push(pattern(1960)) // 2 x 960 + 40 leftover
        val tail = assembler.drainRemainder()
        assertEquals(40, tail?.byteCount)
        assertEquals(2L, tail?.sequence)

        val next = assembler.push(pattern(960))
        assertEquals(1, next.size)
        assertEquals(3L, next[0].sequence)
    }

    // ------------------------------------------------------------------
    // Byte-copy safety
    // ------------------------------------------------------------------

    @Test
    fun `mutating the caller array after push does not change the emitted chunk`() {
        val input = pattern(960)
        val chunks = assembler.push(input)

        // Corrupt the caller's buffer after the push.
        input.fill(9)
        input.set(0, 127)

        assertEquals(1, chunks.size)
        assertArrayEquals(pattern(960), chunks[0].pcm16Bytes)
        assertEquals(0L, chunks[0].sequence)
    }

    // ------------------------------------------------------------------
    // Chunk-size derivation for other supported sample rates (FR-5 rule)
    // ------------------------------------------------------------------

    @Test
    fun `16000 Hz at 20 ms yields 640-byte chunks`() {
        val a = Pcm16FrameAssembler(sampleRateHz = 16_000)
        assertEquals(640, a.expectedBytesPerFrame)
        val chunks = a.push(pattern(640))
        assertEquals(1, chunks.size)
        assertEquals(640, chunks[0].byteCount)
        assertEquals(16_000, chunks[0].sampleRateHz)
    }

    @Test
    fun `48000 Hz at 20 ms yields 1920-byte chunks`() {
        val a = Pcm16FrameAssembler(sampleRateHz = 48_000)
        assertEquals(1920, a.expectedBytesPerFrame)
        val chunks = a.push(pattern(1920))
        assertEquals(1, chunks.size)
        assertEquals(1920, chunks[0].byteCount)
        assertEquals(48_000, chunks[0].sampleRateHz)
    }

    @Test
    fun `derived chunk size is pinned to the documented rule for 16k 24k 48k`() {
        assertEquals(20 * 16_000 * 2 / 1000, Pcm16FrameAssembler(16_000).expectedBytesPerFrame)
        assertEquals(20 * 24_000 * 2 / 1000, Pcm16FrameAssembler(24_000).expectedBytesPerFrame)
        assertEquals(20 * 48_000 * 2 / 1000, Pcm16FrameAssembler(48_000).expectedBytesPerFrame)
    }
}
