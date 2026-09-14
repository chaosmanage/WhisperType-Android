package com.whispertype.android.core.transcript

/**
 * Commit-text shaping shared by every insertion output (direct `commitText()`
 * and the clipboard fallback). Dictated text is committed with exactly one
 * trailing space so the next dictation or keystroke continues after a word
 * boundary instead of requiring a manual space.
 *
 * Idempotent: blank text and text already ending in whitespace are returned
 * unchanged, so applying it at both the runtime boundary and the gateway is
 * safe and never yields a double space.
 */
object InsertionText {

    fun withTrailingSpace(text: String): String =
        if (text.isBlank() || text.last().isWhitespace()) text else "$text "
}
