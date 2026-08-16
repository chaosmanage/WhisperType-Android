package com.whispertype.android.core.groq

/** 0.7.0: typed reports re: dialoging into the Groq backend. No text, no keys
 *  — only codes, so the runtime log and the banner stay privacy-safe. */
enum class GroqBannerKind {
    /** Runtime holds a valid Groq key; a chat is in progress. */
    ACTIVE,

    /** Runtime holds a valid key; the last attempt finished without a committed
     *  polish (e.g. NETWORK_ERROR, rate limit). */
    UNSUCCESSFUL,

    /** User tapped the banner in-flight; the sitting polish attempt was
     *  cancelled. */
    CANCELLED,

    /** The dial was attempted and succeeded in committing text. */
    OK,

    /** The dialoging attempt hit timeout/errors before use. */
    TIMEOUT,
}

/** Interface the runtime uses to surface Groq polish activity to the UI. */
interface GroqBannerContract {
    fun update(kind: GroqBannerKind)
}