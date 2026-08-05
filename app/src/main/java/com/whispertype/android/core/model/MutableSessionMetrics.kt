package com.whispertype.android.core.model

/**
 * Session-local monotonic timing and aggregate counters for dictation diagnostics.
 *
 * All timestamps use the injectable monotonic clock (never wall clock) so that
 * durations are meaningful across device sleep and testable with a fake clock.
 */
class MutableSessionMetrics(
    val sessionId: SessionId,
    private val nowNanos: () -> Long = { android.os.SystemClock.elapsedRealtimeNanos() },
) {

    enum class Event {
        Tap,
        KeyLoadStarted,
        KeyLoaded,
        SettingsReady,
        SocketCreated,
        SocketOpen,
        SetupComplete,
        CaptureStartRequested,
        CaptureStarted,
        ActivityStartQueued,
        FirstAudioQueued,
        FirstInputTranscript,
        Stop,
        CaptureQuiesced,
        LastAudioQueued,
        ActivityEndQueued,
        TurnComplete,
        TranscriptSettled,
        InsertionRequested,
        InsertionResult,
    }

    var tapAt: Long? = null
    var keyLoadStartedAt: Long? = null
    var keyLoadedAt: Long? = null
    var settingsReadyAt: Long? = null
    var socketCreatedAt: Long? = null
    var socketOpenAt: Long? = null
    var setupCompleteAt: Long? = null
    var captureStartRequestedAt: Long? = null
    var captureStartedAt: Long? = null
    var activityStartQueuedAt: Long? = null
    var firstAudioQueuedAt: Long? = null
    var firstInputTranscriptAt: Long? = null
    var stopAt: Long? = null
    var captureQuiescedAt: Long? = null
    var lastAudioQueuedAt: Long? = null
    var activityEndQueuedAt: Long? = null
    var turnCompleteAt: Long? = null
    var transcriptSettledAt: Long? = null
    var insertionRequestedAt: Long? = null
    var insertionResultAt: Long? = null

    var capturedFrames: Long = 0
    var acceptedFrames: Long = 0
    var rejectedFrames: Long = 0
    var maxWebSocketQueueSize: Int = 0
    var inputTranscriptionCount: Long = 0
    var outputTranscriptionCount: Long = 0
    var turnCompleteArrived: Boolean = false
    var usedHardDeadline: Boolean = false
    var audioBufferOverflow: Boolean = false

    /** Records the first timestamp observed for [event]; later marks are ignored. */
    fun mark(event: Event) {
        val now = nowNanos()
        when (event) {
            Event.Tap -> tapAt = tapAt ?: now
            Event.KeyLoadStarted -> keyLoadStartedAt = keyLoadStartedAt ?: now
            Event.KeyLoaded -> keyLoadedAt = keyLoadedAt ?: now
            Event.SettingsReady -> settingsReadyAt = settingsReadyAt ?: now
            Event.SocketCreated -> socketCreatedAt = socketCreatedAt ?: now
            Event.SocketOpen -> socketOpenAt = socketOpenAt ?: now
            Event.SetupComplete -> setupCompleteAt = setupCompleteAt ?: now
            Event.CaptureStartRequested -> captureStartRequestedAt = captureStartRequestedAt ?: now
            Event.CaptureStarted -> captureStartedAt = captureStartedAt ?: now
            Event.ActivityStartQueued -> activityStartQueuedAt = activityStartQueuedAt ?: now
            Event.FirstAudioQueued -> firstAudioQueuedAt = firstAudioQueuedAt ?: now
            Event.FirstInputTranscript -> firstInputTranscriptAt = firstInputTranscriptAt ?: now
            Event.Stop -> stopAt = stopAt ?: now
            Event.CaptureQuiesced -> captureQuiescedAt = captureQuiescedAt ?: now
            Event.LastAudioQueued -> lastAudioQueuedAt = lastAudioQueuedAt ?: now
            Event.ActivityEndQueued -> activityEndQueuedAt = activityEndQueuedAt ?: now
            Event.TurnComplete -> turnCompleteAt = turnCompleteAt ?: now
            Event.TranscriptSettled -> transcriptSettledAt = transcriptSettledAt ?: now
            Event.InsertionRequested -> insertionRequestedAt = insertionRequestedAt ?: now
            Event.InsertionResult -> insertionResultAt = insertionResultAt ?: now
        }
    }

    fun recordWebSocketQueue(size: Int) {
        if (size > maxWebSocketQueueSize) maxWebSocketQueueSize = size
    }

    private fun durationMs(from: Long?, to: Long?): Long? {
        if (from == null || to == null) return null
        return (to - from) / 1_000_000
    }

    fun tapToCaptureMs(): Long? = durationMs(tapAt, captureStartedAt)

    fun tapToSetupCompleteMs(): Long? = durationMs(tapAt, setupCompleteAt)

    fun tapToFirstAudioQueuedMs(): Long? = durationMs(tapAt, firstAudioQueuedAt)

    fun firstAudioToFirstTranscriptMs(): Long? = durationMs(firstAudioQueuedAt, firstInputTranscriptAt)

    fun stopToCaptureQuiescedMs(): Long? = durationMs(stopAt, captureQuiescedAt)

    fun stopToActivityEndQueuedMs(): Long? = durationMs(stopAt, activityEndQueuedAt)

    fun stopToTurnCompleteMs(): Long? = durationMs(stopAt, turnCompleteAt)

    fun stopToSettledMs(): Long? = durationMs(stopAt, transcriptSettledAt)

    fun stopToInsertionResultMs(): Long? = durationMs(stopAt, insertionResultAt)

    fun insertionRequestedToResultMs(): Long? = durationMs(insertionRequestedAt, insertionResultAt)

    fun snapshot(): DictationMetrics = DictationMetrics(
        sessionId = sessionId,
        startedAtMillis = (tapAt ?: 0L) / 1_000_000,
        endedAtMillis = (insertionResultAt ?: transcriptSettledAt)?.div(1_000_000),
        tapToCaptureMs = tapToCaptureMs(),
        tapToSetupCompleteMs = tapToSetupCompleteMs(),
        tapToFirstAudioQueuedMs = tapToFirstAudioQueuedMs(),
        firstAudioToFirstTranscriptMs = firstAudioToFirstTranscriptMs(),
        stopToCaptureQuiescedMs = stopToCaptureQuiescedMs(),
        stopToActivityEndQueuedMs = stopToActivityEndQueuedMs(),
        stopToTurnCompleteMs = stopToTurnCompleteMs(),
        stopToSettledMs = stopToSettledMs(),
        stopToInsertionResultMs = stopToInsertionResultMs(),
        insertionRequestedToResultMs = insertionRequestedToResultMs(),
        acceptedFrames = acceptedFrames,
        rejectedFrames = rejectedFrames,
        inputTranscriptionCount = inputTranscriptionCount,
        outputTranscriptionCount = outputTranscriptionCount,
        maxWebSocketQueueSize = maxWebSocketQueueSize,
        turnCompleteArrived = turnCompleteArrived,
        usedHardDeadline = usedHardDeadline,
        audioBufferOverflow = audioBufferOverflow,
    )

    /** Single non-sensitive log line: only derived durations, flags, and counts. */
    fun summary(): String = buildList {
        durationToken("tapToCapture", tapToCaptureMs())?.let(::add)
        durationToken("setup", tapToSetupCompleteMs())?.let(::add)
        durationToken("tapToFirstAudio", tapToFirstAudioQueuedMs())?.let(::add)
        durationToken("firstAudioToFirstTranscript", firstAudioToFirstTranscriptMs())?.let(::add)
        durationToken("stopToQuiesce", stopToCaptureQuiescedMs())?.let(::add)
        durationToken("stopToActivityEnd", stopToActivityEndQueuedMs())?.let(::add)
        durationToken("stopToTurnComplete", stopToTurnCompleteMs())?.let(::add)
        durationToken("stopToSettled", stopToSettledMs())?.let(::add)
        durationToken("stopToInsert", stopToInsertionResultMs())?.let(::add)
        durationToken("insertToResult", insertionRequestedToResultMs())?.let(::add)
        add("captured=$capturedFrames")
        add("accepted=$acceptedFrames")
        add("rejected=$rejectedFrames")
        add("maxQueue=$maxWebSocketQueueSize")
        add("inputTx=$inputTranscriptionCount")
        add("outputTx=$outputTranscriptionCount")
        add("turnComplete=$turnCompleteArrived")
        add("hardDeadline=$usedHardDeadline")
        add("overflow=$audioBufferOverflow")
    }.joinToString(" ")

    private fun durationToken(name: String, ms: Long?): String? = ms?.let { "$name=${it}ms" }
}
