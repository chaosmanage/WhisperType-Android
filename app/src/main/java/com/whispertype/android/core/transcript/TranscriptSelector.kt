package com.whispertype.android.core.transcript

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.ResultCandidate

/**
 * Pure, stateless transcript candidate selector/validator implementing PRD FR-7.
 *
 * Selection order (FR-7):
 *  1. The first candidate whose *cleaned* text passes validation.
 *  2. If none, the first candidate whose *raw* text passes validation.
 *  3. Otherwise [TranscriptSelection.None] so the caller can produce a Copy
 *     fallback or an actionable failure. No invalid text is ever returned.
 *
 * Determinism & ordering: a single left-to-right pass finds the first usable
 * cleaned candidate; only if no candidate has usable cleaned text do we search
 * for the first usable raw candidate. `cleaned` therefore wins over `raw` and,
 * within a tier, the earliest candidate wins.
 *
 * Immutability & purity: this class never mutates the input [ResultCandidate]s.
 * It only reads their `raw`/`cleaned`/`language` and returns a fresh sealed
 * value. It performs no I/O and has no state.
 *
 * Validation heuristics (all thresholds are named private constants below):
 *  - blank / whitespace-only / punctuation-only (nothing letter-or-digit)
 *  - model preamble / boilerplate (case-insensitive documented prefixes)
 *  - implausible expansion (cleaned word count >> raw word count)
 *  - pathological within-text repetition (e.g. "la la la la la")
 *  - Devanagari script inside a Latin-script language mode (Hinglish/English)
 *  - garbled / corrupted text (high ratio of replacement or control chars)
 *
 * False-positive protection: URLs, emails, identifiers, numbers, normal
 * sentence punctuation, short phrases and natural Latin-script code-switching
 * are preserved because validation only rejects the specific patterns above.
 */
class TranscriptSelector {

    /** Returns the deterministic selection for [candidates] (first valid wins). */
    fun select(candidates: List<ResultCandidate>): TranscriptSelection {
        // Pass 1: first candidate whose cleaned text is usable.
        for (c in candidates) {
            val cleaned = c.cleaned
            if (cleaned != null && isTextValid(cleaned, c.language) && !isImplausiblyExpanded(c)) {
                return TranscriptSelection.Cleaned(c, cleaned)
            }
        }
        // Pass 2: first candidate whose raw text is usable (cleaned was absent or invalid).
        for (c in candidates) {
            if (isTextValid(c.raw, c.language)) {
                return TranscriptSelection.Raw(c, c.raw)
            }
        }
        return TranscriptSelection.None
    }

    /**
     * True when [text] is legitimate, non-corrupt, meaningful content for the
     * given [language]. Every rejection below maps to an FR-7 invalidity class.
     */
    private fun isTextValid(text: String, language: LanguageMode): Boolean {
        if (text.isBlank()) return false                                  // blank / whitespace
        if (!text.any { it.isLetterOrDigit() }) return false              // punctuation-only
        if (isModelPreamble(text)) return false                           // model boilerplate
        if (isLatinScriptMode(language) && containsDevanagari(text)) return false
        if (isGarbled(text)) return false                                 // corrupted / replacement chars
        if (isPathologicallyRepetitive(words(text))) return false         // duplicated / repetitive
        return true
    }


    /**
     * True when the candidate's cleaned text is implausibly longer than its raw
     * text (word-count ratio beyond [MAX_EXPANSION_RATIO]), a strong signal of
     * model hallucination. Only meaningful when the raw text has real words;
     * otherwise there is nothing to expand from, so the check is skipped.
     */
    private fun isImplausiblyExpanded(c: ResultCandidate): Boolean {
        val cleaned = c.cleaned ?: return false
        val rawWords = words(c.raw).size
        if (rawWords == 0) return false
        val cleanedWords = words(cleaned).size
        return cleanedWords > rawWords &&
            cleanedWords > MAX_EXPANSION_RATIO * rawWords
    }

    /**
     * Case-insensitive model-preamble detection.
     *
     * [STRONG_PREAMBLE_PREFIXES] are unambiguous boilerplate starters and are
     * rejected whenever they appear at the start of the text.
     * [GENERIC_LEAD_PREFIXES] (e.g. "sure", "okay") are ordinary spoken words, so
     * they are only treated as preamble when followed by a substantial sentence
     * ([GENERIC_LEAD_MAX_WORDS]) — preserving short legitimate utterances like
     * "Sure!" or "Okay" while catching "Sure, here is your transcript...".
     */
    private fun isModelPreamble(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        for (prefix in STRONG_PREAMBLE_PREFIXES) {
            if (startsWithWordBoundary(t, prefix)) return true
        }
        val wordCount = words(t).size
        for (lead in GENERIC_LEAD_PREFIXES) {
            if (startsWithWordBoundary(t, lead) && wordCount > GENERIC_LEAD_MAX_WORDS) return true
        }
        return false
    }

    /** True when [text] starts with [prefix] and the prefix ends on a word boundary. */
    private fun startsWithWordBoundary(text: String, prefix: String): Boolean =
        text.length >= prefix.length &&
            text.startsWith(prefix) &&
            (text.length == prefix.length || !text[prefix.length].isLetterOrDigit())

    /**
     * True when [text] is dominated by replacement ("\uFFFD") or control
     * characters, i.e. the model returned a corrupted/garbled string.
     */
    private fun isGarbled(text: String): Boolean {
        if (text.isEmpty()) return false
        val problematic = text.count { it == REPLACEMENT_CHAR || it.isISOControl() }
        return problematic > 0 && problematic.toDouble() / text.length > GARBLED_CHAR_RATIO
    }

    /**
     * True when the whole text decomposes into a single short unit repeated at
     * least [MIN_PATTERN_REPEATS] times (e.g. "la la la la la", "abc abc abc",
     * "hi there hi there hi there"). This is pathological duplication, not a
     * normal sentence.
     */
    private fun isPathologicallyRepetitive(tokens: List<String>): Boolean {
        val n = tokens.size
        for (unit in 1..n / 2) {
            if (n % unit != 0) continue
            val repeats = n / unit
            if (repeats < MIN_PATTERN_REPEATS) continue
            var same = true
            for (i in 0 until n) {
                if (tokens[i] != tokens[i % unit]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
    }

    private fun isLatinScriptMode(language: LanguageMode): Boolean =
        language == LanguageMode.ENGLISH || language == LanguageMode.HINGLISH

    private fun containsDevanagari(text: String): Boolean = text.any { isDevanagari(it) }

    private fun isDevanagari(ch: Char): Boolean =
        ch in DEVANAGARI_BLOCK ||
            ch in DEVANAGARI_EXTENDED_A_BLOCK ||
            ch in VEDIC_EXTENSIONS_BLOCK

    /**
     * Lower-cased sequence of letter/digit runs in [text]. Used for word counts
     * and repetition detection.
     */
    private fun words(text: String): List<String> {
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

    // ------------------------------------------------------------------
    // Documented thresholds & preamble lists (FR-7 heuristics).
    // ------------------------------------------------------------------

    private companion object {
        /** Cleaned/raw word-count ratio above which expansion is deemed hallucination. */
        const val MAX_EXPANSION_RATIO: Double = 10.0

        /** Fraction of replacement/control characters that flags a garbled result. */
        const val GARBLED_CHAR_RATIO: Double = 0.2

        /** A text pattern must repeat at least this many times to count as duplication. */
        const val MIN_PATTERN_REPEATS: Int = 3

        /**
         * A single generic spoken lead word (e.g. "sure", "okay") is not preamble
         * unless followed by more than this many words of "sentence".
         */
        const val GENERIC_LEAD_MAX_WORDS: Int = 2

        val REPLACEMENT_CHAR: Char = '\uFFFD'

        val DEVANAGARI_BLOCK: CharRange = '\u0900'..'\u097F'
        val DEVANAGARI_EXTENDED_A_BLOCK: CharRange = '\uA8E0'..'\uA8FF'
        val VEDIC_EXTENSIONS_BLOCK: CharRange = '\u1CD0'..'\u1CFF'

        val STRONG_PREAMBLE_PREFIXES: List<String> = listOf(
            "here is",
            "here's",
            "here are",
            "here you go",
            "the transcript",
            "the transcription",
            "this transcript",
            "below is",
            "as an ai",
            "you asked",
            "i have transcribed",
            "certainly",
            "of course",
            "i'd be happy",
            "i would be happy",
            "i will be happy",
            "sure thing",
            "okay here",
            "ok here",
            "the following",
            "note",
            "disclaimer",
            "my apologies",
        )

        val GENERIC_LEAD_PREFIXES: List<String> = listOf(
            "sure",
            "okay",
            "ok",
            "yes",
            "yep",
            "alright",
            "absolutely",
            "yup",
        )
    }
}

/**
 * Typed, deterministic outcome of [TranscriptSelector.select].
 *
 *  - [Cleaned]: the selected text is the candidate's cleaned transcript.
 *  - [Raw]: the selected text is the candidate's raw transcript (no usable
 *    cleaned transcript existed).
 *  - [None]: no candidate passed validation; the caller must NOT insert or copy
 *    anything, and should instead offer a Copy fallback or an actionable failure.
 *
 * [text] is the exact, already-validated string to insert/copy — never invalid.
 */
sealed interface TranscriptSelection {
    /** The [ResultCandidate] that was selected (never mutated by the selector). */
    val candidate: ResultCandidate

    /** The exact validated text to insert/copy. */
    val text: String

    /** A candidate whose cleaned transcript passed validation. */
    data class Cleaned(override val candidate: ResultCandidate, override val text: String) : TranscriptSelection

    /** A candidate for which only the raw transcript passed validation. */
    data class Raw(override val candidate: ResultCandidate, override val text: String) : TranscriptSelection

    /**
     * No valid candidate was found; do not insert or copy. [candidate] and
     * [text] are intentionally undefined here and throw, so it is impossible to
     * ever obtain text from a [None] selection — the caller must instead branch
     * on it and produce a Copy fallback or an actionable failure.
     */
    data object None : TranscriptSelection {
        override val candidate: ResultCandidate
            get() = throw UnsupportedOperationException("TranscriptSelection.None carries no candidate")

        override val text: String
            get() = throw UnsupportedOperationException("TranscriptSelection.None carries no text")
    }
}
