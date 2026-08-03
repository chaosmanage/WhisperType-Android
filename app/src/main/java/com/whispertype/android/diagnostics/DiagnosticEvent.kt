package com.whispertype.android.diagnostics

/**
 * One non-sensitive diagnostic record: a typed event name plus an optional
 * aggregate duration. Never contains transcripts, audio, API keys, editor
 * content, or package data.
 */
data class DiagnosticEvent(
    val name: String,
    val atMillis: Long,
    val durationMillis: Long? = null,
)