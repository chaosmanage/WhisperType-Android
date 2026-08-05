package com.whispertype.android.audio

import com.whispertype.android.core.model.AudioChunk
import java.util.concurrent.CountDownLatch
import kotlin.collections.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking

/**
 * Orderly producer shutdown (Release C6): the producer owns the [Chunker]
 * exclusively, so the final partial frame is zero-padded and emitted in order
 * before the chunk channel closes, and a blocking read is unblocked by
 * [AudioCapture.requestStop].
 */
class AudioCaptureOrderlyShutdownTest {

    private class FakeSource(private val chunks: ArrayDeque<ByteArray> = ArrayDeque()) : PcmSource {
        var released = false
        private val releaseLatch = CountDownLatch(1)

        override fun read(out: ByteArray): Int {
            val data = chunks.removeFirstOrNull()
            if (data != null) {
                data.copyInto(out)
                return data.size
            }
            // Model the blocking AudioRecord read: it only returns once the
            // source is released (unblocked by requestStop).
            releaseLatch.await()
            return 0
        }

        override fun release() {
            released = true
            releaseLatch.countDown()
        }
    }

    private val fullFrame = ByteArray(640) { 0x01 }
    private val partialFrame = ByteArray(100) { 0x02 }

    @Test
    fun `orderly stop flushes complete and zero-padded partial frames in order then closes`() = runBlocking {
        val source = FakeSource(ArrayDeque(listOf(fullFrame, partialFrame)))
        val capture = AudioCapture(sourceFactory = { source })

        assertIs<AudioStartResult.Started>(capture.start())
        // Give the producer a moment to consume the buffered reads.
        Thread.sleep(100)
        capture.requestStop()

        val emitted = capture.chunks.toList()
        assertTrue(source.released)
        assertEquals(2, emitted.size)
        assertEquals(0, emitted[0].sequence)
        assertEquals(1, emitted[1].sequence)
        assertEquals(640, emitted[0].byteCount)
        assertEquals(640, emitted[1].byteCount, "the partial frame must be zero-padded to a full frame")
        assertTrue(emitted[1].pcm16Bytes.take(100).all { it == 0x02.toByte() })
        assertTrue(emitted[1].pcm16Bytes.drop(100).all { it == 0x00.toByte() })
    }

    @Test
    fun `requestStop unblocks a blocking read and awaitQuiescence returns true`() = runBlocking {
        val source = FakeSource()
        val capture = AudioCapture(sourceFactory = { source })
        assertIs<AudioStartResult.Started>(capture.start())

        Thread.sleep(100)
        capture.requestStop()
        val quiesced = capture.awaitQuiescence(timeoutMs = 2_000)

        assertTrue(quiesced)
        assertTrue(source.released)
        assertEquals(0, capture.chunks.toList().size)
    }

    @Test
    fun `a source that never unblocks fails awaitQuiescence and hard stop is safe`() = runBlocking {
        val stuck = object : PcmSource {
            override fun read(out: ByteArray): Int {
                Thread.sleep(Long.MAX_VALUE)
                return 0
            }

            override fun release() {
                // Never unblocks the read.
            }
        }
        val capture = AudioCapture(sourceFactory = { stuck })
        assertIs<AudioStartResult.Started>(capture.start())

        Thread.sleep(100)
        capture.requestStop()
        val quiesced = capture.awaitQuiescence(timeoutMs = 200)
        assertFalse(quiesced)

        capture.stop() // hard-cancel fallback must not throw
    }
}
