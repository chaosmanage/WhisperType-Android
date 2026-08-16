package com.whispertype.android.core.model

/**
 * 0.7.0: typed outcome of a text-stage attempt. Only codes, never text, so
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
