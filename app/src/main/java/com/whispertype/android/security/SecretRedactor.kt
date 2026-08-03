package com.whispertype.android.security

/**
 * Pure-Kotlin redactor for secrets that may appear in logs, exceptions, or
 * WebSocket URLs. Applies the pattern passes in order:
 *
 *  1. Google API keys (`AIza...`).
 *  2. Query parameters whose name is a key (case-insensitive).
 *  3. Authorization headers or assignments (case-insensitive).
 *  4. Long base64/hex runs.
 */
object SecretRedactor {

    private const val REDACTED: String = "[REDACTED]"

    internal const val GOOGLE_API_KEY_PATTERN: String = "AIza[0-9A-Za-z_\\-]{30,}"
    internal const val QUERY_KEY_PARAM_PATTERN: String = "([?&](?:key|apiKey|api_key|X-Goog-Api-Key)=)[^&\\s]+"
    internal const val AUTHORIZATION_PATTERN: String = "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+"
    internal const val LONG_BASE64_HEX_PATTERN: String = "[0-9A-Fa-f+/=]{32,}"

    fun redact(text: String?): String {
        if (text == null) {
            return ""
        }
        return text
            .replace(Regex(GOOGLE_API_KEY_PATTERN), REDACTED)
            .replace(Regex(QUERY_KEY_PARAM_PATTERN, RegexOption.IGNORE_CASE), "\$1$REDACTED")
            .replace(Regex(AUTHORIZATION_PATTERN), "\$1$REDACTED")
            .replace(Regex(LONG_BASE64_HEX_PATTERN), REDACTED)
    }
}
