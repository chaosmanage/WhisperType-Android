package com.whispertype.android.data.history

import kotlinx.coroutines.flow.Flow

/**
 * Opt-in transcript history. History is disabled by default; while disabled no
 * transcript storage is initialized and [record] is a no-op. Storage is
 * encrypted, retention-controlled, and absent when disabled.
 */
interface HistoryRepository {
    /** One aggregate, non-sensitive history entry. */
    data class HistoryEvent(
        val timestampMillis: Long,
        val language: String,
        val charCount: Int,
        val outcome: String,
    )

    fun events(): Flow<List<HistoryEvent>>

    /** Returns false when history is disabled; never throws for storage absence. */
    suspend fun record(event: HistoryEvent): Boolean

    suspend fun clear(): Boolean
}