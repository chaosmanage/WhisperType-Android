package com.whispertype.android.core.transcript

/**
 * Session-local accumulator for a streamed transcript (remediation plan
 * Release E1/E2, hardened in 0.4.2 for reliability).
 *
 * The Gemini Live server may deliver independent deltas, cumulative revisions,
 * corrected revisions, or a mixture, so this tracks ONE current cumulative
 * revision with content-preserving merge rules (longer content always wins):
 *
 *  1. Empty/blank message: ignored (no change, no revision bump).
 *  2. Exact duplicate of [current]: ignored.
 *  3. No current value yet: accepted as-is.
 *  4. Cumulative extension (message starts with [current] on a word boundary):
 *     merged, so the longer cumulative value wins.
 *  5. Reverse prefix (message is a shorter/partial revision of [current]):
 *     ignored; the longer cumulative value wins.
 *  6. [appendDeltas] only — a delta-style message whose leading content word
 *     does not overlap the current tail (observed for `outputTranscription`,
 *     where the server streams the model's spoken reply as word deltas):
 *     appended on a word boundary, so the full reply is reconstructed instead
 *     of each delta replacing the last one.
 *  7. A strictly shorter revision (fewer content words, whatever the wording):
 *     ignored — a mid-stream condense can never permanently shrink the text
 *     that later gets settled.
 *  8. Otherwise (same-or-longer correction/replacement): replaced.
 *
 * Every accepted change bumps [revisionCount].
 */
class TranscriptAccumulator(
    /** When true, accepts delta-style streamed messages (see rule 6). Use for
     *  the echo source; keep false for the ASR source where revisions replace. */
    private val appendDeltas: Boolean = false,
) {
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
        if (appendDeltas && isAppendableDelta(existing, message)) {
            current = join(existing, message)
            revisionCount += 1
            return current
        }
        if (contentWords(message).size < contentWords(existing).size) return current
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
     * True when [message] is a streamed delta: its leading content word does
     * not overlap the tail of [existing], i.e. it continues the speech rather
     * than revising it. [OVERLAP_TAIL] recent content words are consulted so a
     * repeated connector word ("for the", "and") still reads as new content.
     */
    private fun isAppendableDelta(existing: String, message: String): Boolean {
        val head = contentWords(message).firstOrNull() ?: return false
        val tail = contentWords(existing).takeLast(OVERLAP_TAIL)
        return head !in tail
    }

    /** Joins a delta onto [existing] on a word boundary. */
    private fun join(existing: String, message: String): String {
        val delta = message.trim()
        return if (delta.isEmpty()) existing
        else if (existing.endsWith(" ")) existing + delta
        else "$existing $delta"
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

    /** Lower-cased sequence of letter/digit runs in [text]. */
    private fun contentWords(text: String): List<String> = TranscriptCompleteness.contentWords(text)

    private companion object {
        /** Recent content words of the current value consulted for delta overlap. */
        const val OVERLAP_TAIL: Int = 4
    }
}
