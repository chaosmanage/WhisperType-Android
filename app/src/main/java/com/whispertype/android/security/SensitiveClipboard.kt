package com.whispertype.android.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Explicit, user-initiated clipboard writes only.
 * Marks content as sensitive so other apps are warned before showing it.
 * WhisperType never reads the clipboard.
 */
class SensitiveClipboard(private val context: Context) {

    fun copySensitive(text: String): Boolean {
        if (text.isBlank()) return false
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            manager.setPrimaryClip(clip)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object {
        const val CLIP_LABEL: String = "WhisperType text"
    }
}
