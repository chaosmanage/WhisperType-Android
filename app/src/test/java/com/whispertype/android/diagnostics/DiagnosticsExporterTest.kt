package com.whispertype.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the in-memory diagnostics buffer is bounded, privacy-safe, and records
 * typed events with optional durations (Implementation Plan §19, §20, docs/TESTING.md).
 */
class DiagnosticsExporterTest {

    @Test
    fun recordEmitsTypedEvent() {
        DiagnosticsExporter.clear()
        DiagnosticsExporter.record("test.event")
        val snapshot = DiagnosticsExporter.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("test.event", snapshot[0].name)
        assertEquals(null, snapshot[0].durationMillis)
    }

    @Test
    fun recordWithDurationEmitsAggregate() {
        DiagnosticsExporter.clear()
        DiagnosticsExporter.record("test.timed", 123L)
        val event = DiagnosticsExporter.snapshot().single()
        assertEquals("test.timed", event.name)
        assertEquals(123L, event.durationMillis)
    }

    @Test
    fun bufferIsBounded() {
        DiagnosticsExporter.clear()
        repeat(300) { DiagnosticsExporter.record("test.burst") }
        val snapshot = DiagnosticsExporter.snapshot()
        assertTrue("buffer must stay bounded", snapshot.size <= 256)
    }

    @Test
    fun clearEmptiesBuffer() {
        DiagnosticsExporter.record("test.one")
        DiagnosticsExporter.clear()
        assertTrue(DiagnosticsExporter.snapshot().isEmpty())
    }

    @Test
    fun exportContainsNoTranscriptNorKeyTerms() {
        DiagnosticsExporter.clear()
        DiagnosticsExporter.record("dictation.inserted", 42L)
        val export = DiagnosticsExporter.export(context = null)
        assertTrue(export.contains("dictation.inserted"))
        assertFalse(export.contains("AIza"))
        assertFalse(export.contains("generativelanguage.googleapis.com/ws?key"))
        assertFalse(export.contains("transcript_"))
    }
}