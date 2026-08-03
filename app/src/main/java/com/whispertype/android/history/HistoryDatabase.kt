package com.whispertype.android.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * On-disk history entry (Implementation Plan §11). The payload is already encrypted
 * before it reaches this class; only Base64 strings are persisted.
 */
data class HistoryEntryDto(
    val timestampMillis: Long,
    val encryptedPayloadBase64: String,
    val ivBase64: String,
)

/**
 * JSON-array file store for history entries at
 * [Context.getNoBackupFilesDir]()/history/history.db (Implementation Plan §11).
 *
 * LAZY: the database directory and file are created only by the first [append];
 * the constructor performs no I/O. All operations are Mutex-protected and run on
 * [Dispatchers.IO]. Writes go through a temp file + rename so a crash mid-write
 * cannot truncate the store.
 *
 * NOTE: [encode]/[decode] are kept `internal` and intentionally NOT unit-tested:
 * the JVM unit-test environment stubs org.json, so Android JSONArray/JSONObject
 * behaviour cannot be exercised in plain JVM tests. They are exercised only via
 * instrumented tests and on-device integration.
 */
class HistoryDatabase(context: Context) {

    private val file: File = File(context.getNoBackupFilesDir(), "history/history.db")
    private val mutex = Mutex()

    suspend fun append(entry: HistoryEntryDto) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeUnlocked(readAllUnlocked() + entry)
        }
    }

    suspend fun readAll(): List<HistoryEntryDto> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllUnlocked() }
    }

    suspend fun rewrite(entries: List<HistoryEntryDto>) = withContext(Dispatchers.IO) {
        mutex.withLock { writeUnlocked(entries) }
    }

    private fun readAllUnlocked(): List<HistoryEntryDto> {
        if (!file.exists()) return emptyList()
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }
        return decode(content)
    }

    private fun writeUnlocked(entries: List<HistoryEntryDto>) {
        if (entries.isEmpty() && !file.exists()) return
        val parent = file.parentFile
        parent?.mkdirs() ?: return
        val tmp = File(parent, "history.db.tmp")
        tmp.writeText(encode(entries), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(encode(entries), Charsets.UTF_8)
        }
    }

    internal fun encode(entries: List<HistoryEntryDto>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(KEY_TIMESTAMP_MILLIS, entry.timestampMillis)
                    .put(KEY_ENCRYPTED_PAYLOAD_BASE64, entry.encryptedPayloadBase64)
                    .put(KEY_IV_BASE64, entry.ivBase64),
            )
        }
        return array.toString()
    }

    internal fun decode(content: String): List<HistoryEntryDto> {
        return try {
            val array = JSONArray(content)
            val result = ArrayList<HistoryEntryDto>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val timestampMillis = obj.optLong(KEY_TIMESTAMP_MILLIS, -1L)
                val encrypted = obj.optString(KEY_ENCRYPTED_PAYLOAD_BASE64, "")
                val iv = obj.optString(KEY_IV_BASE64, "")
                if (timestampMillis < 0L || encrypted.isEmpty() || iv.isEmpty()) continue
                result.add(HistoryEntryDto(timestampMillis, encrypted, iv))
            }
            result
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_TIMESTAMP_MILLIS: String = "timestampMillis"
        const val KEY_ENCRYPTED_PAYLOAD_BASE64: String = "encryptedPayloadBase64"
        const val KEY_IV_BASE64: String = "ivBase64"
    }
}
