package com.whispertype.android.diagnostics

/**
 * Aggregate timing for one dictation session. Only timestamps and derived
 * durations; never transcript, audio, key, or editor content.
 */
data class TimingMetrics(
    val sessionId: String,
    val startedAtMillis: Long,
    val captureStartedAtMillis: Long? = null,
    val captureStoppedAtMillis: Long? = null,
    val drainStartedAtMillis: Long? = null,
    val drainFinishedAtMillis: Long? = null,
    val activityEndSentAtMillis: Long? = null,
    val finalResultAtMillis: Long? = null,
    val insertedAtMillis: Long? = null,
) {
    val totalDurationMillis: Long
        get() = (finalResultAtMillis ?: activityEndSentAtMillis ?: insertedAtMillis ?: startedAtMillis) - startedAtMillis

    val captureDurationMillis: Long
        get() = (captureStoppedAtMillis ?: 0L) - (captureStartedAtMillis ?: 0L)

    val drainDurationMillis: Long
        get() = (drainFinishedAtMillis ?: 0L) - (drainStartedAtMillis ?: 0L)
}
