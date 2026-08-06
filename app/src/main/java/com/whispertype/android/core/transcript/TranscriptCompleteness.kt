package com.whispertype.android.core.transcript

/**
 * Pure completeness verification between the two live transcription sources
 * (0.4.2 reliability). The raw input transcription (server ASR of the whole
 * turn) is the completeness baseline: the polished echo may only be inserted
 * when it plausibly covers that baseline, so a truncated or condensed echo can
 * never silently lose the user's words.
 *
 * [covers] answers "does [echo] contain everything the user said in [raw]?" —
 * stateless and host-testable, calibrated against measured server behavior
 * (0.4.2 probe): a complete echo has a content-word ratio near 1.0 (polish may
 * drop ~10-20% fillers), while the failure modes the user hit — "first few
 * words only", "1-2 word summary", "last word only" — all produce an echo with
 * a tiny ratio (< 0.6). A blank raw (ASR never delivered, common on long
 * turns) cannot be verified and returns false, deferring to the caller's
 * duration-sanity check and the audio-recovery failsafe.
 *
 * Word counts use the same letter/digit-run tokenization as the selector so a
 * Devanagari echo in Hinglish mode still counts correctly.
 */
object TranscriptCompleteness {

    /**
     * True when [echo] plausibly contains the full content of [raw]: the echo's
     * content-word count is at least [minRatio] of the raw's. No upper bound:
     * an echo longer than the raw can never be missing content, and fabricated
     * additions are handled by the transcript selector, not this gate.
     */
    fun covers(
        echo: String,
        raw: String,
        minRatio: Double = DEFAULT_MIN_RATIO,
    ): Boolean {
        if (raw.isBlank()) return false
        val echoWords = contentWords(echo).size
        val rawWords = contentWords(raw).size
        if (rawWords == 0) return false
        return echoWords.toDouble() / rawWords >= minRatio
    }

    /**
     * A pure length expectation for the recorded audio: how many words the
     * user plausibly spoke in [durationMs] at [wordsPerSecond]. Used to judge
     * whether a settled transcript is clearly incomplete (0.4.2).
     */
    fun expectedWords(
        durationMs: Long,
        wordsPerSecond: Double = DEFAULT_WORDS_PER_SECOND,
    ): Double = durationMs / 1000.0 * wordsPerSecond

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
                current.append(Character.toLowerCase(ch))
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    /** Default minimum echo/raw content-word ratio for a complete echo. */
    const val DEFAULT_MIN_RATIO: Double = 0.6

    /** Default speaking rate used to derive expected words from duration. */
    const val DEFAULT_WORDS_PER_SECOND: Double = 2.2
}
