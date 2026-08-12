package com.whispertype.android.core.transcript

/**
 * Session-local accumulator for streamed transcript messages.
 *
 * A stream may mix cumulative revisions with independent deltas. Cumulative
 * extensions and related corrections replace the provisional value. In
 * [appendDeltas] mode, all other messages are joined using the maximal
 * normalized-token overlap between the current suffix and the message prefix.
 * This makes repeated connector words deterministic and prevents an overlap
 * from being either duplicated or dropped.
 *
 * Every actual text change bumps [revisionCount].
 */
class TranscriptAccumulator(
    /** True for a source that can emit independent delta messages. */
    private val appendDeltas: Boolean = false,
) {
    /** Result for callers that need to distinguish a duplicate from a revision. */
    data class AcceptResult(
        val text: String?,
        val changed: Boolean,
    )

    var current: String? = null
        private set

    var revisionCount: Int = 0
        private set

    /**
     * 0.6.1 restart guard: index into the current text's tokens where an
     * in-progress replay of the head is being suppressed, or -1 when no replay
     * is active. When a long echo restarts (the server re-emits the accumulated
     * text plus a replay of its own beginning), the replayed tail must not be
     * appended again at the end.
     */
    private var replayPosition: Int = -1

    /**
     * Accepts one streamed [message], preserving the original nullable-text API.
     * Call [acceptWithResult] when the caller also needs the change signal.
     */
    fun accept(message: String): String? = acceptWithResult(message).text

    /** Accepts [message] and reports whether it changed the accumulated text. */
    fun acceptWithResult(message: String): AcceptResult {
        if (message.isBlank()) return unchanged()
        val existing = current
        if (existing == null) {
            replayPosition = -1
            return update(message)
        }
        if (message == existing) return unchanged()

        val existingTokens = tokens(existing)
        val messageTokens = tokens(message)
        if (sameTokens(existingTokens, messageTokens)) {
            return update(message)
        }
        if (existing.startsWith(message)) return unchanged()
        if (extendsFinalToken(existingTokens, messageTokens)) {
            return update(message)
        }
        if (isStrictPrefix(existingTokens, messageTokens)) {
            // A cumulative extension whose appended tail re-leads with the
            // existing text's own beginning is a spurious RESTART of the model's
            // reply (observed on long echoes), not new content: drop the
            // extension and start suppressing the replayed tail.
            val extension = messageTokens.drop(existingTokens.size)
            if (isPrefixOf(extension, existingTokens)) {
                replayPosition = extension.size
                return unchanged()
            }
            replayPosition = -1
            return update(message)
        }
        if (isStrictPrefix(messageTokens, existingTokens)) {
            return unchanged()
        }

        // While a replay is being suppressed, drop deltas that continue matching
        // the existing head at the replay position; resume on divergence.
        if (replayPosition >= 0 && consumeReplay(existingTokens, messageTokens) > 0) {
            return unchanged()
        }

        val likelyRevision = isLikelyRevision(existingTokens, messageTokens)
        if (appendDeltas) {
            // A cumulative correction can happen to begin with a token repeated
            // at the current tail. Prefer the well-supported revision before
            // interpreting that repeated token as a delta overlap.
            if (likelyRevision && commonPrefixLength(existingTokens, messageTokens) > 0) {
                return update(message)
            }

            val overlap = maximalSuffixPrefixOverlap(existingTokens, messageTokens)
            if (overlap > 0) {
                return update(mergeOverlap(existing, message, existingTokens, overlap))
            }

            if (likelyRevision) {
                return update(message)
            }
            return update(join(existing, message))
        }

        if (likelyRevision) {
            return update(message)
        }
        if (messageTokens.size < existingTokens.size) return unchanged()
        return update(message)
    }

    /** Returns the current settled transcript, or null if none exists yet. */
    fun settledText(): String? = current

    /** Clears [current] and [revisionCount] for a fresh session. */
    fun reset() {
        current = null
        revisionCount = 0
        replayPosition = -1
    }

    private fun update(candidate: String): AcceptResult {
        if (candidate == current) return unchanged()
        current = candidate
        revisionCount += 1
        return AcceptResult(text = current, changed = true)
    }

    private fun unchanged(): AcceptResult = AcceptResult(text = current, changed = false)

    /**
     * Replaces the overlapping suffix with the incoming spelling/punctuation
     * and keeps only the non-overlapping continuation.
     */
    private fun mergeOverlap(
        existing: String,
        message: String,
        existingTokens: List<Token>,
        overlap: Int,
    ): String {
        val overlapStart = existingTokens[existingTokens.size - overlap].start
        return join(existing.substring(0, overlapStart), message)
    }

    /**
     * Largest `k` for which the final `k` tokens of [existing] equal the first
     * `k` tokens of [message]. Looking at the complete suffix removes any
     * arbitrary tail window and remains deterministic for repeated words.
     */
    private fun maximalSuffixPrefixOverlap(
        existing: List<Token>,
        message: List<Token>,
    ): Int {
        val limit = minOf(existing.size, message.size)
        for (size in limit downTo 1) {
            val existingStart = existing.size - size
            var matches = true
            for (offset in 0 until size) {
                if (existing[existingStart + offset].normalized != message[offset].normalized) {
                    matches = false
                    break
                }
            }
            if (matches) return size
        }
        return 0
    }

    /**
     * Related, similarly sized messages are revisions rather than independent
     * deltas. Requiring ordered similarity plus a shared edge avoids treating
     * an unrelated equal-length message as a correction.
     */
    private fun isLikelyRevision(
        existing: List<Token>,
        message: List<Token>,
    ): Boolean {
        if (existing.isEmpty() || message.isEmpty()) return false
        val sharesEdge = commonPrefixLength(existing, message) > 0 ||
            commonSuffixLength(existing, message) > 0
        if (!sharesEdge) return false
        val shared = longestCommonSubsequenceLength(existing, message)
        if (shared < MIN_REVISION_SHARED_TOKENS) return false
        val similarity = shared.toDouble() / maxOf(existing.size, message.size)
        return similarity >= MIN_REVISION_SIMILARITY
    }

    private fun longestCommonSubsequenceLength(
        first: List<Token>,
        second: List<Token>,
    ): Int {
        var previous = IntArray(second.size + 1)
        for (firstToken in first) {
            val currentRow = IntArray(second.size + 1)
            for (secondIndex in second.indices) {
                currentRow[secondIndex + 1] =
                    if (firstToken.normalized == second[secondIndex].normalized) {
                        previous[secondIndex] + 1
                    } else {
                        maxOf(previous[secondIndex + 1], currentRow[secondIndex])
                    }
            }
            previous = currentRow
        }
        return previous[second.size]
    }

    private fun commonPrefixLength(first: List<Token>, second: List<Token>): Int {
        val limit = minOf(first.size, second.size)
        var count = 0
        while (count < limit && first[count].normalized == second[count].normalized) {
            count += 1
        }
        return count
    }

    private fun commonSuffixLength(first: List<Token>, second: List<Token>): Int {
        val limit = minOf(first.size, second.size)
        var count = 0
        while (
            count < limit &&
            first[first.lastIndex - count].normalized == second[second.lastIndex - count].normalized
        ) {
            count += 1
        }
        return count
    }

    private fun sameTokens(first: List<Token>, second: List<Token>): Boolean =
        first.size == second.size && first.indices.all {
            first[it].normalized == second[it].normalized
        }

    private fun extendsFinalToken(existing: List<Token>, message: List<Token>): Boolean {
        if (existing.size != message.size || existing.isEmpty()) return false
        if (existing.last().normalized.length < MIN_CORRECTABLE_FRAGMENT_LENGTH) return false
        for (index in 0 until existing.lastIndex) {
            if (existing[index].normalized != message[index].normalized) return false
        }
        val existingFinal = existing.last().normalized
        val messageFinal = message.last().normalized
        return messageFinal.length > existingFinal.length &&
            messageFinal.startsWith(existingFinal)
    }

    private fun isStrictPrefix(prefix: List<Token>, full: List<Token>): Boolean =
        prefix.isNotEmpty() &&
            prefix.size < full.size &&
            prefix.indices.all { prefix[it].normalized == full[it].normalized }

    /** True when [prefix] is empty or matches the leading tokens of [full]. */
    private fun isPrefixOf(prefix: List<Token>, full: List<Token>): Boolean =
        prefix.isEmpty() ||
            (prefix.size <= full.size &&
                prefix.indices.all { prefix[it].normalized == full[it].normalized })

    /**
     * 0.6.1 restart guard: consumes as much of [message] as continues matching
     * [existing] at [replayPosition], advancing the replay. Returns the number
     * of tokens consumed (0 on divergence, which ends the replay). When the
     * replay reaches the end of [existing] it is fully suppressed and reset.
     */
    private fun consumeReplay(existing: List<Token>, message: List<Token>): Int {
        if (replayPosition < 0 || replayPosition >= existing.size || message.isEmpty()) return 0
        if (message[0].normalized != existing[replayPosition].normalized) {
            replayPosition = -1
            return 0
        }
        val limit = minOf(message.size, existing.size - replayPosition)
        var consumed = 0
        while (consumed < limit &&
            message[consumed].normalized == existing[replayPosition + consumed].normalized
        ) {
            consumed++
        }
        replayPosition += consumed
        if (replayPosition >= existing.size) replayPosition = -1
        return consumed
    }

    /** Joins a delta on a natural text boundary. */
    private fun join(existing: String, message: String): String {
        val continuation = message.trim()
        if (continuation.isEmpty()) return existing
        if (existing.isEmpty()) return continuation
        if (continuation.first() in ATTACHED_PUNCTUATION) {
            return existing.trimEnd() + continuation
        }
        if (existing.last().isWhitespace()) {
            return existing + continuation
        }
        return "$existing $continuation"
    }

    private fun tokens(text: String): List<Token> {
        val result = ArrayList<Token>()
        var tokenStart = -1
        val normalized = StringBuilder()

        fun flush() {
            if (tokenStart >= 0) {
                result.add(
                    Token(
                        normalized = normalized.toString(),
                        start = tokenStart,
                    ),
                )
                tokenStart = -1
                normalized.setLength(0)
            }
        }

        for (index in text.indices) {
            val character = text[index]
            if (character.isLetterOrDigit()) {
                if (tokenStart < 0) tokenStart = index
                normalized.append(character.lowercaseChar())
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    private data class Token(
        val normalized: String,
        val start: Int,
    )

    private companion object {
        const val MIN_REVISION_SHARED_TOKENS: Int = 2
        const val MIN_REVISION_SIMILARITY: Double = 0.5
        const val MIN_CORRECTABLE_FRAGMENT_LENGTH: Int = 3
        val ATTACHED_PUNCTUATION: Set<Char> = setOf('.', ',', '!', '?', ';', ':', '%', ')', ']', '}')
    }
}
