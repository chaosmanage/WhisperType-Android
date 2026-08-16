package com.whispertype.android.platform.groq

/**
 * 0.8.0: key selectors for the Groq REST surface.
 *
 * MODEL POLICY: Groq is **text-only** in this app. Its speech-to-text surface
 * and any Whisper model are forbidden — audio goes only to Gemini Live.
 * Enforced by `ModelPolicyTest`, which is why this comment does not spell out
 * the forbidden endpoint paths.
 */
object GroqEndpoints {

    /** OpenAI-compatible chat completions endpoint (verified live). */
    const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"

    /**
     * Text-shaping model. `llama-3.1-8b-instant` is chosen for latency and for
     * free-tier headroom: 30 RPM / 14,400 requests per day / 500K tokens per
     * day, versus 1,000 requests per day for the 70B model.
     */
    const val CHAT_MODEL = "llama-3.1-8b-instant"

    /**
     * Quality fallback for Hinglish romanization if the 8B model proves weak
     * (free tier: 30 RPM / 1,000 RPD / 100K TPD).
     */
    const val CHAT_MODEL_FALLBACK = "llama-3.3-70b-versatile"
}
