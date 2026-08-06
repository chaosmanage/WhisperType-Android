package com.whispertype.android.data.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Unit tests for the pure 0.4.2 home stats aggregates. */
class HistoryStatsTest {

    private fun entry(
        text: String,
        timestampMillis: Long,
        durationMs: Long = 0L,
    ) = HistoryRepository.HistoryEntry(
        id = "e",
        timestampMillis = timestampMillis,
        text = text,
        language = "ENGLISH",
        charCount = text.length,
        outcome = "Success",
        durationMs = durationMs,
    )

    @Test
    fun `sessions counts entries`() {
        assertEquals(0, HistoryStats.sessions(emptyList()))
        assertEquals(2, HistoryStats.sessions(listOf(entry("a", 0L), entry("b", 1L))))
    }

    @Test
    fun `total words counts letter and digit runs`() {
        val entries = listOf(entry("the quick brown fox", 0L), entry("123 456", 1L))
        assertEquals(6, HistoryStats.totalWords(entries))
    }

    @Test
    fun `today words only counts entries after the local start of today`() {
        val now = System.currentTimeMillis()
        val startOfToday = HistoryStats.startOfTodayMillis(now)
        val today = entry("today words", now, 0L)
        val yesterday = entry("yesterday words here", startOfToday - 60_000L, 0L)
        assertEquals(2, HistoryStats.todayWords(listOf(today, yesterday), now))
    }

    @Test
    fun `words per minute uses recorded durations`() {
        // 60 words over exactly 60 seconds -> 60 wpm.
        val text = (1..60).joinToString(" ") { "word$it" }
        val entries = listOf(entry(text, 0L, 60_000L))
        assertEquals(60.0, HistoryStats.wordsPerMinute(entries)!!, 0.5)
    }

    @Test
    fun `words per minute is null without duration evidence`() {
        assertNull(HistoryStats.wordsPerMinute(emptyList()))
        assertNull(HistoryStats.wordsPerMinute(listOf(entry("some words", 0L, 0L))))
    }

    @Test
    fun `start of today is midnight local time`() {
        val now = 1_700_000_000_000L
        val start = HistoryStats.startOfTodayMillis(now)
        assert(start <= now)
        // The start-of-day is on the same local day as now.
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = start
        assertEquals(dayOfYear, cal.get(java.util.Calendar.DAY_OF_YEAR))
    }
}
