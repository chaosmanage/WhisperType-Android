package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle

/**
 * 0.8.0: rejects over-edited polish output.
 *
 * The text stage runs on a remote model, so "do not restructure" can only ever
 * be a request. This guard is the enforcement: it compares the model's output
 * against the raw ASR and, when the edit magnitude exceeds what the level
 * allows, the caller inserts the unpolished text instead. That makes a
 * wholesale rewrite at [TranscriptionStyle.LOW]/[TranscriptionStyle.MEDIUM]
 * impossible rather than merely discouraged.
 *
 * It is a validator, not a text processor: it never rewrites anything, it only
 * accepts or rejects.
 *
 * English compares content words, ignoring filler words (which the level is
 * *supposed* to delete) so their removal is never counted as an edit. Hinglish
 * is cross-script (Devanagari in, Latin out), which makes word comparison
 * meaningless, so it is validated on script and length instead.
 */
object PolishGuard {

    sealed interface Verdict {
        /** The polished text is within the level's allowance. */
        data object Accept : Verdict

        /** [code] is a short, non-sensitive reason suitable for metrics. */
        data class Reject(val code: String) : Verdict
    }

    /** Unambiguous non-words a polish level is allowed to delete. */
    private val FILLERS = setOf(
        "um", "umm", "ummm", "uh", "uhh", "uhhh", "ah", "aah", "ahh",
        "er", "err", "erm", "hmm", "hmmm", "mm", "mmm", "huh",
    )

    fun evaluate(
        raw: String,
        polished: String,
        language: LanguageMode,
        style: TranscriptionStyle,
    ): Verdict {
        if (polished.isBlank()) return Verdict.Reject("blank")
        if (language == LanguageMode.HINGLISH) return evaluateHinglish(raw, polished, style)
        return evaluateEnglish(raw, polished, style)
    }

    private fun evaluateEnglish(
        raw: String,
        polished: String,
        style: TranscriptionStyle,
    ): Verdict {
        val rawWords = contentWords(raw).filterNot { it in FILLERS }
        val polishedWords = contentWords(polished)
        if (rawWords.isEmpty()) return Verdict.Accept
        val retained = rawWords.count { it in polishedWords.toSet() }.toDouble() / rawWords.size
        val lengthRatio = polishedWords.size.toDouble() / rawWords.size
        val limits = limitsFor(style)
        if (retained < limits.minRetention) return Verdict.Reject("retention")
        if (lengthRatio < limits.minLengthRatio) return Verdict.Reject("too_short")
        if (lengthRatio > limits.maxLengthRatio) return Verdict.Reject("too_long")
        return Verdict.Accept
    }

    private fun evaluateHinglish(
        raw: String,
        polished: String,
        style: TranscriptionStyle,
    ): Verdict {
        val letters = polished.count { it.isLetter() }
        if (letters == 0) return Verdict.Reject("blank")
        val devanagari = polished.count { it in DEVANAGARI_RANGE }
        // The whole point of the Hinglish pass is Latin output; a mostly
        // Devanagari reply means the romanization did not happen.
        if (devanagari.toDouble() / letters > MAX_DEVANAGARI_RATIO) {
            return Verdict.Reject("not_romanized")
        }
        val rawWords = contentWords(raw).filterNot { it in FILLERS }
        val polishedWords = contentWords(polished)
        if (rawWords.isEmpty()) return Verdict.Accept
        val lengthRatio = polishedWords.size.toDouble() / rawWords.size
        val limits = hinglishLimitsFor(style)
        if (lengthRatio < limits.first) return Verdict.Reject("too_short")
        if (lengthRatio > limits.second) return Verdict.Reject("too_long")
        return Verdict.Accept
    }

    private data class Limits(
        val minRetention: Double,
        val minLengthRatio: Double,
        val maxLengthRatio: Double,
    )

    private fun limitsFor(style: TranscriptionStyle): Limits = when (style) {
        // NONE never dials, but keep it strict if it somehow does.
        TranscriptionStyle.NONE -> Limits(minRetention = 0.95, minLengthRatio = 0.9, maxLengthRatio = 1.1)
        TranscriptionStyle.LOW -> Limits(minRetention = 0.85, minLengthRatio = 0.8, maxLengthRatio = 1.15)
        TranscriptionStyle.MEDIUM -> Limits(minRetention = 0.70, minLengthRatio = 0.6, maxLengthRatio = 1.4)
        TranscriptionStyle.HIGH -> Limits(minRetention = 0.40, minLengthRatio = 0.25, maxLengthRatio = 2.0)
    }

    /** Cross-script: length bounds only (word identity cannot be compared). */
    private fun hinglishLimitsFor(style: TranscriptionStyle): Pair<Double, Double> = when (style) {
        TranscriptionStyle.NONE -> 0.8 to 1.25
        TranscriptionStyle.LOW -> 0.7 to 1.3
        TranscriptionStyle.MEDIUM -> 0.5 to 1.5
        TranscriptionStyle.HIGH -> 0.25 to 2.0
    }

    private fun contentWords(text: String): List<String> =
        TranscriptCompleteness.contentWords(text)

    /** A stray un-romanized word is tolerated; a mostly-Devanagari reply is not. */
    private const val MAX_DEVANAGARI_RATIO = 0.25
    private val DEVANAGARI_RANGE = '\u0900'..'\u097F'
}
