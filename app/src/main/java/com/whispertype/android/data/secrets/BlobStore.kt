package com.whispertype.android.data.secrets

import android.content.Context
import java.io.File

/**
 * App-private byte storage for an encrypted blob. The production implementation
 * writes to the app's private files dir (outside the Keystore), which, combined
 * with the Keystore-held key, plus allowBackup=false and the data extraction
 * rules, keeps the encrypted secret out of backups.
 */
interface BlobStore {
    /** @return stored bytes, or null when absent or unreadable. */
    fun read(): ByteArray?

    fun write(data: ByteArray): Boolean

    fun delete(): Boolean
}

class FileBlobStore(file: File) : BlobStore {

    constructor(context: Context, fileName: String) : this(File(context.filesDir, fileName))

    private val target: File = file

    override fun read(): ByteArray? =
        try {
            if (target.exists()) target.readBytes() else null
        } catch (_: Throwable) {
            null
        }

    // Crash-safe write: bytes land in a temp file in the same directory and are
    // moved onto the target with an atomic rename, so a crash mid-write can
    // never leave a truncated target behind.
    override fun write(data: ByteArray): Boolean =
        try {
            val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
            try {
                temp.writeBytes(data)
                if (temp.renameTo(target)) {
                    true
                } else {
                    temp.delete()
                    false
                }
            } catch (_: Throwable) {
                temp.delete()
                false
            }
        } catch (_: Throwable) {
            false
        }

    override fun delete(): Boolean =
        try {
            !target.exists() || target.delete()
        } catch (_: Throwable) {
            false
        }
}