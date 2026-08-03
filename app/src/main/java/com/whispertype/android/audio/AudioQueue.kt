package com.whispertype.android.audio

import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded FIFO for captured audio chunks; the oldest element is dropped when
 * full. Mutations are serialized through [Mutex]; [size] and [metrics] are
 * unsynchronized snapshots backed by volatile counters.
 */
class AudioQueue(private val capacity: Int = 100) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val queue = ArrayDeque<AudioChunk>()
    private val mutex = Mutex()

    @Volatile
    private var droppedCount = 0

    @Volatile
    private var highWaterMark = 0

    @Volatile
    private var totalPushed = 0

    /**
     * Appends [chunk], dropping the oldest buffered chunk when the queue is
     * full.
     * @return false when a chunk was dropped.
     */
    suspend fun put(chunk: AudioChunk): Boolean = mutex.withLock {
        val dropped = queue.size == capacity
        if (dropped) {
            queue.removeFirst()
            droppedCount++
        }
        queue.add(chunk)
        totalPushed++
        val currentSize = queue.size
        if (currentSize > highWaterMark) highWaterMark = currentSize
        !dropped
    }

    /** Removes and returns all buffered chunks in FIFO order. */
    suspend fun drain(): List<AudioChunk> = mutex.withLock {
        buildList {
            while (queue.isNotEmpty()) {
                add(queue.removeFirst())
            }
        }
    }

    /** Current buffered chunk count (unsynchronized snapshot). */
    fun size(): Int = queue.size

    /** Cumulative lifetime metrics (unsynchronized snapshot). */
    fun metrics(): QueueMetrics = QueueMetrics(droppedCount, highWaterMark, totalPushed)

    /** Empties the buffer; lifetime counters are preserved. */
    suspend fun clear() {
        mutex.withLock { queue.clear() }
    }

    data class QueueMetrics(
        val droppedCount: Int,
        val highWaterMark: Int,
        val totalPushed: Int,
    )
}
