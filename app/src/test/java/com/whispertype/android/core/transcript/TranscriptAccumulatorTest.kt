package com.whispertype.android.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the session-local [TranscriptAccumulator] merge rules
 * (remediation plan Release E1/E2).
 */
class TranscriptAccumulatorTest {

    @Test
    fun `empty and blank messages are ignored without change or revision bump`() {
        val accumulator = TranscriptAccumulator()
        assertNull(accumulator.accept(""))
        assertNull(accumulator.accept("   "))
        assertNull(accumulator.accept("\t\n "))
        assertNull(accumulator.current)
        assertEquals(0, accumulator.revisionCount)
    }

    @Test
    fun `first message is accepted as current`() {
        val accumulator = TranscriptAccumulator()
        assertEquals("hello world", accumulator.accept("hello world"))
        assertEquals("hello world", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `exact duplicate of current is ignored`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the meeting", accumulator.accept("schedule the meeting"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `cumulative extension ends as the longest value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule")
        accumulator.accept("schedule the")
        assertEquals("schedule the meeting", accumulator.accept("schedule the meeting"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(3, accumulator.revisionCount)
    }

    @Test
    fun `extension preserves inner spaces and does not trim`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("word ")
        assertEquals("word count", accumulator.accept("word count"))
        assertEquals("word count", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `reverse prefix partial message does not shrink current`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the meeting", accumulator.accept("schedule the"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `message that merely extends a word is a correction not a merge`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule")
        assertEquals("scheduled", accumulator.accept("scheduled"))
        assertEquals("scheduled", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `correction replaces the prior provisional value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the demo", accumulator.accept("schedule the demo"))
        assertEquals("schedule the demo", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `revision count increments only on actual changes`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        accumulator.accept("hello")
        accumulator.accept("   ")
        accumulator.accept("hello world")
        accumulator.accept("hello wo")
        accumulator.accept("goodbye world")
        assertEquals(3, accumulator.revisionCount)
        assertEquals("goodbye world", accumulator.current)
    }

    @Test
    fun `settledText returns null before any accept and current afterwards`() {
        val accumulator = TranscriptAccumulator()
        assertNull(accumulator.settledText())
        accumulator.accept("hello")
        assertEquals("hello", accumulator.settledText())
    }

    @Test
    fun `reset clears current and revision count`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        accumulator.accept("hello world")
        accumulator.reset()
        assertNull(accumulator.current)
        assertNull(accumulator.settledText())
        assertEquals(0, accumulator.revisionCount)
        assertEquals("again", accumulator.accept("again"))
        assertEquals(1, accumulator.revisionCount)
    }

    // ------------------------------------------------------------------
    // 0.4.2: anti-shrink and delta-append hardening
    // ------------------------------------------------------------------

    @Test
    fun `strictly shorter non-prefix revision never shrinks the current value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("I want to order a pizza for delivery")
        assertEquals("I want to order a pizza for delivery", accumulator.accept("order pizza"))
        assertEquals("I want to order a pizza for delivery", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `same length correction still replaces the prior value`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the demo", accumulator.accept("schedule the demo"))
        assertEquals("schedule the demo", accumulator.current)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `appendDeltas accumulates streamed word deltas into the full echo`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("This is")
        accumulator.accept(" a test")
        accumulator.accept(" of the")
        accumulator.accept(" system.")
        assertEquals("This is a test of the system.", accumulator.current)
        assertEquals(4, accumulator.revisionCount)
    }

    @Test
    fun `appendDeltas still ignores reverse prefix partial messages`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("schedule the meeting")
        assertEquals("schedule the meeting", accumulator.accept("schedule the"))
        assertEquals("schedule the meeting", accumulator.current)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `appendDeltas does not append an overlapping revision as new content`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("I want pizza")
        // A revision whose leading word overlaps the current tail is a correction,
        // not a delta continuation.
        assertEquals("I want pasta", accumulator.accept("I want pasta"))
        assertEquals("I want pasta", accumulator.current)
    }

    @Test
    fun `appendDeltas replaces a provisional final word fragment`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("please sched")

        assertEquals("please schedule", accumulator.accept("please schedule"))
    }

    @Test
    fun `appendDeltas keeps the longest value when a cumulative extension arrives after deltas`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("hello")
        accumulator.accept(" world")
        assertEquals("hello world", accumulator.current)
        assertEquals("hello world there", accumulator.accept("hello world there"))
        assertEquals("hello world there", accumulator.current)
    }

    @Test
    fun `merge only accumulator replaces delta style messages as before`() {
        // Without appendDeltas the old replace semantics stay for the ASR source.
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello")
        assertEquals("world", accumulator.accept("world"))
        assertEquals("world", accumulator.current)
    }

    @Test
    fun `acceptWithResult distinguishes state changes from ignored messages`() {
        val accumulator = TranscriptAccumulator()

        val first = accumulator.acceptWithResult("hello")
        assertTrue(first.changed)
        assertEquals("hello", first.text)

        assertFalse(accumulator.acceptWithResult("hello").changed)
        assertFalse(accumulator.acceptWithResult("   ").changed)

        val extension = accumulator.acceptWithResult("hello world")
        assertTrue(extension.changed)
        assertEquals("hello world", extension.text)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `punctuation-only revision reports a real text change`() {
        val accumulator = TranscriptAccumulator()
        accumulator.accept("hello world!")

        val revision = accumulator.acceptWithResult("hello world")

        assertTrue(revision.changed)
        assertEquals("hello world", revision.text)
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `appendDeltas merges the maximal multiword suffix prefix overlap`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("alpha beta gamma delta")

        assertEquals(
            "alpha beta gamma delta epsilon zeta",
            accumulator.accept("gamma delta epsilon zeta"),
        )
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `maximal overlap handles repeated connector words without duplication`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("tea and biscuits and tea and")

        assertEquals(
            "tea and biscuits and tea and coffee",
            accumulator.accept("tea and coffee"),
        )
    }

    @Test
    fun `cumulative correction wins over a coincidental tail overlap`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("go home and go")

        assertEquals("go home and stay", accumulator.accept("go home and stay"))
        assertEquals(2, accumulator.revisionCount)
    }

    @Test
    fun `correction may revise the first token while preserving the remainder`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("Their meeting starts Friday")

        assertEquals(
            "The meeting starts Friday",
            accumulator.accept("The meeting starts Friday"),
        )
    }

    @Test
    fun `unrelated equal length messages remain true deltas`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("alpha beta")

        assertEquals("alpha beta gamma delta", accumulator.accept("gamma delta"))
    }

    @Test
    fun `fully overlapped replay is unchanged`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("one two three")

        val replay = accumulator.acceptWithResult("two three")
        assertFalse(replay.changed)
        assertEquals("one two three", replay.text)
        assertEquals(1, accumulator.revisionCount)
    }

    @Test
    fun `overlap uses incoming terminal punctuation`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("Hello world")

        assertEquals("Hello world! Again", accumulator.accept("world! Again"))
    }

    @Test
    fun `punctuation delta attaches after trailing whitespace`() {
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("Hello ")

        assertEquals("Hello, world", accumulator.accept(", world"))
    }

    @Test
    fun `all overlap lengths append each continuation exactly once`() {
        val prefix = listOf("zero", "one", "two", "three", "four", "five")
        val continuation = listOf("six", "seven")
        val expected = (prefix + continuation).joinToString(" ")

        for (overlap in 1..prefix.size) {
            val accumulator = TranscriptAccumulator(appendDeltas = true)
            accumulator.accept(prefix.joinToString(" "))
            val message = (prefix.takeLast(overlap) + continuation).joinToString(" ")

            assertEquals(expected, accumulator.accept(message), "overlap=$overlap")
        }
    }

    @Test
    fun `user-reported duplicate tail - long correction replaces instead of appending`() {
        // Regression for the reported "a segment of my utterance appended to the
        // end": a cumulative revision that corrects the first word of a long
        // utterance was appended whole, duplicating the tail. The overlap merge
        // must treat it as a revision.
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        accumulator.accept("I want to order a pizza for delivery tonight")

        assertEquals(
            "I need to order a pizza for delivery tonight around eight",
            accumulator.accept("I need to order a pizza for delivery tonight around eight"),
        )
    }

    @Test
    fun `user-reported dropped word - repeated connector in a delta stream is kept`() {
        // Regression for the "word transposed/lost" variant: a delta whose leading
        // word repeats a recent connector must still be appended, never dropped.
        val accumulator = TranscriptAccumulator(appendDeltas = true)
        for (message in listOf("I", "want", "to", "go", "to", "the", "store")) {
            accumulator.accept(message)
        }

        assertEquals("I want to go to the store", accumulator.current)
    }
}
