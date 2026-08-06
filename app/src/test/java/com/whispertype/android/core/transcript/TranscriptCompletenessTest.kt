package com.whispertype.android.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the 0.4.2 completeness gate that decides whether a polished
 * echo may be inserted over the raw ASR baseline.
 */
class TranscriptCompletenessTest {

    @Test
    fun `verbatim echo fully covering the raw is complete`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "The quick brown fox jumps over the lazy dog",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `echo that only drops fillers is complete`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "We should meet on Thursday",
                raw = "um we should like meet on thursday",
            ),
        )
    }

    @Test
    fun `first-few-words echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "The quick brown",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `one word summary echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "Meeting",
                raw = "The team meeting is scheduled for nine in the morning",
            ),
        )
    }

    @Test
    fun `last word only echo is incomplete`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "dog",
                raw = "The quick brown fox jumps over the lazy dog",
            ),
        )
    }

    @Test
    fun `echo at the completeness boundary is accepted`() {
        // 4 of 6 words: ratio 0.667 >= 0.6 (polish may drop ~a third of fillers).
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "one two three four",
                raw = "one two three four five six",
            ),
        )
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "one two three",
                raw = "one two three four five six",
            ),
        )
    }

    @Test
    fun `echo longer than the raw is complete (can never be missing content)`() {
        assertTrue(
            TranscriptCompleteness.covers(
                echo = "We should meet on Thursday then",
                raw = "We should meet on Thursday",
            ),
        )
    }

    @Test
    fun `blank raw cannot be verified`() {
        assertFalse(TranscriptCompleteness.covers(echo = "anything", raw = ""))
        assertFalse(TranscriptCompleteness.covers(echo = "anything", raw = "   "))
    }

    @Test
    fun `blank echo never covers a real raw`() {
        assertFalse(
            TranscriptCompleteness.covers(
                echo = "",
                raw = "The quick brown fox",
            ),
        )
    }

    @Test
    fun `custom min ratio adjusts the acceptance band`() {
        assertFalse(TranscriptCompleteness.covers(echo = "a b c", raw = "a b c d e f", minRatio = 0.6))
        assertTrue(TranscriptCompleteness.covers(echo = "a b c d", raw = "a b c d e f", minRatio = 0.6))
    }

    @Test
    fun `expected words scales with duration and speaking rate`() {
        assertEquals(220.0, TranscriptCompleteness.expectedWords(100_000), 0.001)
        assertEquals(2.2, TranscriptCompleteness.expectedWords(1_000), 0.001)
    }

    @Test
    fun `content words tokenizes letter and digit runs only`() {
        assertEquals(
            listOf("the", "quick", "brown", "fox123"),
            TranscriptCompleteness.contentWords("The quick, brown 'fox123'!"),
        )
    }
}
