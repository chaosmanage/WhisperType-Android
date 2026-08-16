package com.whispertype.android.core.model

/**
 * 0.7.0: how the settled raw ASR becomes the inserted text.
 *
 * [AUTO] resolves at session setup: Groq when a Groq key is present, otherwise
 * [LIVE_ECHO] for Hinglish (Latin script requires a model pass) and [NONE] for
 * English. [GROQ] and [NONE] settle on the fast raw ASR; [LIVE_ECHO] keeps the
 * 0.6.2 echo behavior unchanged.
 */
enum class PolishBackend {
    AUTO,
    GROQ,
    NONE,
    LIVE_ECHO,
}

/** 0.7.0: a non-LIVE_ECHO backend strips the session down to raw ASR transport. */
fun needsLiveEcho(backend: PolishBackend, language: LanguageMode, style: TranscriptionStyle): Boolean =
    backend == PolishBackend.LIVE_ECHO &&
        (language == LanguageMode.HINGLISH ||
            (style != TranscriptionStyle.NONE && style != TranscriptionStyle.LOW))

/** 0.7.0: the echo instruction only exists for the LIVE_ECHO backend. */
fun liveInstructionFor(
    backend: PolishBackend,
    language: LanguageMode,
    style: TranscriptionStyle,
): String? =
    if (backend == PolishBackend.LIVE_ECHO) language.liveInstruction(style) else null

/**
 * 0.7.0: typed outcome of a polish attempt. Only codes, never text, so
 * diagnostics stay privacy-safe.
 */
enum class PolishOutcome {
    SUCCESS,
    TIMEOUT,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    EMPTY_RESPONSE,
    NO_KEY,
    CANCELLED,
    OTHER,
}