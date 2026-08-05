package com.whispertype.android.core.transcript

/**
 * Session-local accumulator for the current input transcript (remediation plan
 * Release E1/E2).
 *
 * Tracks ONE current cumulative revision of the input transcription streamed
 * by the Gemini Live server, which may deliver independent deltas, cumulative
 * revisions, corrected revisions, or a mixture. Merge priority:
 *
 *  1. Empty/blank message: ignored (no change, no revision bump).
 *  2. Exact duplicate of [current]: ignored.
 *  3. No current value yet: accepted as-is.
 *  4. Cumulative extension (message starts with [current] on a word boundary):
 *     merged, so the longer cumulative value wins.
 *  5. Reverse prefix (message is a shorter/partial revision of [current]):
 *     ignored; the longer cumulative value wins.
 *  6. Otherwise (correction/replacement of a prior provisional value):
 *     replaced.
 *
 * Every accepted change bumps [revisionCount].
 *
 * The extension-boundary heuristic is provisional and will be calibrated from
 * measured server message semantics; per the remediation plan only
 * prefix/overlap-length diagnostics are logged, never transcript content.
 */
class TranscriptAccumulator {
    var current: String? = null
        private set

    var revisionCount: Int = 0
        private set

    /** Accepts one streamed [message]; returns the new (or unchanged) current. */
    fun accept(message: String): String? {
        if (message.trim().isEmpty()) return current
        val existing = current
        if (existing == null) {
            current = message
            revisionCount += 1
            return current
        }
        if (message == existing) return current
        if (isCumulativeExtension(existing, message)) {
            current = message
            revisionCount += 1
            return current
        }
        if (existing.startsWith(message)) return current
        current = message
        revisionCount += 1
        return current
    }

    /** Returns the current settled transcript, or null if none exists yet. */
    fun settledText(): String? = current

    /** Clears [current] and [revisionCount] for a fresh session. */
    fun reset() {
        current = null
        revisionCount = 0
    }

    /**
     * True when [message] cumulatively extends [current] on a word boundary:
     * [message] starts with [current] and either the character directly after
     * [current] is whitespace or [current] itself already ends on a trailing
     * whitespace boundary. Fragments are never trimmed before this check
     * because internal spaces can carry word boundaries.
     */
    private fun isCumulativeExtension(current: String, message: String): Boolean {
        if (!message.startsWith(current)) return false
        if (message.length == current.length) return false
        return message[current.length].isWhitespace() || current.last().isWhitespace()
    }
}
