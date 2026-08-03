package com.whispertype.android.validation

/** Result of validating one transcript candidate against the corruption rules. */
sealed interface TranscriptValidationResult {
    data object Valid : TranscriptValidationResult
    data class Rejected(val ruleName: String, val reason: String) : TranscriptValidationResult
}

/** Applies [CorruptionRules] to a candidate. The first matching rule wins. */
object TranscriptValidator {

    /**
     * Validates [candidate] against [rules]. An empty candidate fails the emptyOutput rule;
     * callers that treat null as invalid should not call this with null.
     */
    fun validate(candidate: String, rules: List<CorruptionRule> = CorruptionRules.standard()): TranscriptValidationResult {
        for (rule in rules) {
            if (rule.predicate(candidate)) {
                return TranscriptValidationResult.Rejected(rule.name, rule.rejectionReason)
            }
        }
        return TranscriptValidationResult.Valid
    }
}
