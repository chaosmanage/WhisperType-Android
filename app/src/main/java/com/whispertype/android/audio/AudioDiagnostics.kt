package com.whispertype.android.audio

/**
 * Non-sensitive counters and timestamps for one capture session. All writes
 * are atomic volatile updates; this class never stores audio.
 */
class AudioDiagnostics(private val clock: () -> Long = System::currentTimeMillis) {

    data class AudioDiagnosticsSnapshot(
        val chunkCount: Long,
        val droppedCount: Long,
        val queueHighWater: Int,
        val captureStartedAtMillis: Long,
        val captureStoppedAtMillis: Long,
        val drainStartedAtMillis: Long,
        val drainFinishedAtMillis: Long,
        val activityEndSentAtMillis: Long,
        val endBoundaryTimestampMillis: Long,
    )

    @Volatile private var chunkCount = 0L
    @Volatile private var droppedCount = 0L
    @Volatile private var queueHighWater = 0
    @Volatile private var captureStartedAtMillis = 0L
    @Volatile private var captureStoppedAtMillis = 0L
    @Volatile private var drainStartedAtMillis = 0L
    @Volatile private var drainFinishedAtMillis = 0L
    @Volatile private var activityEndSentAtMillis = 0L
    @Volatile private var endBoundaryTimestampMillis = 0L

    fun recordChunk() {
        chunkCount++
    }

    fun recordDrop() {
        droppedCount++
    }

    fun markHighWater(n: Int) {
        if (n > queueHighWater) queueHighWater = n
    }

    fun markCaptureStart() {
        captureStartedAtMillis = clock()
    }

    fun markCaptureStop() {
        captureStoppedAtMillis = clock()
    }

    fun markDrainStart() {
        drainStartedAtMillis = clock()
    }

    fun markDrainFinish() {
        drainFinishedAtMillis = clock()
    }

    fun markActivityEndSent() {
        activityEndSentAtMillis = clock()
    }

    fun markEndBoundary(timestampMillis: Long) {
        endBoundaryTimestampMillis = timestampMillis
    }

    fun snapshot(): AudioDiagnosticsSnapshot = AudioDiagnosticsSnapshot(
        chunkCount = chunkCount,
        droppedCount = droppedCount,
        queueHighWater = queueHighWater,
        captureStartedAtMillis = captureStartedAtMillis,
        captureStoppedAtMillis = captureStoppedAtMillis,
        drainStartedAtMillis = drainStartedAtMillis,
        drainFinishedAtMillis = drainFinishedAtMillis,
        activityEndSentAtMillis = activityEndSentAtMillis,
        endBoundaryTimestampMillis = endBoundaryTimestampMillis,
    )
}
