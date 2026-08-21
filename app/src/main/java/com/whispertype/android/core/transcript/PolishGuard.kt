package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.TranscriptionStyle

/**
 * 0.8.0: rejects a text-stage reply that edited more than the level allows, or
 * that answered the dictation instead of transcribing it.
 *
 * The stage runs on a remote model, so "do not restructure" and "do not answer"
 * can only ever be requests. This guard is the enforcement: it compares the
 * reply against the raw ASR and, when the reply is out of budget, the caller
 * inserts the unpolished text instead (or, for Hinglish, surfaces a retry).
 *
 * It is a validator, not a text processor: it never rewrites anything.
 *
 * Two independent signals catch an answer:
 *  - **retention** — how much of the speaker's own vocabulary survived;
 *  - **invention** — how much of the reply is words the speaker never said.
 *
 * An answer that quotes the question ("What is the capital of France" ->
 * "The capital of France is Paris") passes retention but fails invention, which
 * is exactly why both exist.
 *
 * Script handling: the checks above need a shared script. They are applied
 * whenever the **raw** transcript has no Devanagari — including Hinglish mode,
 * where the speaker often dictates whole sentences in English (that case shipped
 * broken in the first 0.8.0 build: the model answered an English question in
 * romanized Hindi and the script-only check accepted it). When the raw genuinely
 * contains Devanagari, word identity cannot be compared across scripts, so the
 * guard falls back to script, length, and survival of Latin loanwords/digits.
 */
object PolishGuard {

    sealed interface Verdict {
        /** The reply is within the level's allowance. */
        data object Accept : Verdict

        /** [code] is a short, non-sensitive reason suitable for metrics. */
        data class Reject(val code: String) : Verdict
    }

    /** Unambiguous non-words a level is allowed to delete. */
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
        val limits = limitsFor(style)

        // Hinglish must always come back romanized, whatever else is true.
        if (language == LanguageMode.HINGLISH) {
            val letters = polished.count { it.isLetter() }
            if (letters == 0) return Verdict.Reject("blank")
            val devanagari = polished.count { it in DEVANAGARI_RANGE }
            if (devanagari.toDouble() / letters > MAX_DEVANAGARI_RATIO) {
                return Verdict.Reject("not_romanized")
            }
        }

        // Shared-script word identity needs a script-aware tokenizer: the
        // transcript util treats Devanagari combining marks (matras, nuktas) as
        // delimiters, which splits "मैं" into fragments and breaks every length
        // and retention ratio in Hinglish mode.
        val crossScript = isCrossScript(raw, language)
        // Fillers are filtered from BOTH sides: a faithful reply may keep the
        // speaker's own "um", and counting it only against the polished side
        // would fail the length/invention budgets at strict styles.
        val rawWords = scriptWords(raw).filterNot { it in FILLERS }
        val polishedWords = scriptWords(polished).filterNot { it in FILLERS }
        if (rawWords.isEmpty()) return Verdict.Accept

        val lengthRatio = polishedWords.size.toDouble() / rawWords.size
        if (lengthRatio < limits.minLengthRatio) return Verdict.Reject("too_short")
        if (lengthRatio > limits.maxLengthRatio) return Verdict.Reject("too_long")

        // Word-identity checks require a shared script.
        if (!crossScript) {
            val polishedSet = polishedWords.toSet()
            val rawSet = rawWords.toSet()
            val retained = rawWords.count { it in polishedSet }.toDouble() / rawWords.size
            if (retained < limits.minRetention) return Verdict.Reject("retention")
            val invented = polishedWords.count { it !in rawSet }.toDouble() / polishedWords.size
            if (invented > limits.maxInventedRatio) return Verdict.Reject("invented")
            return Verdict.Accept
        }

        // Cross-script: the English loanwords and numbers the speaker used are
        // Latin on both sides, so their survival is still checkable.
        val latinRaw = rawWords.filter { word -> word.none { it in DEVANAGARI_RANGE } }
        if (latinRaw.isNotEmpty()) {
            val polishedSet = polishedWords.toSet()
            val kept = latinRaw.count { it in polishedSet }.toDouble() / latinRaw.size
            if (kept < MIN_LOANWORD_RETENTION) return Verdict.Reject("loanwords_lost")
        }
        return Verdict.Accept
    }

    /** True when word-identity comparison is meaningless (Devanagari in, Latin out). */
    private fun isCrossScript(raw: String, language: LanguageMode): Boolean =
        language == LanguageMode.HINGLISH && raw.any { it in DEVANAGARI_RANGE }

    /**
     * Script-aware word tokenizer. Unlike [TranscriptCompleteness.contentWords],
     * this keeps Devanagari combining marks (matras, nuktas) attached to their
     * consonant so "मैं" counts as one word, not three fragments. Needed for any
     * length/retention math that touches Devanagari.
     */
    private fun scriptWords(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result.add(current.toString().lowercase())
                current.setLength(0)
            }
        }
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch.isCombiningMark()) {
                current.append(ch)
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    private fun Char.isCombiningMark(): Boolean = when (category) {
        CharCategory.NON_SPACING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        -> true
        else -> false
    }

    private data class Limits(
        val minRetention: Double,
        val minLengthRatio: Double,
        val maxLengthRatio: Double,
        /** Share of the reply that may be words the speaker never said. */
        val maxInventedRatio: Double,
    )

    private fun limitsFor(style: TranscriptionStyle): Limits = when (style) {
        // NONE only dials for Hinglish romanization; keep it strict regardless.
        TranscriptionStyle.NONE -> Limits(0.95, 0.9, 1.1, 0.10)
        TranscriptionStyle.LOW -> Limits(0.85, 0.8, 1.15, 0.15)
        TranscriptionStyle.MEDIUM -> Limits(0.70, 0.6, 1.4, 0.35)
        TranscriptionStyle.HIGH -> Limits(0.40, 0.25, 2.0, 0.60)
    }

    /** A stray un-romanized word is tolerated; a mostly-Devanagari reply is not. */
    private const val MAX_DEVANAGARI_RATIO = 0.25

    /** Half of the spoken loanwords/numbers must survive a romanization pass. */
    private const val MIN_LOANWORD_RETENTION = 0.5

    private val DEVANAGARI_RANGE = '\u0900'..'\u097F'
}
