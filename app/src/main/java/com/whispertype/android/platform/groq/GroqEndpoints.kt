package com.whispertype.android.platform.groq

/**
 * 0.7.0: single source of truth for the Groq polish endpoint.
 *
 * The URL must match the current Groq SpeechCompletions WebSocket endpoint
 * and be verified on-device before release (docs/TESTING.md). An unverified
 * or wrong URL degrades safely: every dial fails with NETWORK_ERROR and the
 * raw ASR is inserted instead.
 */
object GroqEndpoints {
    val DEFAULT_WS_URL: String = "wss://api.groq.com/openai/v1/audio/transcriptions"
}