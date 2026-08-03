package com.whispertype.android.history

/**
 * User-facing history settings (Implementation Plan §11).
 */
data class HistorySettings(
    val enabled: Boolean = false,
    val retentionDays: Int = 7,
    val includeMetadata: Boolean = false,
)

/**
 * Pure retention policy (Implementation Plan §16). No Android dependencies, JVM-testable.
 */
object HistoryPolicy {

    private const val DAY_MILLIS: Long = 86_400_000L

    /** True when [timestampMillis] is strictly older than [retentionDays] relative to [nowMillis]. */
    fun isExpired(timestampMillis: Long, retentionDays: Int, nowMillis: Long): Boolean =
        nowMillis - timestampMillis > retentionDays * DAY_MILLIS

    /** Only 1, 7 and 30-day retention windows are offered in settings. */
    fun validRetentionDays(days: Int): Boolean = days == 1 || days == 7 || days == 30
}
