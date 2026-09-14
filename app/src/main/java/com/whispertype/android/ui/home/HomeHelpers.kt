package com.whispertype.android.ui.home

import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.history.HistoryStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class DayBarData(
    val dayName: String,
    val initial: String,
    val dateLabel: String,
    val words: Int,
    val isCurrentDay: Boolean,
    val isEarlierActive: Boolean,
)

data class WeeklyBarsState(
    val weekWords: Int,
    val weekSessions: Int,
    val weekWpm: Int?,
    val days: List<DayBarData>,
    val semanticsDescription: String,
)

data class HomeGlanceMetrics(
    val sessionsValue: String,
    val sessionsLabel: String,
    val wpmValue: String,
    val wpmLabel: String,
    val perSessionValue: String,
    val perSessionLabel: String,
)

object HomeHelpers {

    private val WEEKDAY_NAMES = listOf(
        "Monday",
        "Tuesday",
        "Wednesday",
        "Thursday",
        "Friday",
        "Saturday",
        "Sunday",
    )

    private val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Milliseconds at the local start of the oldest day in the trailing 7-day window. */
    fun trailingWindowStartMillis(nowMillis: Long): Long {
        val startOfToday = HistoryStats.startOfTodayMillis(nowMillis)
        return startOfToday - 6 * MILLIS_PER_DAY
    }

    fun computeWeeklyBarsState(
        entries: List<HistoryRepository.HistoryEntry>,
        nowMillis: Long,
    ): WeeklyBarsState {
        val windowStart = trailingWindowStartMillis(nowMillis)

        val days = (0..6).map { i ->
            val dayStart = windowStart + i * MILLIS_PER_DAY
            val dayEnd = dayStart + MILLIS_PER_DAY
            val words = entries
                .filter { it.timestampMillis in dayStart until dayEnd }
                .sumOf { HistoryStats.contentWords(it.text).size }
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            // Index into the Monday-first name/initial tables.
            val nameIndex = (dayOfWeek + 5) % 7
            val isCurrentDay = (i == 6)
            val isEarlierActive = (i < 6 && words > 0)
            DayBarData(
                dayName = WEEKDAY_NAMES[nameIndex],
                initial = WEEKDAY_INITIALS[nameIndex],
                dateLabel = cal.get(Calendar.DAY_OF_MONTH).toString(),
                words = words,
                isCurrentDay = isCurrentDay,
                isEarlierActive = isEarlierActive,
            )
        }

        val windowEnd = windowStart + 7 * MILLIS_PER_DAY
        val weekEntries = entries.filter {
            it.timestampMillis >= windowStart && it.timestampMillis < windowEnd
        }
        val weekWords = days.sumOf { it.words }
        val weekSessions = weekEntries.size
        val weekWpm = HistoryStats.wordsPerMinute(weekEntries)?.roundToInt()

        val semanticsDescription = buildWeeklySemantics(weekWords, days)

        return WeeklyBarsState(
            weekWords = weekWords,
            weekSessions = weekSessions,
            weekWpm = weekWpm,
            days = days,
            semanticsDescription = semanticsDescription,
        )
    }

    fun buildWeeklySemantics(weekWords: Int, days: List<DayBarData>): String {
        if (weekWords == 0) return "No words recorded in the last 7 days"
        val activeDays = days.filter { it.words > 0 }.map { it.dayName }
        val wordNoun = if (weekWords == 1) "word" else "words"
        val recordedOn = when (activeDays.size) {
            0 -> ""
            1 -> ", recorded on ${activeDays[0]}"
            2 -> ", recorded on ${activeDays[0]} and ${activeDays[1]}"
            else -> {
                val head = activeDays.dropLast(1).joinToString(", ")
                ", recorded on $head, and ${activeDays.last()}"
            }
        }
        return "$weekWords $wordNoun in the last 7 days$recordedOn"
    }

    fun computeGlanceMetrics(
        entries: List<HistoryRepository.HistoryEntry>,
        allTimeSessionsLabel: String = "All-time sessions",
        avgWpmLabel: String = "Words / min",
        noTimedSessionsLabel: String = "No timed sessions yet",
        avgWordsPerSessionLabel: String = "Words / session",
    ): HomeGlanceMetrics {
        val sessions = HistoryStats.sessions(entries)
        val wpm = HistoryStats.wordsPerMinute(entries)?.roundToInt()
        val perSession = HistoryStats.wordsPerSession(entries)

        return HomeGlanceMetrics(
            sessionsValue = sessions.toString(),
            sessionsLabel = allTimeSessionsLabel,
            wpmValue = wpm?.toString() ?: "—",
            wpmLabel = if (wpm != null) avgWpmLabel else noTimedSessionsLabel,
            perSessionValue = perSession.toString(),
            perSessionLabel = avgWordsPerSessionLabel,
        )
    }

    fun formatCount(n: Int): String =
        if (n >= 1000) {
            val k = n / 1000f
            if (k >= 10) "${k.roundToInt()}k" else String.format(Locale.US, "%.1fk", k)
        } else {
            n.toString()
        }

    fun formatRecentSnippetMeta(
        entry: HistoryRepository.HistoryEntry,
        nowMillis: Long,
        todayLabel: String = "Today",
        insertedLabel: String = "Inserted",
        copiedLabel: String = "Copied",
        locale: Locale = Locale.getDefault(),
    ): String {
        val startOfToday = HistoryStats.startOfTodayMillis(nowMillis)
        val whenLabel = if (entry.timestampMillis >= startOfToday) {
            todayLabel
        } else {
            SimpleDateFormat("MMM d", locale).format(Date(entry.timestampMillis))
        }
        val timeFmt = SimpleDateFormat("h:mm a", locale)
        val timeStr = timeFmt.format(Date(entry.timestampMillis)).lowercase(locale)
        val outcome = when (entry.outcome) {
            "Success" -> insertedLabel
            "CopiedToClipboard", "CopyAvailable" -> copiedLabel
            else -> null
        }
        return buildString {
            append(whenLabel)
            append(" · ")
            append(timeStr)
            if (outcome != null) {
                append(" · ")
                append(outcome)
            }
        }
    }
}
