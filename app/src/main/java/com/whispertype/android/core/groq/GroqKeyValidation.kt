package com.whispertype.android.core.groq

/**
 * 0.7.0: pure, stateless validation for user-entered Groq API keys. Never
 * touches a network or keystore — the settings screen uses this before
 * persisting, and the runtime re-validates before dialing.
 *
 * Groq keys are `gsk_`-prefixed tokens. The check is intentionally
 * lenient beyond the prefix (length + charset) so a key that the user later
 * rotates or a future key format still passes.
 */
object GroqKeyValidation {

    private val ALLOWED_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + "_-.=".toList()

    /** True when [key] (after trimming) looks like a Groq API key. */
    fun looksValid(key: String): Boolean {
        val trimmed = key.trim()
        if (!trimmed.startsWith("gsk_")) return false
        if (trimmed.length < 20 || trimmed.length > 160) return false
        return trimmed.all { it in ALLOWED_CHARS }
    }
}