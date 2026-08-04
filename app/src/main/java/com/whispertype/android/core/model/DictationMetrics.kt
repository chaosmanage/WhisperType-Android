package com.whispertype.android.core.model

/** Aggregate, non-sensitive session metrics for diagnostics. */
data class DictationMetrics(
    val sessionId: SessionId,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val chunkCount: Int = 0,
    val bytesSent: Long = 0,
    val droppedChunks: Int = 0,
    val sendRejected: Int = 0,
    val finalizeLatencyMillis: Long? = null,
)