package com.whispertype.android.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiTranscriptAssemblerTest {

    @Test
    fun nullDeltaIsIgnored() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append(null, null)
        assertEquals(TranscriptCandidates(null, null), assembler.snapshot())
    }

    @Test
    fun rawAndCleanedDeltasAccumulateIndependently() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append("first ", "F first ")
        assembler.append(null, "F second")
        assembler.append("second", null)
        val snapshot = assembler.snapshot()
        assertEquals("first second", snapshot.raw)
        assertEquals("F first F second", snapshot.cleaned)
    }

    @Test
    fun snapshotReturnsNullWhenNothingReceived() {
        val assembler = GeminiTranscriptAssembler()
        assertEquals(TranscriptCandidates(null, null), assembler.snapshot())
        assembler.append(null, null)
        assertEquals(TranscriptCandidates(null, null), assembler.snapshot())
    }

    @Test
    fun completeTurnStartsANewTurn() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append("one", "ONE")
        val first = assembler.completeTurn()
        assertEquals("one", first.raw)
        assertEquals("ONE", first.cleaned)
        assertEquals(1, assembler.turnCount)
        assembler.append("two", "TWO")
        assertEquals("two", assembler.snapshot().raw)
        assertEquals(2, assembler.turnCount)
    }

    @Test
    fun completeTurnWithNoDeltasReturnsEmptyCandidatesAndStillCounts() {
        val assembler = GeminiTranscriptAssembler()
        assertEquals(TranscriptCandidates(null, null), assembler.completeTurn())
        assertEquals(1, assembler.turnCount)
    }

    @Test
    fun turnCountGrowsAcrossMultipleTurns() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append("a", "A")
        assembler.completeTurn()
        assembler.append("b", "B")
        assembler.completeTurn()
        assembler.append("c", "C")
        assembler.completeTurn()
        assertEquals(3, assembler.turnCount)
    }

    @Test
    fun snapshotDoesNotResetStream() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append("a", "A")
        assembler.snapshot()
        assembler.append("b", "B")
        assertEquals("ab", assembler.snapshot().raw)
        assertEquals("AB", assembler.snapshot().cleaned)
    }

    @Test
    fun snapshotReturnsDistinctValues() {
        val assembler = GeminiTranscriptAssembler()
        assembler.append("one", "ONE")
        val first = assembler.snapshot()
        val second = assembler.snapshot()
        assertEquals(first.raw, second.raw)
        assertEquals(first.cleaned, second.cleaned)
    }
}
