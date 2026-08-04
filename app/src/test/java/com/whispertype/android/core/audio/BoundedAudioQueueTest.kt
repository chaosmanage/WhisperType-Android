package com.whispertype.android.core.audio

import com.whispertype.android.core.model.AudioChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BoundedAudioQueue] (PRD FR-5): FIFO order, capacity bound, typed overflow
 * rejection (never silent drop), drain-for-activity-end, and sent-chunk accounting.
 */
class BoundedAudioQueueTest {

    private fun chunk(sequence: Long): AudioChunk =
        AudioChunk(sequence = sequence, pcm16Bytes = ByteArray(960), sampleRateHz = 24_000, frameMillis = 20)

    // ------------------------------------------------------------------
    // FIFO order
    // ------------------------------------------------------------------

    @Test
    fun `take returns chunks in FIFO order`() {
        val queue = BoundedAudioQueue(capacity = 4)
        queue.offer(chunk(0))
        queue.offer(chunk(1))
        queue.offer(chunk(2))

        assertEquals(0L, queue.take()?.sequence)
        assertEquals(1L, queue.take()?.sequence)
        assertEquals(2L, queue.take()?.sequence)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `take returns null when empty`() {
        val queue = BoundedAudioQueue(capacity = 2)
        assertNull(queue.take())
        assertEquals(0L, queue.sentCount)
    }

    // ------------------------------------------------------------------
    // Capacity bound and overflow (never silently drop)
    // ------------------------------------------------------------------

    @Test
    fun `capacity bound is honored and overflow is rejected not silently dropped`() {
        val queue = BoundedAudioQueue(capacity = 2)
        assertEquals(EnqueueResult.Accepted, queue.offer(chunk(0)))
        assertEquals(EnqueueResult.Accepted, queue.offer(chunk(1)))
        assertEquals(2, queue.pendingCount)

        val overflow = queue.offer(chunk(2))
        assertTrue(overflow is EnqueueResult.Rejected)
        val rejected = overflow as EnqueueResult.Rejected
        assertTrue(rejected.reason.isNotBlank())

        // Nothing extra was recorded and the overflow is counted.
        assertEquals(2, queue.pendingCount)
        assertEquals(1L, queue.rejectedCount)
        // The overflowing chunk was never recorded, so sending accounting is unaffected.
        assertEquals(0L, queue.sentCount)
        assertTrue(queue.isFull)
        assertEquals(0, queue.remainingCapacity)
    }

    @Test
    fun `after freeing space new chunks are accepted again`() {
        val queue = BoundedAudioQueue(capacity = 1)
        assertTrue(queue.offer(chunk(1)) is EnqueueResult.Accepted)
        assertTrue(queue.offer(chunk(2)) is EnqueueResult.Rejected)

        assertEquals(1L, queue.take()?.sequence)
        assertEquals(EnqueueResult.Accepted, queue.offer(chunk(3)))
        assertEquals(3L, queue.take()?.sequence)
    }

    // ------------------------------------------------------------------
    // Drain before activity-end (FR-5)
    // ------------------------------------------------------------------

    @Test
    fun `drain returns all buffered chunks in FIFO order and clears the queue`() {
        val queue = BoundedAudioQueue(capacity = 5)
        queue.offer(chunk(0))
        queue.offer(chunk(1))
        queue.offer(chunk(2))

        val drained = queue.drain()

        assertEquals(listOf(0L, 1L, 2L), drained.map { it.sequence })
        assertEquals(0, queue.pendingCount)
        // Each drained chunk is counted as sent exactly once.
        assertEquals(3L, queue.sentCount)
    }

    @Test
    fun `drain on an empty queue returns an empty list`() {
        val queue = BoundedAudioQueue(capacity = 3)
        assertTrue(queue.drain().isEmpty())
        assertEquals(0L, queue.sentCount)
    }

    // ------------------------------------------------------------------
    // Sent-chunk accounting (exactly-once sending)
    // ------------------------------------------------------------------

    @Test
    fun `sentCount counts each chunk that leaves the queue exactly once`() {
        val queue = BoundedAudioQueue(capacity = 6)
        repeat(5) { queue.offer(chunk(it.toLong())) }

        queue.take() // one sent via take
        queue.take() // one sent via take
        queue.drain() // remaining three sent via drain

        assertEquals(5L, queue.sentCount)
        assertEquals(0, queue.pendingCount)
        // Rejected chunks never enter the sent count.
        assertEquals(0L, queue.rejectedCount)
    }

    @Test
    fun `sentCount reflects only successful sends and rejects do not increment it`() {
        val queue = BoundedAudioQueue(capacity = 2)
        assertEquals(EnqueueResult.Accepted, queue.offer(chunk(0)))
        assertEquals(EnqueueResult.Accepted, queue.offer(chunk(1)))
        assertTrue(queue.offer(chunk(2)) is EnqueueResult.Rejected)

        queue.drain()
        assertEquals(2L, queue.sentCount)
        assertEquals(1L, queue.rejectedCount)
        // A late take now that the queue is empty returns null and adds nothing.
        assertNull(queue.take())
        assertEquals(2L, queue.sentCount)
    }
}
