package com.whispertype.android.dictation

import com.whispertype.android.accessibility.TargetToken

/**
 * Global dictation state machine (Implementation Plan §10.4).
 * Only one session exists at a time; stale sessions are rejected by [sessionId].
 */
sealed interface DictationState {
    val sessionId: String?

    /** No prerequisite (accessibility disabled, no mic permission, no key, unsupported layout...). */
    data object Unavailable : DictationState {
        override val sessionId: String? = null
    }

    /** Accessibility active but no eligible focused text field. */
    data object NoEditableFocus : DictationState {
        override val sessionId: String? = null
    }

    /** Eligible field focused, keyboard docked, dock visible and tappable. */
    data object DockedReady : DictationState {
        override val sessionId: String? = null
    }

    data class Starting(
        override val sessionId: String,
        val target: TargetToken,
    ) : DictationState

    data class Listening(
        override val sessionId: String,
        val elapsedMillis: Long,
        val amplitude: Float,
    ) : DictationState

    data class Finalizing(
        override val sessionId: String,
        val amplitude: Float,
    ) : DictationState

    data class Inserting(override val sessionId: String) : DictationState

    data class Success(
        override val sessionId: String,
        val insertedTextLength: Int,
    ) : DictationState

    data object Cancelled : DictationState {
        override val sessionId: String? = null
    }

    data class CopyAvailable(
        override val sessionId: String,
        val resultText: String,
    ) : DictationState

    data class Error(
        override val sessionId: String?,
        val failure: DictationFailure,
    ) : DictationState
}
