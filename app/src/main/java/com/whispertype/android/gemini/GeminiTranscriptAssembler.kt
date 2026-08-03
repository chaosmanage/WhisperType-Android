package com.whispertype.android.gemini

/**
 * Full assembled raw/cleaned candidates for a session.
 * Values are null when no delta of that stream has been received.
 */
data class TranscriptCandidates(
    val raw: String?,
    val cleaned: String?,
)

/**
 * Accumulates raw and cleaned transcript deltas for one session, turn by turn.
 *
 * Deltas are concatenated in arrival order within the current turn; [completeTurn]
 * finalizes the current turn, returning its candidates, and starts a new turn with an
 * empty buffer. Empty or null deltas are ignored. [snapshot] returns null for streams
 * that never produced a delta, so callers can distinguish "no transcript" from an
 * empty transcript. [turnCount] is the number of turns completed so far, plus the
 * current turn once it has accumulated any delta.
 */
class GeminiTranscriptAssembler {

    private val rawBuilder = StringBuilder()
    private val cleanedBuilder = StringBuilder()
    private var rawStarted = false
    private var cleanedStarted = false
    private var turnCountValue = 0
    private var turnInProgress = false

    /** Number of completed turns, plus one while the current turn holds deltas. */
    val turnCount: Int
        get() = turnCountValue + if (turnInProgress) 1 else 0

    /** Appends non-empty deltas to the corresponding candidates. */
    fun append(rawDelta: String?, cleanedDelta: String?) {
        if (!rawDelta.isNullOrEmpty()) {
            rawStarted = true
            turnInProgress = true
            rawBuilder.append(rawDelta)
        }
        if (!cleanedDelta.isNullOrEmpty()) {
            cleanedStarted = true
            turnInProgress = true
            cleanedBuilder.append(cleanedDelta)
        }
    }

    /** Returns the candidates accumulated so far in the current turn. */
    fun snapshot(): TranscriptCandidates = TranscriptCandidates(
        raw = if (rawStarted) rawBuilder.toString() else null,
        cleaned = if (cleanedStarted) cleanedBuilder.toString() else null,
    )

    /**
     * Finalizes the current turn, returning its candidates, and starts a new turn
     * with an empty buffer so its first delta does not include earlier turns' text.
     */
    fun completeTurn(): TranscriptCandidates {
        val completed = snapshot()
        turnCountValue++
        turnInProgress = false
        rawBuilder.setLength(0)
        cleanedBuilder.setLength(0)
        rawStarted = false
        cleanedStarted = false
        return completed
    }
}
