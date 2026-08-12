package com.whispertype.android.core.transcript

/**
 * Pure completeness checks for streamed transcript sources.
 *
 * Length alone cannot distinguish a complete rewrite from unrelated text.
 * [assess] therefore combines normalized token coverage, ordered coverage, a
 * minimum length ratio, and mandatory preservation of high-signal anchors.
 * Diagnostics contain only aggregate counts and ratios; transcript or anchor
 * contents are never exposed.
 */
object TranscriptCompleteness {

    /** Aggregate reason for a completeness decision. */
    enum class Diagnosis {
        COMPLETE,
        RAW_EMPTY,
        ECHO_EMPTY,
        MISSING_ANCHOR,
        TOO_SHORT,
        LOW_ORDERED_COVERAGE,
        LOW_TOKEN_COVERAGE,
    }

    /** Content-free diagnostic returned by [assess]. */
    data class Assessment(
        val isComplete: Boolean,
        val diagnosis: Diagnosis,
        val rawTokenCount: Int,
        val echoTokenCount: Int,
        val lengthRatio: Double,
        val tokenCoverage: Double,
        val orderedCoverage: Double,
        val requiredAnchorCount: Int,
        val preservedAnchorCount: Int,
    )

    /** Aggregate reason for an echo-only duration plausibility decision. */
    enum class DurationDiagnosis {
        PLAUSIBLE,
        BLANK_TRANSCRIPT,
        TOO_FEW_WORDS,
    }

    /** Content-free diagnostic returned by [assessDuration]. */
    data class DurationAssessment(
        val isPlausible: Boolean,
        val diagnosis: DurationDiagnosis,
        val transcriptWordCount: Int,
        val expectedWordCount: Double,
        val coverageRatio: Double,
    )

    /**
     * Backwards-compatible boolean completeness API. See [assess] for the
     * aggregate decision details.
     */
    fun covers(
        echo: String,
        raw: String,
        minRatio: Double = DEFAULT_MIN_RATIO,
    ): Boolean = assess(echo, raw, minRatio).isComplete

    /**
     * Determines whether [echo] plausibly preserves [raw].
     *
     * Common vocal fillers are excluded from the ordered comparison. Other
     * wording may change as long as enough normalized tokens remain in order.
     * Every number, date word, URL, email, and identifier-like anchor found in
     * [raw] must still occur in [echo], with multiplicity and order preserved.
     */
    fun assess(
        echo: String,
        raw: String,
        minRatio: Double = DEFAULT_MIN_RATIO,
        minOrderedCoverage: Double = DEFAULT_MIN_ORDERED_COVERAGE,
    ): Assessment {
        val rawWords = coverageWords(raw)
        val echoWords = coverageWords(echo)
        val rawAnchors = anchors(raw, includePotentialIdentifiers = false)
        val echoAnchors = anchors(echo, includePotentialIdentifiers = true)
        val preservedAnchors = preservedAnchorCount(rawAnchors, echoAnchors)
        val lengthRatio = ratio(echoWords.size, rawWords.size)
        val tokenCoverage = ratio(
            sharedTokenCount(rawWords, echoWords),
            rawWords.size,
        )
        val orderedCoverage = ratio(
            longestCommonSubsequenceLength(rawWords, echoWords),
            rawWords.size,
        )

        val diagnosis = when {
            rawWords.isEmpty() -> Diagnosis.RAW_EMPTY
            echoWords.isEmpty() -> Diagnosis.ECHO_EMPTY
            preservedAnchors < rawAnchors.size -> Diagnosis.MISSING_ANCHOR
            lengthRatio < minRatio -> Diagnosis.TOO_SHORT
            orderedCoverage < minOrderedCoverage -> Diagnosis.LOW_ORDERED_COVERAGE
            tokenCoverage < minRatio -> Diagnosis.LOW_TOKEN_COVERAGE
            else -> Diagnosis.COMPLETE
        }
        return Assessment(
            isComplete = diagnosis == Diagnosis.COMPLETE,
            diagnosis = diagnosis,
            rawTokenCount = rawWords.size,
            echoTokenCount = echoWords.size,
            lengthRatio = lengthRatio,
            tokenCoverage = tokenCoverage,
            orderedCoverage = orderedCoverage,
            requiredAnchorCount = rawAnchors.size,
            preservedAnchorCount = preservedAnchors,
        )
    }

    /**
     * A pure length expectation for the recorded audio: how many words the
     * user plausibly spoke in [durationMs] at [wordsPerSecond].
     */
    fun expectedWords(
        durationMs: Long,
        wordsPerSecond: Double = DEFAULT_WORDS_PER_SECOND,
    ): Double = durationMs / 1000.0 * wordsPerSecond

    /**
     * Boolean helper for an echo that has no raw transcript to compare against.
     * Short recordings receive a grace period; long recordings must contain at
     * least [minCoverageRatio] of the duration-based word expectation.
     */
    fun isPlausibleForDuration(
        transcript: String,
        durationMs: Long,
        minCoverageRatio: Double = DEFAULT_DURATION_MIN_COVERAGE,
        wordsPerSecond: Double = DEFAULT_WORDS_PER_SECOND,
        longDurationMs: Long = DEFAULT_LONG_DURATION_MS,
    ): Boolean = assessDuration(
        transcript = transcript,
        durationMs = durationMs,
        minCoverageRatio = minCoverageRatio,
        wordsPerSecond = wordsPerSecond,
        longDurationMs = longDurationMs,
    ).isPlausible

    /** Aggregate diagnostic form of [isPlausibleForDuration]. */
    fun assessDuration(
        transcript: String,
        durationMs: Long,
        minCoverageRatio: Double = DEFAULT_DURATION_MIN_COVERAGE,
        wordsPerSecond: Double = DEFAULT_WORDS_PER_SECOND,
        longDurationMs: Long = DEFAULT_LONG_DURATION_MS,
    ): DurationAssessment {
        val wordCount = contentWords(transcript).size
        val expected = expectedWords(durationMs.coerceAtLeast(0L), wordsPerSecond)
            .coerceAtLeast(0.0)
        val coverage = if (expected == 0.0) {
            if (wordCount == 0) 0.0 else 1.0
        } else {
            wordCount / expected
        }
        val plausible = wordCount > 0 &&
            (durationMs < longDurationMs || coverage >= minCoverageRatio)
        val diagnosis = when {
            wordCount == 0 -> DurationDiagnosis.BLANK_TRANSCRIPT
            plausible -> DurationDiagnosis.PLAUSIBLE
            else -> DurationDiagnosis.TOO_FEW_WORDS
        }
        return DurationAssessment(
            isPlausible = plausible,
            diagnosis = diagnosis,
            transcriptWordCount = wordCount,
            expectedWordCount = expected,
            coverageRatio = coverage,
        )
    }

    /** Lower-cased letter/digit runs, matching the selector's tokenization. */
    fun contentWords(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result.add(current.toString())
                current.setLength(0)
            }
        }
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                current.append(ch.lowercaseChar())
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    private fun coverageWords(text: String): List<String> =
        contentWords(NUMERIC_GROUPING_SEPARATOR_REGEX.replace(text, ""))
            .filterNot { it in OPTIONAL_FILLERS }

    private fun sharedTokenCount(required: List<String>, available: List<String>): Int {
        val remaining = available.groupingBy { it }.eachCount().toMutableMap()
        var shared = 0
        for (token in required) {
            val count = remaining[token] ?: continue
            shared += 1
            if (count == 1) {
                remaining.remove(token)
            } else {
                remaining[token] = count - 1
            }
        }
        return shared
    }

    private fun longestCommonSubsequenceLength(
        first: List<String>,
        second: List<String>,
    ): Int {
        var previous = IntArray(second.size + 1)
        for (firstWord in first) {
            val current = IntArray(second.size + 1)
            for (secondIndex in second.indices) {
                current[secondIndex + 1] =
                    if (firstWord == second[secondIndex]) {
                        previous[secondIndex] + 1
                    } else {
                        maxOf(previous[secondIndex + 1], current[secondIndex])
                    }
            }
            previous = current
        }
        return previous[second.size]
    }

    private fun anchors(
        text: String,
        includePotentialIdentifiers: Boolean,
    ): List<Anchor> {
        val result = ArrayList<Anchor>()
        EMAIL_REGEX.findAll(text).forEach {
            result.add(
                Anchor(
                    kind = AnchorKind.NETWORK,
                    normalized = canonicalNetworkAnchor(it.value),
                    position = it.range.first,
                ),
            )
        }
        URL_REGEX.findAll(text).forEach {
            result.add(
                Anchor(
                    kind = AnchorKind.NETWORK,
                    normalized = canonicalNetworkAnchor(it.value),
                    position = it.range.first,
                ),
            )
        }
        NUMBER_REGEX.findAll(text).forEach {
            result.add(
                Anchor(
                    kind = AnchorKind.NUMBER,
                    normalized = it.value.replace(",", ""),
                    position = it.range.first,
                ),
            )
        }
        IDENTIFIER_CANDIDATE_REGEX.findAll(text).forEach {
            val candidate = it.value
            if (includePotentialIdentifiers || isIdentifierLike(candidate)) {
                result.add(
                    Anchor(
                        kind = AnchorKind.IDENTIFIER,
                        normalized = candidate.lowercase(),
                        position = it.range.first,
                    ),
                )
            }
        }
        result.addAll(dateAnchors(text))
        result.sortWith(
            compareBy<Anchor> { it.position }
                .thenBy { it.kind.ordinal }
                .thenBy { it.normalized },
        )
        return result
    }

    private fun preservedAnchorCount(
        required: List<Anchor>,
        available: List<Anchor>,
    ): Int {
        var availableIndex = 0
        var preserved = 0
        for (anchor in required) {
            while (
                availableIndex < available.size &&
                !sameAnchor(anchor, available[availableIndex])
            ) {
                availableIndex += 1
            }
            if (availableIndex < available.size) {
                preserved += 1
                availableIndex += 1
            }
        }
        return preserved
    }

    private fun sameAnchor(first: Anchor, second: Anchor): Boolean =
        first.kind == second.kind && first.normalized == second.normalized

    private fun isIdentifierLike(candidate: String): Boolean {
        val hasLetter = candidate.any { it.isLetter() }
        val hasDigit = candidate.any { it.isDigit() }
        val hasLowercase = candidate.any { it.isLowerCase() }
        val hasUppercase = candidate.any { it.isUpperCase() }
        val hasInternalUppercase = candidate.drop(1).any { it.isUpperCase() }
        val isAllUppercase = candidate.length >= 2 && hasUppercase && !hasLowercase
        return candidate.contains('_') ||
            (hasLetter && hasDigit) ||
            (hasLowercase && hasInternalUppercase) ||
            isAllUppercase
    }

    private fun dateAnchors(text: String): List<Anchor> {
        val words = WORD_REGEX.findAll(text).map {
            PositionedWord(
                normalized = it.value.lowercase(),
                position = it.range.first,
            )
        }.toList()
        val result = ArrayList<Anchor>()
        for (index in words.indices) {
            val word = words[index]
            if (word.normalized in UNAMBIGUOUS_DATE_WORDS) {
                result.add(word.asAnchor())
            } else if (word.normalized in AMBIGUOUS_MONTH_WORDS) {
                val previousIsNumber =
                    index > 0 && words[index - 1].normalized.any { it.isDigit() }
                val nextIsNumber =
                    index < words.lastIndex && words[index + 1].normalized.any { it.isDigit() }
                val previousMarksDate =
                    index > 0 && words[index - 1].normalized in DATE_PREPOSITIONS
                val nextIsDay =
                    index < words.lastIndex && words[index + 1].normalized in DATE_DAY_WORDS
                if (previousIsNumber || nextIsNumber || previousMarksDate || nextIsDay) {
                    result.add(word.asAnchor())
                }
            }

            if (word.normalized in ALL_MONTH_WORDS) {
                if (
                    index < words.lastIndex &&
                    words[index + 1].normalized in DATE_DAY_WORDS
                ) {
                    result.add(words[index + 1].asAnchor())
                }
                if (
                    index >= 2 &&
                    words[index - 1].normalized == "of" &&
                    words[index - 2].normalized in DATE_DAY_WORDS
                ) {
                    result.add(words[index - 2].asAnchor())
                }
            }
        }
        return result
    }

    private fun canonicalNetworkAnchor(value: String): String =
        value.trimEnd { it in TRAILING_NETWORK_PUNCTUATION }.lowercase()

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private enum class AnchorKind {
        NUMBER,
        DATE,
        NETWORK,
        IDENTIFIER,
    }

    private data class Anchor(
        val kind: AnchorKind,
        val normalized: String,
        val position: Int,
    )

    private data class PositionedWord(
        val normalized: String,
        val position: Int,
    ) {
        fun asAnchor(): Anchor = Anchor(
            kind = AnchorKind.DATE,
            normalized = normalized,
            position = position,
        )
    }

    /** Default minimum normalized token coverage and length ratio. */
    const val DEFAULT_MIN_RATIO: Double = 0.6

    /** Default minimum fraction of raw tokens preserved in order. */
    const val DEFAULT_MIN_ORDERED_COVERAGE: Double = DEFAULT_MIN_RATIO

    /** Default speaking rate used to derive expected words from duration. */
    const val DEFAULT_WORDS_PER_SECOND: Double = 2.2

    /** Conservative minimum word expectation used only for long recordings. */
    const val DEFAULT_DURATION_MIN_COVERAGE: Double = 0.1

    /** Durations below this threshold are not rejected by the duration helper. */
    const val DEFAULT_LONG_DURATION_MS: Long = 30_000L

    private val OPTIONAL_FILLERS: Set<String> = setOf(
        "ah",
        "eh",
        "er",
        "erm",
        "hm",
        "hmm",
        "mm",
        "uh",
        "uhh",
        "um",
        "umm",
    )

    private val UNAMBIGUOUS_MONTH_WORDS: Set<String> = setOf(
        "january",
        "february",
        "april",
        "june",
        "july",
        "august",
        "september",
        "october",
        "november",
        "december",
    )

    private val AMBIGUOUS_MONTH_WORDS: Set<String> = setOf("march", "may")

    private val ALL_MONTH_WORDS: Set<String> =
        UNAMBIGUOUS_MONTH_WORDS + AMBIGUOUS_MONTH_WORDS

    private val UNAMBIGUOUS_DATE_WORDS: Set<String> = UNAMBIGUOUS_MONTH_WORDS + setOf(
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday",
        "today",
        "tomorrow",
        "yesterday",
    )

    private val DATE_PREPOSITIONS: Set<String> =
        setOf("after", "before", "by", "during", "from", "in", "of", "on", "until")

    private val DATE_DAY_WORDS: Set<String> = setOf(
        "first",
        "second",
        "third",
        "fourth",
        "fifth",
        "sixth",
        "seventh",
        "eighth",
        "ninth",
        "tenth",
        "eleventh",
        "twelfth",
        "thirteenth",
        "fourteenth",
        "fifteenth",
        "sixteenth",
        "seventeenth",
        "eighteenth",
        "nineteenth",
        "twentieth",
        "thirtieth",
        "thirtyfirst",
    )

    private val EMAIL_REGEX: Regex =
        Regex("""(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?![a-z0-9._%+-])""")

    private val URL_REGEX: Regex = Regex(
        """(?i)\b(?:(?:https?://|www\.)[^\s<>()]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?:[/?#][^\s<>()]*)?)""",
    )

    private val NUMBER_REGEX: Regex =
        Regex("""(?<![\p{L}\p{N}])\d[\d,]*(?:\.\d+)?(?![\p{L}\p{N}])""")

    private val NUMERIC_GROUPING_SEPARATOR_REGEX: Regex =
        Regex("""(?<=\d),(?=\d{3}(?:\D|$))""")

    private val IDENTIFIER_CANDIDATE_REGEX: Regex =
        Regex("""[\p{L}\p{N}]+(?:[_-][\p{L}\p{N}]+)*""")

    private val WORD_REGEX: Regex = Regex("""[\p{L}\p{N}]+""")

    private val TRAILING_NETWORK_PUNCTUATION: Set<Char> =
        setOf('.', ',', '!', '?', ';', ':', ')', ']', '}')
}
