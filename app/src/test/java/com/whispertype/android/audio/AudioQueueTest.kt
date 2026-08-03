package com.whispertype.android.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioQueueTest {

    private fun chunk(sequenceNumber: Long): AudioChunk =
        AudioChunk(
            sequenceNumber = sequenceNumber,
            pcm16Bytes = byteArrayOf(0, 0),
            capturedAtMillis = sequenceNumber,
        )

    @Test
    fun putBeyondCapacityDropsOldestAndTracksMetrics() = runTest {
        val queue = AudioQueue(capacity = 3)

        for (seq in 1L..5L) {
            queue.put(chunk(seq))
        }

        assertEquals(3, queue.size())
        assertEquals(2, queue.metrics().droppedCount)
        assertEquals(3, queue.metrics().highWaterMark)
        assertEquals(5, queue.metrics().totalPushed)
    }

    @Test
    fun putReturnsFalseOnlyWhenFull() = runTest {
        val queue = AudioQueue(capacity = 2)

        assertTrue(queue.put(chunk(1)))
        assertTrue(queue.put(chunk(2)))
        assertFalse(queue.put(chunk(3)))
        assertFalse(queue.put(chunk(4)))
    }

    @Test
    fun drainReturnsLastBufferedChunksInFifoOrderAndEmptiesQueue() = runTest {
        val queue = AudioQueue(capacity = 3)

        for (seq in 1L..5L) {
            queue.put(chunk(seq))
        }

        val drained = queue.drain()

        assertEquals(listOf(3L, 4L, 5L), drained.map { it.sequenceNumber })
        assertEquals(0, queue.size())
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun clearEmptiesQueueAndAllowsFurtherPuts() = runTest {
        val queue = AudioQueue(capacity = 3)

        for (seq in 1L..3L) {
            queue.put(chunk(seq))
        }

        queue.clear()

        assertEquals(0, queue.size())
        assertTrue(queue.put(chunk(10)))
        assertEquals(listOf(10L), queue.drain().map { it.sequenceNumber })
    }

    @Test
    fun metricsPreserveLifetimeTotalsAcrossClear() = runTest {
        val queue = AudioQueue(capacity = 2)

        queue.put(chunk(1))
        queue.put(chunk(2))
        queue.put(chunk(3))
        queue.clear()
        queue.put(chunk(4))

        assertEquals(1, queue.metrics().droppedCount)
        assertEquals(2, queue.metrics().highWaterMark)
        assertEquals(4, queue.metrics().totalPushed)
    }
}
