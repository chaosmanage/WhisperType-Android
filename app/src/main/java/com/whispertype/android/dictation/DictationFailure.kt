package com.whispertype.android.dictation

/**
 * Typed, non-sensitive failure description.
 * [code] is a stable diagnostic token that may appear in logs and diagnostics.
 */
data class DictationFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)
