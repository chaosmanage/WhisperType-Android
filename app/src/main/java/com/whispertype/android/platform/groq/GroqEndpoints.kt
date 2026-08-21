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
     * Text-shaping model. The llama-3.1/3.3 Groq deployments were decommissioned
     * (verified live 2026-08-21: every llama chat model returns
     * `model_not_found`), which silently degraded the whole text stage to raw
     * ASR insertion. `openai/gpt-oss-120b` is the current free-tier pick: it
     * follows the [PolishPrompts] level calibration including the Hinglish
     * never-translate rule (verified live: it keeps Hindi words in Latin
     * script, where gpt-oss-20b translated them to English).
     */
    const val CHAT_MODEL = "openai/gpt-oss-120b"

    /**
     * Speed fallback on the 429 retry path. A different deployment shares
     * nothing with the primary's tokens-per-minute bucket, so a drained
     * primary budget still rescues a dictation.
     */
    const val CHAT_MODEL_FALLBACK = "openai/gpt-oss-20b"
}
