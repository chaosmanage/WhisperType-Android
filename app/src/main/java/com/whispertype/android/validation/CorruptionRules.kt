package com.whispertype.android.validation

/**
 * One named corruption rule: [predicate] returns true when [name] considers the
 * candidate corrupt, rejected with [rejectionReason].
 */
data class CorruptionRule(
    val name: String,
    val predicate: (String) -> Boolean,
    val rejectionReason: String,
)

/**
 * Port of the desktop WhisperType transcript quality rules (Implementation Plan §14).
 *
 * Heuristics documented here on purpose:
 * - Repeated dots/pipes: any ".." or "||" run, the classic stutter form.
 * - Conversational preambles: output that starts like a model reply ("Sure, ...",
 *   "Here is ...", "The transcript ...", "Certainly, ...", "Got it, ...", "Okay, ...",
 *   "Of course, ...", "No problem, ...") or with an em/en dash.
 * - Implausible length expansion: WhisperType caps a recording session at 60 seconds;
 *   at a 4 words/second ceiling with ~7.5 chars/word including spaces a single utterance
 *   cannot plausibly exceed ~240 words / ~1,800 chars. The rule is configurable and
 *   defaults sit just above that ceiling so legitimate full-length utterances pass while
 *   runaway model expansions are rejected. The "20x utterance count" proxy from the plan
 *   was not used because voice transcripts are typically punctuation-free, making any
 *   per-sentence threshold reject legitimate long dictations.
 * - Malformed output: any single non-whitespace character repeated 10+ times, any control
 *   character (other than tab/newline/carriage return), or any U+FFFD replacement
 *   character (stricter than a ratio check, still invisible to legitimate text).
 */
object CorruptionRules {

    const val DEFAULT_MAX_EXPANSION_WORDS: Int = 250
    const val DEFAULT_MAX_EXPANSION_CHARS: Int = 1800
    const val DEFAULT_CLEANED_OVER_RAW_RATIO: Double = 3.0

    /** The default rule set. */
    fun standard(): List<CorruptionRule> =
        standard(DEFAULT_MAX_EXPANSION_WORDS, DEFAULT_MAX_EXPANSION_CHARS)

    /** Rule set with a configurable length-expansion ceiling. */
    fun standard(maxExpansionWords: Int, maxExpansionChars: Int): List<CorruptionRule> = listOf(
        emptyOutput,
        whitespaceOnly,
        symbolOnly,
        punctuationOnly,
        repeatedDotsOrPipes,
        conversationalPreamble,
        implausibleLengthExpansion(maxExpansionWords, maxExpansionChars),
        repeatedCharacterRun,
        controlCharacters,
    )

    val emptyOutput: CorruptionRule = CorruptionRule(
        name = "emptyOutput",
        predicate = { it.isEmpty() },
        rejectionReason = "The transcript is empty.",
    )

    val whitespaceOnly: CorruptionRule = CorruptionRule(
        name = "whitespaceOnly",
        predicate = { it.isNotEmpty() && it.isBlank() },
        rejectionReason = "The transcript contains only whitespace.",
    )

    val punctuationOnly: CorruptionRule = CorruptionRule(
        name = "punctuationOnly",
        predicate = { PUNCTUATION_ONLY.matches(it) },
        rejectionReason = "The transcript contains no words.",
    )

    val symbolOnly: CorruptionRule = CorruptionRule(
        name = "symbolOnly",
        predicate = { SYMBOL_ONLY.matches(it) },
        rejectionReason = "The transcript contains only symbols.",
    )

    val repeatedDotsOrPipes: CorruptionRule = CorruptionRule(
        name = "repeatedDotsOrPipes",
        predicate = { it.contains("..") || it.contains("||") },
        rejectionReason = "The transcript contains repeated dot or pipe runs.",
    )

    val conversationalPreamble: CorruptionRule = CorruptionRule(
        name = "conversationalPreamble",
        predicate = { CONVERSATIONAL_PREAMBLE.containsMatchIn(it) },
        rejectionReason = "The transcript starts like a model reply rather than a dictation.",
    )

    val repeatedCharacterRun: CorruptionRule = CorruptionRule(
        name = "repeatedCharacterRun",
        predicate = { REPEATED_CHARACTER_RUN.containsMatchIn(it) },
        rejectionReason = "The transcript contains a long run of one repeated character.",
    )

    val controlCharacters: CorruptionRule = CorruptionRule(
        name = "controlCharacters",
        predicate = { CONTROL_CHARACTERS.containsMatchIn(it) || it.contains(REPLACEMENT_CHARACTER) },
        rejectionReason = "The transcript contains malformed or non-printable characters.",
    )

    /** Rejects transcripts longer than the configured per-utterance ceilings. */
    fun implausibleLengthExpansion(
        maxExpansionWords: Int = DEFAULT_MAX_EXPANSION_WORDS,
        maxExpansionChars: Int = DEFAULT_MAX_EXPANSION_CHARS,
    ): CorruptionRule = CorruptionRule(
        name = "implausibleLengthExpansion",
        predicate = { WORD_BOUNDARY.split(it.trim()).size > maxExpansionWords || it.length > maxExpansionChars },
        rejectionReason = "The transcript is implausibly long for a single dictation utterance.",
    )

    /**
     * True when one candidate is a pure repetition of the other, e.g. ("hello", "hellohello")
     * or ("abc", "abcabcabc"). Used to reject duplicated candidate content.
     */
    fun duplicatedCandidateContent(first: String, second: String): Boolean {
        fun isRepetitionOf(shorter: String, longer: String): Boolean {
            if (shorter.isEmpty() || longer.length < shorter.length * 2) return false
            return longer.replace(shorter, "").isBlank()
        }
        return isRepetitionOf(first, second) || isRepetitionOf(second, first)
    }

    private val PUNCTUATION_ONLY = Regex("^[\\p{P}\\p{S}\\s]+$")
    private val SYMBOL_ONLY = Regex("^[\\p{P}\\p{S}\\s]*\\p{S}[\\p{P}\\p{S}\\s]*$")
    private val CONVERSATIONAL_PREAMBLE = Regex(
        "^\\s*(?:(?:sure|certainly|okay|got\\s+it|of\\s+course|no\\s+problem)[,:!]\\s+" +
            "|here\\s+is\\s+|the\\s+transcript\\b|[\\u2014\\u2013]\\s+)",
        RegexOption.IGNORE_CASE,
    )
    private val REPEATED_CHARACTER_RUN = Regex("(\\S)\\1{9,}")
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private const val REPLACEMENT_CHARACTER: String = "\uFFFD"
    private val WORD_BOUNDARY = Regex("\\s+")
}
