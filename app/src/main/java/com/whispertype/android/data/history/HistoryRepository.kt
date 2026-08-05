package com.whispertype.android.data.history

import kotlinx.coroutines.flow.Flow

/** Opt-in, encrypted transcript history (text included). */
interface HistoryRepository {

    data class HistoryEntry(
        val id: String,
        val timestampMillis: Long,
        val text: String,
        val language: String,
        val charCount: Int,
        val outcome: String,
    )

    /** Newest-first, retention-pruned entries. */
    fun events(): Flow<List<HistoryEntry>>

    /** Records one entry (prunes by retention + cap). False on storage failure. */
    suspend fun record(entry: HistoryEntry): Boolean

    suspend fun delete(id: String): Boolean

    suspend fun clear(): Boolean
}
