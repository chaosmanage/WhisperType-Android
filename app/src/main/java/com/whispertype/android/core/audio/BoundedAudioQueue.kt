package com.whispertype.android.core.audio

import com.whispertype.android.core.model.AudioChunk

/**
 * Result of attempting to enqueue one [AudioChunk] into a [BoundedAudioQueue].
 */
sealed interface EnqueueResult {
    /** The chunk was recorded in the queue (and will be sent exactly once). */
    data object Accepted : EnqueueResult

    /** The queue was full; the chunk was NOT recorded. Never silently dropped. */
    data class Rejected(val reason: String) : EnqueueResult
}

/**
 * Host-testable bounded FIFO transmission queue for [AudioChunk]s awaiting Gemini
 * (PRD FR-5).
 *
 * A single producer [offer]s chunks and a single consumer [take]s them; [drain] pulls
 * everything still buffered, in order, before the activity-end boundary is sent. Every
 * chunk that leaves the queue (via [take] or [drain]) is counted exactly once in
 * [sentCount], enabling the pipeline to assert exactly-once sending. Chunks rejected on a
 * full queue are counted in [rejectedCount] but are never silently dropped — the caller
 * maps a [EnqueueResult.Rejected] to a typed failure.
 *
 * The queue is deliberately clock-free and non-blocking so it can be driven synchronously
 * in host tests: [take] returns the oldest buffered chunk, or `null` when empty.
 */
class BoundedAudioQueue(
    /** Maximum number of buffered chunks. */
    val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val deque = ArrayDeque<AudioChunk>()
    private val lock = Any()
    private var sent = 0L
    private var rejected = 0L

    /** Number of chunks currently buffered. */
    val pendingCount: Int get() = synchronized(lock) { deque.size }

    /** Number of additional chunks that can be buffered before the queue is full. */
    val remainingCapacity: Int get() = synchronized(lock) { capacity - deque.size }

    /** True when no more chunks can be accepted without overflow. */
    val isFull: Boolean get() = synchronized(lock) { deque.size >= capacity }

    /** Number of chunks that have left the queue (sent) exactly once. */
    val sentCount: Long get() = synchronized(lock) { sent }

    /** Number of chunks rejected because the queue was full. */
    val rejectedCount: Long get() = synchronized(lock) { rejected }

    /**
     * Enqueues [chunk] at the tail if capacity allows. On a full queue returns
     * [EnqueueResult.Rejected] (the chunk is NOT recorded, and [rejectedCount] is
     * incremented). On success returns [EnqueueResult.Accepted].
     */
    fun offer(chunk: AudioChunk): EnqueueResult = synchronized(lock) {
        if (deque.size >= capacity) {
            rejected++
            EnqueueResult.Rejected("queue full at capacity $capacity")
        } else {
            deque.addLast(chunk)
            EnqueueResult.Accepted
        }
    }

    /**
     * Removes and returns the oldest buffered chunk (FIFO), counting it as sent exactly
     * once, or returns `null` when empty. The caller is responsible for actually
     * transmitting the returned chunk.
     */
    fun take(): AudioChunk? = synchronized(lock) {
        val chunk = deque.removeFirstOrNull()
        if (chunk != null) sent++
        chunk
    }

    /**
     * Removes and returns all currently buffered chunks in FIFO order, counting each as
     * sent exactly once. Used by the caller to flush accepted chunks before sending the
     * single activity-end boundary (FR-5).
     */
    fun drain(): List<AudioChunk> = synchronized(lock) {
        val all = deque.toList()
        sent += all.size
        deque.clear()
        all
    }
}
