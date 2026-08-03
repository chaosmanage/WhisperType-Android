package com.whispertype.android.validation

import com.whispertype.android.gemini.LanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishValidatorTest {

    @Test
    fun devanagariRejectedInEnglishMode() {
        val result = HinglishValidator.validate("hello \u0915\u0948\u0938\u0947 ho", LanguageMode.ENGLISH)
        assertTrue(result is ScriptValidationResult.Rejected)
        val rejected = result as ScriptValidationResult.Rejected
        assertEquals(NonLatinScript.DEVANAGARI, rejected.script)
        assertEquals('\u0915', rejected.offendingChar)
    }

    @Test
    fun devanagariRejectedInHinglishMode() {
        val result = HinglishValidator.validate("hi \u0915\u0948\u0938\u0947", LanguageMode.HINGLISH)
        assertTrue(result is ScriptValidationResult.Rejected)
        assertEquals(NonLatinScript.DEVANAGARI, (result as ScriptValidationResult.Rejected).script)
    }

    @Test
    fun cyrillicRejectedInBothModes() {
        for (mode in LanguageMode.entries) {
            val result = HinglishValidator.validate("hello \u041F\u0440\u0438\u0432\u0435\u0442", mode)
            assertTrue(result is ScriptValidationResult.Rejected)
            assertEquals(NonLatinScript.CYRILLIC, (result as ScriptValidationResult.Rejected).script)
        }
    }

    @Test
    fun arabicRejectedInBothModes() {
        for (mode in LanguageMode.entries) {
            val result = HinglishValidator.validate("hello \u0645\u0631\u062D\u0628\u0627", mode)
            assertTrue(result is ScriptValidationResult.Rejected)
            assertEquals(NonLatinScript.ARABIC, (result as ScriptValidationResult.Rejected).script)
        }
    }

    @Test
    fun cjkRejectedInBothModes() {
        for (mode in LanguageMode.entries) {
            val result = HinglishValidator.validate("hello \u4F60\u597D", mode)
            assertTrue(result is ScriptValidationResult.Rejected)
            assertEquals(NonLatinScript.CJK, (result as ScriptValidationResult.Rejected).script)
        }
    }

    @Test
    fun hinglishLatinTextPassesInBothModes() {
        val text = "okay bhai kal raat ki meeting ekdum bekaar thi"
        assertEquals(ScriptValidationResult.Valid, HinglishValidator.validate(text, LanguageMode.HINGLISH))
        assertEquals(ScriptValidationResult.Valid, HinglishValidator.validate(text, LanguageMode.ENGLISH))
    }

    @Test
    fun englishDictationPasses() {
        assertEquals(
            ScriptValidationResult.Valid,
            HinglishValidator.validate("the sprint review starts at ten a.m.", LanguageMode.ENGLISH),
        )
    }

    @Test
    fun punctuationDigitsAndSymbolsPass() {
        assertEquals(
            ScriptValidationResult.Valid,
            HinglishValidator.validate("call me at (555) 123-4567 or k@example.com - pay $49.99 !", LanguageMode.HINGLISH),
        )
    }

    @Test
    fun urlsAndEmojisPass() {
        assertEquals(
            ScriptValidationResult.Valid,
            HinglishValidator.validate("check https://example.com/a_b?q=1 \uD83D\uDE00", LanguageMode.HINGLISH),
        )
    }

    @Test
    fun accentedLatinPasses() {
        assertEquals(
            ScriptValidationResult.Valid,
            HinglishValidator.validate("caf\u00E9 r\u00E9sum\u00E9 na\u00EFve", LanguageMode.ENGLISH),
        )
    }

    @Test
    fun combiningMarksPass() {
        assertEquals(
            ScriptValidationResult.Valid,
            HinglishValidator.validate("na\u0308ive", LanguageMode.ENGLISH),
        )
    }

    @Test
    fun bengaliRejectedAsOtherNonLatin() {
        val result = HinglishValidator.validate("hello \u09A8\u09AE\u09B8\u09CD\u0995\u09BE\u09B0", LanguageMode.HINGLISH)
        assertTrue(result is ScriptValidationResult.Rejected)
        assertEquals(NonLatinScript.OTHER_NON_LATIN, (result as ScriptValidationResult.Rejected).script)
    }

    @Test
    fun emptyAndWhitespaceTextPass() {
        assertEquals(ScriptValidationResult.Valid, HinglishValidator.validate("", LanguageMode.HINGLISH))
        assertEquals(ScriptValidationResult.Valid, HinglishValidator.validate(" \t\n", LanguageMode.HINGLISH))
    }

    @Test
    fun firstOffendingCharacterReported() {
        val result = HinglishValidator.validate("ok \u0411 \u0412", LanguageMode.ENGLISH)
        assertTrue(result is ScriptValidationResult.Rejected)
        assertEquals('\u0411', (result as ScriptValidationResult.Rejected).offendingChar)
    }
}
