package com.whispertype.android.core.model

/**
 * Typed result of an insertion transaction. Never a bare Boolean: [Ambiguous]
 * means the commit status could not be confirmed and must surface an explicit
 * Copy fallback rather than a blind retry.
 */
sealed interface InsertionResult {
    data object Inserted : InsertionResult
    data class Failed(val failure: DictationFailure) : InsertionResult
    data object Ambiguous : InsertionResult
}