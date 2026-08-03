package com.whispertype.android.validation

import com.whispertype.android.gemini.LanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSelectorTest {

    @Test
    fun cleanedPreferredWhenBothValid() {
        val selection = CandidateSelector.select("Hello world", "hello world", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        val selected = selection as CandidateSelection.Selected
        assertEquals("Hello world", selected.text)
        assertEquals(CandidateSource.CLEANED, selected.source)
    }

    @Test
    fun rawFallsBackWhenCleanedInvalid() {
        val selection = CandidateSelector.select("   ", "hello world", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        val selected = selection as CandidateSelection.Selected
        assertEquals("hello world", selected.text)
        assertEquals(CandidateSource.RAW, selected.source)
    }

    @Test
    fun cleanedInvalidDueToLengthExpansionFallsBackToRaw() {
        val selection = CandidateSelector.select("a".repeat(100000), "short raw", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("short raw", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun cleanedInvalidDueToHinglishFallsBackToRaw() {
        val cleaned = "hi \u0915\u0948\u0938\u0947 ho"
        val selection = CandidateSelector.select(cleaned, "hi kaise ho", LanguageMode.HINGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("hi kaise ho", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun devanagariCleanedFallsBackToRawInEnglishModeToo() {
        val selection = CandidateSelector.select("hi \u0915\u0948\u0938\u0947", "hi kaise", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("hi kaise", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun bothInvalidRejected() {
        val selection = CandidateSelector.select("...", "...", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Rejected)
        val rejected = selection as CandidateSelection.Rejected
        assertEquals(CandidateSelector.CODE_NO_VALID_TRANSCRIPT, rejected.failure.code)
        assertTrue(rejected.failure.recoverable)
        assertTrue(rejected.failure.message.isNotBlank())
    }

    @Test
    fun nullCandidatesRejected() {
        val selection = CandidateSelector.select(null, null, LanguageMode.HINGLISH)
        assertTrue(selection is CandidateSelection.Rejected)
        assertEquals(CandidateSelector.CODE_NO_VALID_TRANSCRIPT, (selection as CandidateSelection.Rejected).failure.code)
    }

    @Test
    fun nullCleanedFallsBackToValidRaw() {
        val selection = CandidateSelector.select(null, "hello world", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("hello world", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun nullRawUsesValidCleaned() {
        val selection = CandidateSelector.select("Hello world", null, LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("Hello world", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun cleanedThatIsPureDuplicationOfRawIsInvalid() {
        val selection = CandidateSelector.select("hellohello", "hello", LanguageMode.ENGLISH)
        val selected = selection as CandidateSelection.Selected
        assertEquals("hello", selected.text)
        assertEquals(CandidateSource.RAW, selected.source)
    }

    @Test
    fun rawThatIsPureDuplicationOfCleanedDoesNotInvalidateCleaned() {
        val selection = CandidateSelector.select("hello", "hellohello", LanguageMode.ENGLISH)
        val selected = selection as CandidateSelection.Selected
        assertEquals("hello", selected.text)
        assertEquals(CandidateSource.CLEANED, selected.source)
    }

    @Test
    fun duplicatedRawFallsBackToCleanerCleaned() {
        val selection = CandidateSelector.select("abc", "abcabc", LanguageMode.ENGLISH)
        val selected = selection as CandidateSelection.Selected
        assertEquals("abc", selected.text)
        assertEquals(CandidateSource.CLEANED, selected.source)
    }

    @Test
    fun markerTextCleanedRejectedFallsBackToRaw() {
        val selection = CandidateSelector.select("*transcript* of the call", "transcript of the call", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals("transcript of the call", (selection as CandidateSelection.Selected).text)
    }

    @Test
    fun cleanedLongerThanRawByMoreThanThreeTimesFallsBackToRaw() {
        val raw = "the quick brown fox jumps over the lazy dog"
        val cleaned = "the quick brown fox jumps over the lazy dog " +
            "and then some extra words that make this a whole lot longer than it should be " +
            "and yet more padding to blow past the three times ratio limit"
        val selection = CandidateSelector.select(cleaned, raw, LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals(CandidateSource.RAW, (selection as CandidateSelection.Selected).source)
    }

    @Test
    fun cleanedSlightlyLongerThanRawStillPasses() {
        val raw = "the quick brown fox jumps"
        val cleaned = "the quick brown fox jumps over the lazy dog"
        val selection = CandidateSelector.select(cleaned, raw, LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertEquals(CandidateSource.CLEANED, (selection as CandidateSelection.Selected).source)
    }

    @Test
    fun selectionIsNeverBoth() {
        val selection = CandidateSelector.select("A", "B", LanguageMode.ENGLISH)
        assertTrue(selection is CandidateSelection.Selected)
        assertFalse(selection is CandidateSelection.Rejected)
        assertTrue((selection as CandidateSelection.Selected).text.isNotEmpty())
    }
}
