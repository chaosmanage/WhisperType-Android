package com.whispertype.android.core.transcript

/**
 * The independently streamed transcript channel that supplied evidence.
 */
enum class TranscriptEvidenceSource {
    INPUT,
    ECHO,
    REPAIR,
}

/**
 * Tunable, pure quality thresholds used by [TranscriptEvidenceLedger].
 */
data class TranscriptQualityPolicy(
    val minCoverageRatio: Double = TranscriptCompleteness.DEFAULT_MIN_RATIO,
    val minOrderedCoverage: Double = TranscriptCompleteness.DEFAULT_MIN_ORDERED_COVERAGE,
    val echoOnlyMinDurationCoverage: Double =
        TranscriptCompleteness.DEFAULT_DURATION_MIN_COVERAGE,
    val wordsPerSecond: Double = TranscriptCompleteness.DEFAULT_WORDS_PER_SECOND,
    val longDurationMs: Long = TranscriptCompleteness.DEFAULT_LONG_DURATION_MS,
) {
    init {
        require(minCoverageRatio.isFinite() && minCoverageRatio in 0.0..1.0)
        require(minOrderedCoverage.isFinite() && minOrderedCoverage in 0.0..1.0)
        require(
            echoOnlyMinDurationCoverage.isFinite() &&
                echoOnlyMinDurationCoverage in 0.0..1.0,
        )
        require(wordsPerSecond.isFinite() && wordsPerSecond > 0.0)
        require(longDurationMs >= 0L)
    }
}

/**
 * A quality-first result. Lifecycle and quiet-period readiness remain the
 * coordinator's responsibility; this type answers only what the text evidence
 * supports at the instant it is evaluated.
 */
sealed interface TranscriptQualityDecision {
    /**
     * A polished candidate preserves the strongest available input baseline.
     */
    data class UsePolished(
        val text: String,
        val source: TranscriptEvidenceSource,
        val assessment: TranscriptCompleteness.Assessment,
    ) : TranscriptQualityDecision

    /**
     * Input is available, but no polished hypothesis safely covers it.
     *
     * A styled coordinator may repair [text] before insertion. A coordinator
     * that does not repair can use it as the lossless fallback.
     */
    data class UseInput(
        val text: String,
        val rejectedPolishedAssessment: TranscriptCompleteness.Assessment?,
    ) : TranscriptQualityDecision

    /**
     * No input baseline exists, but duration evidence makes this candidate
     * plausible rather than an obvious short summary of a long recording.
     */
    data class UseEchoOnly(
        val text: String,
        val source: TranscriptEvidenceSource,
        val assessment: TranscriptCompleteness.DurationAssessment,
    ) : TranscriptQualityDecision

    /** No candidate is currently safe to use. */
    data class Insufficient(
        val reason: Reason,
        val durationAssessment: TranscriptCompleteness.DurationAssessment? = null,
    ) : TranscriptQualityDecision {
        enum class Reason {
            NO_TRANSCRIPT,
            DURATION_REQUIRED,
            DURATION_IMPLAUSIBLE,
            EVIDENCE_OVERFLOW,
        }
    }
}

/**
 * Bounded, session-local evidence for event-driven transcript settlement.
 *
 * Exact non-blank revisions are retained per source. [hypotheses] also derives
 * replacement and overlap-joined interpretations, because a server channel may
 * switch among cumulative, corrective, and delta-shaped messages. Both the
 * revision count and each accepted revision's character count are capped, so
 * retained memory has a fixed upper bound.
 *
 * This class owns no timers and performs no I/O. A coordinator records events,
 * observes lifecycle/quiet barriers itself, and calls [qualityDecision] after
 * each relevant event.
 */
class TranscriptEvidenceLedger(
    val maxRevisionsPerSource: Int = DEFAULT_MAX_REVISIONS_PER_SOURCE,
    val maxCharsPerRevision: Int = DEFAULT_MAX_CHARS_PER_REVISION,
) {
    init {
        require(maxRevisionsPerSource > 0)
        require(maxCharsPerRevision > 0)
    }

    /** Outcome of recording one exact streamed revision. */
    data class RecordResult(
        val status: Status,
        val retainedRevisionCount: Int,
        val evictedOldest: Boolean,
        val hypothesisOverflowed: Boolean,
    ) {
        val changed: Boolean
            get() = status == Status.ADDED

        val accepted: Boolean
            get() = status == Status.ADDED || status == Status.DUPLICATE

        enum class Status {
            ADDED,
            DUPLICATE,
            BLANK_IGNORED,
            TOO_LARGE,
        }
    }

    /** Immutable view of one source. Text is never logged by this class. */
    data class SourceSnapshot(
        val source: TranscriptEvidenceSource,
        val revisions: List<String>,
        val hypotheses: List<String>,
        val hypothesisOverflowed: Boolean,
    ) {
        val retainedRevisionCount: Int
            get() = revisions.size
    }

    private val states: Map<TranscriptEvidenceSource, SourceState> =
        TranscriptEvidenceSource.entries.associateWith { SourceState() }

    /** Records [text], suppressing only a consecutive exact duplicate. */
    fun record(source: TranscriptEvidenceSource, text: String): RecordResult {
        val state = stateFor(source)
        val revisions = state.revisions
        if (text.isBlank()) {
            return RecordResult(
                status = RecordResult.Status.BLANK_IGNORED,
                retainedRevisionCount = revisions.size,
                evictedOldest = false,
                hypothesisOverflowed = state.hypothesisOverflowed,
            )
        }
        if (text.length > maxCharsPerRevision) {
            state.hypothesisOverflowed = true
            return RecordResult(
                status = RecordResult.Status.TOO_LARGE,
                retainedRevisionCount = revisions.size,
                evictedOldest = false,
                hypothesisOverflowed = state.hypothesisOverflowed,
            )
        }
        if (revisions.lastOrNull() == text) {
            return RecordResult(
                status = RecordResult.Status.DUPLICATE,
                retainedRevisionCount = revisions.size,
                evictedOldest = false,
                hypothesisOverflowed = state.hypothesisOverflowed,
            )
        }

        val evicted = revisions.size == maxRevisionsPerSource
        if (evicted) revisions.removeAt(0)
        revisions.add(text)
        state.hypothesisOverflowed = state.hypothesisOverflowed ||
            acceptBounded(state.replacement, text) ||
            acceptBounded(state.overlapJoined, text)
        return RecordResult(
            status = RecordResult.Status.ADDED,
            retainedRevisionCount = revisions.size,
            evictedOldest = evicted,
            hypothesisOverflowed = state.hypothesisOverflowed,
        )
    }

    fun recordInput(text: String): RecordResult = record(TranscriptEvidenceSource.INPUT, text)

    fun recordEcho(text: String): RecordResult = record(TranscriptEvidenceSource.ECHO, text)

    fun recordRepair(text: String): RecordResult = record(TranscriptEvidenceSource.REPAIR, text)

    /** Exact retained revisions, oldest first. */
    fun revisions(source: TranscriptEvidenceSource): List<String> =
        stateFor(source).revisions.toList()

    /**
     * Distinct latest, longest, replacement, and overlap-joined interpretations
     * for [source]. The latest exact revision is always considered first.
     */
    fun hypotheses(source: TranscriptEvidenceSource): List<String> {
        val state = stateFor(source)
        val revisions = state.revisions
        if (revisions.isEmpty()) return emptyList()

        val result = LinkedHashSet<String>()
        result.add(revisions.last())
        result.add(
            revisions.maxWithOrNull(
                compareBy<String> { TranscriptCompleteness.contentWords(it).size }
                    .thenBy { it.length },
            ) ?: revisions.last(),
        )

        state.replacement.settledText()?.let(result::add)
        state.overlapJoined.settledText()?.let(result::add)
        return result.toList()
    }

    fun snapshot(source: TranscriptEvidenceSource): SourceSnapshot =
        SourceSnapshot(
            source = source,
            revisions = revisions(source),
            hypotheses = hypotheses(source),
            hypothesisOverflowed = stateFor(source).hypothesisOverflowed,
        )

    /** Number of exact revisions retained across all sources. */
    val retainedRevisionCount: Int
        get() = states.values.sumOf { it.revisions.size }

    /**
     * Selects the strongest safe candidate from current evidence.
     *
     * Repair hypotheses outrank direct echo hypotheses. With input available,
     * a polished hypothesis must pass [TranscriptCompleteness]. Without input,
     * a duration is mandatory and the echo/repair must pass duration sanity.
     */
    fun qualityDecision(
        durationMs: Long? = null,
        policy: TranscriptQualityPolicy = TranscriptQualityPolicy(),
    ): TranscriptQualityDecision {
        if (stateFor(TranscriptEvidenceSource.INPUT).hypothesisOverflowed) {
            return TranscriptQualityDecision.Insufficient(
                reason = TranscriptQualityDecision.Insufficient.Reason.EVIDENCE_OVERFLOW,
            )
        }
        val input = strongestHypothesis(TranscriptEvidenceSource.INPUT)
        val polished = polishedHypotheses(strongestFirst = input == null)

        if (input != null) {
            var bestRejected: TranscriptCompleteness.Assessment? = null
            for (candidate in polished) {
                val assessment = TranscriptCompleteness.assess(
                    echo = candidate.text,
                    raw = input,
                    minRatio = policy.minCoverageRatio,
                    minOrderedCoverage = policy.minOrderedCoverage,
                )
                if (assessment.isComplete) {
                    return TranscriptQualityDecision.UsePolished(
                        text = candidate.text,
                        source = candidate.source,
                        assessment = assessment,
                    )
                }
                if (bestRejected == null || assessment.orderedCoverage > bestRejected.orderedCoverage) {
                    bestRejected = assessment
                }
            }
            return TranscriptQualityDecision.UseInput(
                text = input,
                rejectedPolishedAssessment = bestRejected,
            )
        }

        if (polished.isEmpty()) {
            return TranscriptQualityDecision.Insufficient(
                reason = if (hasPolishedOverflow()) {
                    TranscriptQualityDecision.Insufficient.Reason.EVIDENCE_OVERFLOW
                } else {
                    TranscriptQualityDecision.Insufficient.Reason.NO_TRANSCRIPT
                },
            )
        }
        if (durationMs == null) {
            return TranscriptQualityDecision.Insufficient(
                reason = TranscriptQualityDecision.Insufficient.Reason.DURATION_REQUIRED,
            )
        }

        var bestRejected: TranscriptCompleteness.DurationAssessment? = null
        for (candidate in polished) {
            val assessment = TranscriptCompleteness.assessDuration(
                transcript = candidate.text,
                durationMs = durationMs,
                minCoverageRatio = policy.echoOnlyMinDurationCoverage,
                wordsPerSecond = policy.wordsPerSecond,
                longDurationMs = policy.longDurationMs,
            )
            if (assessment.isPlausible) {
                return TranscriptQualityDecision.UseEchoOnly(
                    text = candidate.text,
                    source = candidate.source,
                    assessment = assessment,
                )
            }
            if (bestRejected == null || assessment.coverageRatio > bestRejected.coverageRatio) {
                bestRejected = assessment
            }
        }
        return TranscriptQualityDecision.Insufficient(
            reason = TranscriptQualityDecision.Insufficient.Reason.DURATION_IMPLAUSIBLE,
            durationAssessment = bestRejected,
        )
    }

    /** Concise alias for reducers that reevaluate after every event. */
    fun decide(
        durationMs: Long? = null,
        policy: TranscriptQualityPolicy = TranscriptQualityPolicy(),
    ): TranscriptQualityDecision = qualityDecision(durationMs, policy)

    fun clear() {
        states.values.forEach(SourceState::clear)
    }

    private fun strongestHypothesis(source: TranscriptEvidenceSource): String? =
        hypotheses(source).maxWithOrNull(
            compareBy<String> { TranscriptCompleteness.contentWords(it).size }
                .thenBy { it.length },
        )

    private fun polishedHypotheses(strongestFirst: Boolean): List<SourcedText> {
        val result = ArrayList<SourcedText>()
        for (source in POLISHED_SOURCE_ORDER) {
            if (stateFor(source).hypothesisOverflowed) continue
            val sourceHypotheses = hypotheses(source)
            val ordered = if (strongestFirst) {
                sourceHypotheses.sortedWith(
                    compareByDescending<String> {
                        TranscriptCompleteness.contentWords(it).size
                    }.thenByDescending { it.length },
                )
            } else {
                sourceHypotheses
            }
            ordered.forEach { result.add(SourcedText(source, it)) }
        }
        return result
    }

    private fun acceptBounded(accumulator: TranscriptAccumulator, text: String): Boolean {
        val previous = accumulator.settledText()
        val updated = accumulator.accept(text)
        if (updated == null || updated.length <= maxCharsPerRevision) return false

        accumulator.reset()
        previous?.let(accumulator::accept)
        return true
    }

    private fun hasPolishedOverflow(): Boolean =
        POLISHED_SOURCE_ORDER.any { stateFor(it).hypothesisOverflowed }

    private fun stateFor(source: TranscriptEvidenceSource): SourceState =
        checkNotNull(states[source])

    private class SourceState {
        val revisions: MutableList<String> = ArrayList()
        val replacement: TranscriptAccumulator = TranscriptAccumulator()
        val overlapJoined: TranscriptAccumulator = TranscriptAccumulator(appendDeltas = true)
        var hypothesisOverflowed: Boolean = false

        fun clear() {
            revisions.clear()
            replacement.reset()
            overlapJoined.reset()
            hypothesisOverflowed = false
        }
    }

    private data class SourcedText(
        val source: TranscriptEvidenceSource,
        val text: String,
    )

    companion object {
        const val DEFAULT_MAX_REVISIONS_PER_SOURCE: Int = 12
        const val DEFAULT_MAX_CHARS_PER_REVISION: Int = 16_384

        private val POLISHED_SOURCE_ORDER: List<TranscriptEvidenceSource> =
            listOf(TranscriptEvidenceSource.REPAIR, TranscriptEvidenceSource.ECHO)
    }
}
