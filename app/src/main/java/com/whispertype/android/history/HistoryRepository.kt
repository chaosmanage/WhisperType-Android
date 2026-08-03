package com.whispertype.android.history

import android.content.Context
import com.whispertype.android.diagnostics.DiagnosticsExporter
import com.whispertype.android.gemini.LanguageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * High-level history service (Implementation Plan §11, §16).
 *
 * Storage is gated on [enabledProvider]; when disabled, [addCompleted] and
 * [listEntries] are no-ops and never touch the database file. All plaintext
 * is encrypted before persistence and never logged.
 */
class HistoryRepository(
    context: Context,
    private val enabledProvider: () -> Boolean,
    private val retentionProvider: () -> Int,
    private val includeMetadataProvider: () -> Boolean,
) {

    data class HistoryEntry(
        val timestampMillis: Long,
        val transcript: String,
        val languageMode: String,
        val insertionStatus: String,
        val appPackage: String?,
    )

    private val database: HistoryDatabase = HistoryDatabase(context)
    private val store: EncryptedHistoryStore = EncryptedHistoryStore(context)

    /**
     * Records one completed dictation. Returns immediately without creating any
     * storage when history is disabled.
     */
    suspend fun addCompleted(
        transcript: String,
        languageMode: LanguageMode,
        insertionStatus: String,
        appPackage: String?,
    ) = withContext(Dispatchers.IO) {
        if (!enabledProvider()) return@withContext

        val nowMillis = System.currentTimeMillis()
        val storedPackage = if (includeMetadataProvider()) appPackage else null
        val plaintext = JSONObject()
            .put(KEY_TIMESTAMP_MILLIS, nowMillis)
            .put(KEY_TRANSCRIPT, transcript)
            .put(KEY_LANGUAGE_MODE, languageMode.name)
            .put(KEY_INSERTION_STATUS, insertionStatus)
            .put(KEY_APP_PACKAGE, storedPackage ?: JSONObject.NULL)
            .toString()

        val blob = store.encrypt(plaintext)
        database.append(
            HistoryEntryDto(
                timestampMillis = nowMillis,
                encryptedPayloadBase64 = blob.ciphertextBase64,
                ivBase64 = blob.ivBase64,
            ),
        )
    }

    /** Returns decrypted, unexpired entries newest-first; empty when history is disabled. */
    suspend fun listEntries(): List<HistoryEntry> {
        if (!enabledProvider()) return emptyList()
        return withContext(Dispatchers.IO) {
            val retentionDays = retentionProvider()
            val nowMillis = System.currentTimeMillis()
            database.readAll()
                .mapNotNull { entry -> parseEntry(entry) }
                .filterNot { HistoryPolicy.isExpired(it.timestampMillis, retentionDays, nowMillis) }
                .sortedByDescending { it.timestampMillis }
        }
    }

    /** Removes every stored entry; no-op when nothing has ever been stored. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.rewrite(emptyList())
    }

    private fun parseEntry(dto: HistoryEntryDto): HistoryEntry? {
        return try {
            val plaintext = store.decrypt(EncryptedBlob(dto.encryptedPayloadBase64, dto.ivBase64))
            val json = JSONObject(plaintext)
            HistoryEntry(
                timestampMillis = json.getLong(KEY_TIMESTAMP_MILLIS),
                transcript = json.getString(KEY_TRANSCRIPT),
                languageMode = json.getString(KEY_LANGUAGE_MODE),
                insertionStatus = json.getString(KEY_INSERTION_STATUS),
                appPackage = if (json.isNull(KEY_APP_PACKAGE)) null else json.getString(KEY_APP_PACKAGE),
            )
        } catch (_: Exception) {
            DiagnosticsExporter.record("history.corrupt_entry")
            null
        }
    }

    private companion object {
        const val KEY_TIMESTAMP_MILLIS: String = "timestampMillis"
        const val KEY_TRANSCRIPT: String = "transcript"
        const val KEY_LANGUAGE_MODE: String = "languageMode"
        const val KEY_INSERTION_STATUS: String = "insertionStatus"
        const val KEY_APP_PACKAGE: String = "appPackage"
    }
}
