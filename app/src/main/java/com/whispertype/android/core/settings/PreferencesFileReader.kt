package com.whispertype.android.core.settings

import java.io.File

/**
 * Pure, framework-free reader for a Preferences DataStore protobuf file. It
 * bypasses the in-process DataStore cache so the `:accessibility` process can
 * observe writes made by the main process (e.g. the `app_enabled` kill-switch
 * toggle) directly from disk, without a cross-process data channel.
 *
 * Wire format (PreferencesProto from androidx.datastore.preferences):
 *
 * ```
 * PreferenceMap {
 *   repeated PreferenceMapEntry preferences = 1;   // length-delimited
 * }
 * PreferenceMapEntry {
 *   string key = 1;
 *   Value value = 2;                               // length-delimited
 * }
 * Value {
 *   oneof value {
 *     bool boolean = 1;
 *     ...
 *   }
 * }
 * ```
 *
 * Any unreadable or malformed input yields `null` rather than throwing.
 */
object PreferencesFileReader {

    /** Relative path of the settings DataStore file under the app's files dir. */
    const val DATASTORE_RELATIVE_PATH = "datastore/settings.preferences_pb"

    /**
     * Returns the boolean stored under [key], or `null` when the file is
     * missing/unreadable, the key is absent, or the value is not a boolean.
     */
    fun readBoolean(file: File, key: String): Boolean? {
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        return parsePreferencesMap(bytes, key)
    }

    private fun parsePreferencesMap(bytes: ByteArray, wantedKey: String): Boolean? {
        var offset = 0
        var found = false
        var result: Boolean? = null
        while (offset < bytes.size) {
            val tag = readVarint(bytes, offset) ?: return null
            offset = tag.second
            val fieldNumber = tag.first ushr 3
            val wireType = (tag.first and 0x7L).toInt()
            when {
                fieldNumber == 1L && wireType == 2 -> {
                    val len = readVarint(bytes, offset) ?: return null
                    offset = len.second
                    val entryEnd = boundedEnd(offset, len.first, bytes.size) ?: return null
                    val entry = parseEntry(bytes, offset, entryEnd, wantedKey) ?: return null
                    if (entry.first) {
                        found = true
                        result = entry.second
                    }
                    offset = entryEnd
                }
                else -> {
                    offset = skipField(bytes, offset, wireType, bytes.size) ?: return null
                }
            }
        }
        return if (found) result else null
    }

    /** Parses one PreferenceMapEntry; returns (keyMatched, booleanValueOrNull). */
    private fun parseEntry(
        bytes: ByteArray,
        start: Int,
        end: Int,
        wantedKey: String,
    ): Pair<Boolean, Boolean?>? {
        var offset = start
        var key: String? = null
        var value: Boolean? = null
        while (offset < end) {
            val tag = readVarint(bytes, offset) ?: return null
            offset = tag.second
            val fieldNumber = tag.first ushr 3
            val wireType = (tag.first and 0x7L).toInt()
            when {
                fieldNumber == 1L && wireType == 2 -> {
                    val len = readVarint(bytes, offset) ?: return null
                    offset = len.second
                    val valueEnd = boundedEnd(offset, len.first, end) ?: return null
                    if (key == null) {
                        key = String(bytes, offset, len.first.toInt(), Charsets.UTF_8)
                    }
                    offset = valueEnd
                }
                fieldNumber == 2L && wireType == 2 -> {
                    val len = readVarint(bytes, offset) ?: return null
                    offset = len.second
                    val valueEnd = boundedEnd(offset, len.first, end) ?: return null
                    value = parseValue(bytes, offset, valueEnd)
                    offset = valueEnd
                }
                else -> {
                    offset = skipField(bytes, offset, wireType, end) ?: return null
                }
            }
        }
        return (key == wantedKey) to value
    }

    /** Parses a Value message; returns the boolean when present, else null. */
    private fun parseValue(bytes: ByteArray, start: Int, end: Int): Boolean? {
        var offset = start
        var bool: Boolean? = null
        while (offset < end) {
            val tag = readVarint(bytes, offset) ?: return null
            offset = tag.second
            val fieldNumber = tag.first ushr 3
            val wireType = (tag.first and 0x7L).toInt()
            when {
                fieldNumber == 1L && wireType == 0 -> {
                    val v = readVarint(bytes, offset) ?: return null
                    offset = v.second
                    bool = v.first != 0L
                }
                else -> {
                    offset = skipField(bytes, offset, wireType, end) ?: return null
                }
            }
        }
        return bool
    }

    private fun skipField(bytes: ByteArray, offset: Int, wireType: Int, limit: Int): Int? {
        return when (wireType) {
            0 -> readVarint(bytes, offset)?.second
            1 -> if (offset + 8 <= limit) offset + 8 else null
            2 -> {
                val len = readVarint(bytes, offset) ?: return null
                boundedEnd(len.second, len.first, limit)
            }
            5 -> if (offset + 4 <= limit) offset + 4 else null
            else -> null
        }
    }

    private fun boundedEnd(offset: Int, len: Long, limit: Int): Int? {
        if (len > Int.MAX_VALUE || offset + len > limit) return null
        return offset + len.toInt()
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var offset = start
        var value = 0L
        var shift = 0
        while (offset < bytes.size) {
            val b = bytes[offset].toInt() and 0xFF
            offset++
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to offset
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }
}
