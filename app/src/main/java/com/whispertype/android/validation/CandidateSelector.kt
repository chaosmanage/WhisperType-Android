package com.whispertype.android.validation

import com.whispertype.android.dictation.DictationFailure
import com.whispertype.android.gemini.LanguageMode

/** Where the selected candidate came from. */
enum class CandidateSource {
    CLEANED,
    RAW,
}

/** Outcome of candidate selection. */
sealed interface CandidateSelection {

    /** A valid candidate to insert. */
    data class Selected(val text: String, val source: CandidateSource) : CandidateSelection

    /** No valid candidate; nothing may be inserted. */
    data class Rejected(val failure: DictationFailure) : CandidateSelection
}

/**
 * Selection chain (Implementation Plan §14): cleaned if valid, otherwise raw if valid,
 * otherwise rejection with code [CandidateSelector.CODE_NO_VALID_TRANSCRIPT].
 * Never inserts both. A candidate that is a pure repetition-duplicate of the other is
 * treated as invalid.
 */
object CandidateSelector {

    const val CODE_NO_VALID_TRANSCRIPT: String = "NO_VALID_TRANSCRIPT"

    /**
     * Selects the best candidate for [languageMode]. Null candidates are treated as invalid.
     */
    fun select(cleaned: String?, raw: String?, languageMode: LanguageMode): CandidateSelection {
        if (cleaned != null && isCleanedValid(cleaned, raw, languageMode)) {
            return CandidateSelection.Selected(cleaned, CandidateSource.CLEANED)
        }
        if (raw != null && isRawValid(raw, cleaned, languageMode)) {
            return CandidateSelection.Selected(raw, CandidateSource.RAW)
        }
        return CandidateSelection.Rejected(
            DictationFailure(
                code = CODE_NO_VALID_TRANSCRIPT,
                message = "No valid transcript was produced for this utterance.",
                recoverable = true,
            ),
        )
    }

    private fun isCleanedValid(cleaned: String, raw: String?, languageMode: LanguageMode): Boolean =
        CleanedTranscriptValidator.validate(cleaned, raw) is TranscriptValidationResult.Valid &&
            HinglishValidator.validate(cleaned, languageMode) is ScriptValidationResult.Valid &&
            (raw == null || !isRepetitionOf(cleaned, raw))

    private fun isRawValid(raw: String, cleaned: String?, languageMode: LanguageMode): Boolean =
        TranscriptValidator.validate(raw) is TranscriptValidationResult.Valid &&
            HinglishValidator.validate(raw, languageMode) is ScriptValidationResult.Valid &&
            (cleaned == null || !isRepetitionOf(raw, cleaned))

    private fun isRepetitionOf(repeated: String, base: String): Boolean {
        if (base.isEmpty() || repeated.length < base.length * 2) return false
        return repeated.replace(base, "").isBlank()
    }
}
