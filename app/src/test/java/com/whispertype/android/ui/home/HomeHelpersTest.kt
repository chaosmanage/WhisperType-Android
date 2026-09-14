package com.whispertype.android.ui.home

import com.whispertype.android.data.history.HistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class HomeHelpersTest {

    private fun fixedMondayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 7, 14, 30, 0) // Monday, Sept 7, 2026
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun fixedWednesdayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 9, 10, 0, 0) // Wednesday, Sept 9, 2026
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun makeEntry(
        text: String,
        timestampMillis: Long,
        outcome: String = "Success",
        durationMs: Long = 60_000L,
    ) = HistoryRepository.HistoryEntry(
        id = text.hashCode().toString(),
        timestampMillis = timestampMillis,
        text = text,
        language = "HINGLISH",
        charCount = text.length,
        outcome = outcome,
        durationMs = durationMs,
    )

    @Test
    fun testTrailingWindowStart() {
        // Monday Sept 7 2026 -> window covers Sept 1 (Tue) through Sept 7 (Mon).
        val monday = fixedMondayMillis()
        val start = HomeHelpers.trailingWindowStartMillis(monday)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
    }

    @Test
    fun testComputeWeeklyBarsState_emptyHistory() {
        val now = fixedMondayMillis()
        val state = HomeHelpers.computeWeeklyBarsState(emptyList(), now)

        assertEquals(0, state.weekWords)
        assertEquals(0, state.weekSessions)
        assertNull(state.weekWpm)
        assertEquals(7, state.days.size)
        assertTrue(state.days.all { it.words == 0 })
        assertEquals("No words recorded in the last 7 days", state.semanticsDescription)
        assertTrue(state.days[6].isCurrentDay)
        assertFalse(state.days[5].isCurrentDay)
        assertFalse(state.days.any { it.isEarlierActive })
        // Trailing window Sept 1 (Tue) .. Sept 7 (Mon) with stacked date labels.
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), state.days.map { it.dateLabel })
        assertEquals(listOf("T", "W", "T", "F", "S", "S", "M"), state.days.map { it.initial })
        assertEquals("Monday", state.days[6].dayName)
    }

    @Test
    fun testComputeWeeklyBarsState_singleDayEntry() {
        val now = fixedMondayMillis()
        val entry = makeEntry("one two three four five", now)
        val state = HomeHelpers.computeWeeklyBarsState(listOf(entry), now)

        assertEquals(5, state.weekWords)
        assertEquals(1, state.weekSessions)
        assertEquals(5, state.days[6].words)
        assertEquals("5 words in the last 7 days, recorded on Monday", state.semanticsDescription)
    }

    @Test
    fun testComputeWeeklyBarsState_multiDayEntries() {
        val wednesdayNow = fixedWednesdayMillis()
        val monday = fixedMondayMillis()
        val e1 = makeEntry("one two three four five", monday)
        val e2 = makeEntry("six seven eight nine ten", wednesdayNow)
        val state = HomeHelpers.computeWeeklyBarsState(listOf(e1, e2), wednesdayNow)

        assertEquals(10, state.weekWords)
        assertEquals(2, state.weekSessions)
        // Trailing window Sept 3 (Thu) .. Sept 9 (Wed): Monday lands at index 4.
        assertTrue(state.days[4].isEarlierActive)
        assertEquals(5, state.days[4].words)
        assertEquals("Monday", state.days[4].dayName)
        assertTrue(state.days[6].isCurrentDay)
        assertEquals(5, state.days[6].words)
        assertEquals("10 words in the last 7 days, recorded on Monday and Wednesday", state.semanticsDescription)
    }

    @Test
    fun testComputeWeeklyBarsState_excludesEntriesBeforeWindow() {
        val now = fixedMondayMillis()
        val windowStart = HomeHelpers.trailingWindowStartMillis(now)
        val inside = makeEntry("one two three", windowStart + 60_000L)
        val outside = makeEntry("four five six seven eight", windowStart - 60_000L)
        val state = HomeHelpers.computeWeeklyBarsState(listOf(inside, outside), now)

        assertEquals(3, state.weekWords)
        assertEquals(1, state.weekSessions)
    }

    @Test
    fun testFormatCount() {
        assertEquals("0", HomeHelpers.formatCount(0))
        assertEquals("450", HomeHelpers.formatCount(450))
        assertEquals("999", HomeHelpers.formatCount(999))
        assertEquals("1,000", HomeHelpers.formatCount(1000))
        assertEquals("1,200", HomeHelpers.formatCount(1200))
        assertEquals("15,400", HomeHelpers.formatCount(15400))
        assertEquals("1,000,000", HomeHelpers.formatCount(1_000_000))
    }

    @Test
    fun testComputeGlanceMetrics_emptyHistory() {
        val metrics = HomeHelpers.computeGlanceMetrics(emptyList())

        assertEquals("0", metrics.sessionsValue)
        assertEquals("All-time sessions", metrics.sessionsLabel)
        assertEquals("—", metrics.wpmValue)
        assertEquals("No timed sessions yet", metrics.wpmLabel)
        assertEquals("0", metrics.perSessionValue)
        assertEquals("Words / session", metrics.perSessionLabel)
    }

    @Test
    fun testComputeGlanceMetrics_withSessionsAndDurations() {
        val now = fixedMondayMillis()
        val e1 = makeEntry("a ".repeat(100).trim(), now, durationMs = 60_000L) // 100 words, 1 min
        val e2 = makeEntry("b ".repeat(50).trim(), now, durationMs = 30_000L) // 50 words, 0.5 min
        val metrics = HomeHelpers.computeGlanceMetrics(listOf(e1, e2))

        assertEquals("2", metrics.sessionsValue)
        assertEquals("All-time sessions", metrics.sessionsLabel)
        assertEquals("100", metrics.wpmValue)
        assertEquals("Words / min", metrics.wpmLabel)
        assertEquals("75", metrics.perSessionValue)
        assertEquals("Words / session", metrics.perSessionLabel)
    }

    @Test
    fun testComputeGlanceMetrics_zeroDurationSessions() {
        val now = fixedMondayMillis()
        val e1 = makeEntry("test dictation text", now, durationMs = 0L)
        val metrics = HomeHelpers.computeGlanceMetrics(listOf(e1))

        assertEquals("1", metrics.sessionsValue)
        assertEquals("—", metrics.wpmValue)
        assertEquals("No timed sessions yet", metrics.wpmLabel)
        assertEquals("3", metrics.perSessionValue)
    }

    @Test
    fun testFormatRecentSnippetMeta() {
        val now = fixedMondayMillis()
        val todayEntry = makeEntry("hello world", now, outcome = "Success")
        val metaToday = HomeHelpers.formatRecentSnippetMeta(
            entry = todayEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertTrue(metaToday.startsWith("Today · "))
        assertTrue(metaToday.endsWith(" · Inserted"))

        val copiedEntry = makeEntry("hello world", now, outcome = "CopiedToClipboard")
        val metaCopied = HomeHelpers.formatRecentSnippetMeta(
            entry = copiedEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertTrue(metaCopied.endsWith(" · Copied"))

        val pastEntry = makeEntry("old text", now - 2 * 86_400_000L, outcome = "Success")
        val metaPast = HomeHelpers.formatRecentSnippetMeta(
            entry = pastEntry,
            nowMillis = now,
            todayLabel = "Today",
            insertedLabel = "Inserted",
            copiedLabel = "Copied",
            locale = Locale.US,
        )
        assertFalse(metaPast.startsWith("Today"))
        assertTrue(metaPast.contains("Sep 5"))
    }
}
