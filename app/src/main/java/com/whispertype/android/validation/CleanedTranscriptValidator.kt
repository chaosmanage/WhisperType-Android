package com.whispertype.android.validation

/**
 * Adds cleaned-specific checks on top of [TranscriptValidator]:
 * - cleaned must not be longer than raw by more than 3x (a cleaned transcript is a
 *   normalization, never an expansion),
 * - cleaned must not contain asterisk-wrapped marker text such as "*transcript*".
 */
object CleanedTranscriptValidator {

    /**
     * Validates [cleaned]. [raw] may be null; the length-ratio check only applies when a
     * raw candidate exists.
     */
    fun validate(cleaned: String, raw: String?): TranscriptValidationResult {
        val base = TranscriptValidator.validate(cleaned)
        if (base is TranscriptValidationResult.Rejected) return base
        if (raw != null && cleaned.length > CorruptionRules.DEFAULT_CLEANED_OVER_RAW_RATIO * raw.length) {
            return TranscriptValidationResult.Rejected(
                ruleName = "cleanedLongerThanRaw",
                reason = "The cleaned transcript is implausibly longer than the raw transcript.",
            )
        }
        if (ASTERISK_MARKER.containsMatchIn(cleaned)) {
            return TranscriptValidationResult.Rejected(
                ruleName = "cleanedMarkerText",
                reason = "The cleaned transcript contains model-style markers.",
            )
        }
        return TranscriptValidationResult.Valid
    }

    private val ASTERISK_MARKER = Regex("\\*[^*\\n]{1,80}\\*")
}
