package com.whispertype.android.gemini

/**
 * Central configuration for a Gemini Live session (Implementation Plan §12).
 *
 * The model identifier lives ONLY here. Changing it must be a one-line change
 * accompanied by a contract-test update, documented in docs/GEMINI_LIVE_PROTOCOL.md.
 */
data class GeminiSessionConfig(
    val modelId: String = DEFAULT_MODEL_ID,
    val languageMode: LanguageMode,
    val sampleRateHz: Int = 16000,
    val activityEndTimeoutMillis: Long = 15_000L,
    val connectionTimeoutMillis: Long = 15_000L,
) {
    companion object {
        const val DEFAULT_MODEL_ID: String = "gemini-3.1-flash-live-preview"
    }
}

enum class LanguageMode {
    ENGLISH,
    HINGLISH,
}
