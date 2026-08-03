package com.whispertype.android.diagnostics

import android.content.Context

/**
 * In-memory ring buffer (256 events) of typed, non-sensitive codes and aggregate
 * timing metrics (Implementation Plan §19, §20). Nothing here ever contains a
 * transcript, raw audio, an API key, the authenticated Gemini URL, editor text,
 * or AccessibilityNode content.
 *
 * [record] is safe to call from any thread. The buffer lives only in memory and
 * is cleared on process death; it is never persisted.
 */
object DiagnosticsExporter {

    private const val MAX_EVENTS = 256

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>()

    /** Records a typed event without a duration. */
    fun record(name: String) {
        synchronized(lock) { append(DiagnosticEvent(name = name, atMillis = System.currentTimeMillis())) }
    }

    /** Records a typed event with an aggregate duration in milliseconds. */
    fun record(name: String, durationMillis: Long) {
        synchronized(lock) {
            append(DiagnosticEvent(name = name, atMillis = System.currentTimeMillis(), durationMillis = durationMillis))
        }
    }

    /** Returns a copy of the recorded events, oldest first. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    /** Removes all recorded events. */
    fun clear() {
        synchronized(lock) { events.clear() }
    }

    private fun append(event: DiagnosticEvent) {
        events.addLast(event)
        if (events.size > MAX_EVENTS) events.removeFirst()
    }

    /**
     * Produces a privacy-safe, plain-text diagnostics export for sharing. Includes
     * version info and the recorded event codes/durations only. [context] is used
     * only to read the app version and may be null in unit tests.
     */
    fun export(context: Context?): String {
        val sb = StringBuilder()
        sb.appendLine("Diagnostics export")
        sb.appendLine("version=${context?.let { versionName(it) } ?: "unknown"}")
        sb.appendLine("events=${snapshot().size}")
        snapshot().forEach { event ->
            val duration = event.durationMillis?.let { " duration=$it" } ?: ""
            sb.appendLine("${event.atMillis}:${event.name}$duration")
        }
        return sb.toString()
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}