package com.whispertype.android.validation

import com.whispertype.android.gemini.LanguageMode

/** Non-Latin script blocks detected by [HinglishValidator]. */
enum class NonLatinScript(val label: String) {
    DEVANAGARI("Devanagari"),
    CYRILLIC("Cyrillic"),
    ARABIC("Arabic"),
    CJK("CJK"),
    OTHER_NON_LATIN("other non-Latin script"),
}

/** Result of a Latin-script enforcement check. */
sealed interface ScriptValidationResult {
    data object Valid : ScriptValidationResult
    data class Rejected(val script: NonLatinScript, val offendingChar: Char) : ScriptValidationResult
}

/**
 * Latin-script-only enforcement for all dictation output (Implementation Plan §14).
 *
 * All non-Latin scripts (Devanagari, Cyrillic, Arabic, CJK and everything else outside
 * the allowed sets) are rejected in every mode: HINGLISH output must be romanized Hindi
 * in Latin script, and any non-Latin script in ENGLISH output is equally corrupt.
 * Allowed content: Latin letters (including accented and extended Latin), ASCII digits,
 * whitespace, all punctuation and symbol categories (URLs, emails, code identifiers,
 * numbers, currency symbols and emoji pass), and combining marks.
 */
object HinglishValidator {

    /**
     * Returns [ScriptValidationResult.Valid] when [text] is Latin-script only for
     * [languageMode], otherwise the first offending script and character.
     */
    fun validate(text: String, languageMode: LanguageMode): ScriptValidationResult {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val script = classify(codePoint)
            if (script != null) {
                return ScriptValidationResult.Rejected(script, text[index])
            }
            if (!isAllowed(codePoint)) {
                return ScriptValidationResult.Rejected(NonLatinScript.OTHER_NON_LATIN, text[index])
            }
            index += Character.charCount(codePoint)
        }
        return ScriptValidationResult.Valid
    }

    private fun classify(codePoint: Int): NonLatinScript? = when (codePoint) {
        in 0x0900..0x097F -> NonLatinScript.DEVANAGARI
        in 0x0400..0x052F -> NonLatinScript.CYRILLIC
        in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF -> NonLatinScript.ARABIC
        in 0x3040..0x30FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF -> NonLatinScript.CJK
        else -> null
    }

    private fun isAllowed(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint)) return true
        if (codePoint in '0'.code..'9'.code) return true
        if (Character.isLetter(codePoint)) return isLatinLetter(codePoint)
        return CHARACTER_CATEGORY_ALLOWED.contains(Character.getType(codePoint))
    }

    private fun isLatinLetter(codePoint: Int): Boolean = when (codePoint) {
        in 0x0041..0x007A -> true
        in 0x00C0..0x02AF -> true
        in 0x1E00..0x1EFF -> true
        else -> false
    }

    private val CHARACTER_CATEGORY_ALLOWED: Set<Int> = setOf(
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
    )
}
