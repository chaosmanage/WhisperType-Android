package com.whispertype.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [MutableSessionMetrics] using a fake monotonic clock. */
class MutableSessionMetricsTest {

    private var now = 0L
    private val metrics = MutableSessionMetrics(SessionId("s1")) { now }

    private fun advanceMillis(ms: Long) {
        now += ms * 1_000_000
    }

    @Test
    fun `timestamps start null and counters start zero`() {
        assertNull(metrics.tapAt)
        assertNull(metrics.captureStartedAt)
        assertNull(metrics.stopAt)
        assertNull(metrics.insertionResultAt)
        assertEquals(0L, metrics.capturedFrames)
        assertEquals(0L, metrics.acceptedFrames)
        assertEquals(0L, metrics.rejectedFrames)
        assertEquals(0, metrics.maxWebSocketQueueSize)
        assertEquals(0L, metrics.inputTranscriptionCount)
        assertEquals(0L, metrics.outputTranscriptionCount)
        assertFalse(metrics.turnCompleteArrived)
        assertFalse(metrics.usedHardDeadline)
        assertFalse(metrics.audioBufferOverflow)
    }

    @Test
    fun `mark records events and is first-wins`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(2)
        metrics.mark(MutableSessionMetrics.Event.Tap)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(1)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)

        assertEquals(0L, metrics.tapAt)
        assertEquals(2_000_000L, metrics.captureStartedAt)
        assertEquals(2L, metrics.tapToCaptureMs())
    }

    @Test
    fun `derived durations are computed from the fake monotonic clock`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(10)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(5)
        metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
        advanceMillis(30)
        metrics.mark(MutableSessionMetrics.Event.FirstInputTranscript)
        advanceMillis(7)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(20)
        metrics.mark(MutableSessionMetrics.Event.CaptureQuiesced)
        metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued)
        advanceMillis(40)
        metrics.mark(MutableSessionMetrics.Event.TurnComplete)
        advanceMillis(60)
        metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        metrics.mark(MutableSessionMetrics.Event.InsertionRequested)
        advanceMillis(80)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)

        assertEquals(10L, metrics.tapToCaptureMs())
        assertEquals(10L, metrics.tapToSetupCompleteMs())
        assertEquals(15L, metrics.tapToFirstAudioQueuedMs())
        assertEquals(30L, metrics.firstAudioToFirstTranscriptMs())
        assertEquals(20L, metrics.stopToCaptureQuiescedMs())
        assertEquals(20L, metrics.stopToActivityEndQueuedMs())
        assertEquals(60L, metrics.stopToTurnCompleteMs())
        assertEquals(120L, metrics.stopToSettledMs())
        assertEquals(200L, metrics.stopToInsertionResultMs())
        assertEquals(80L, metrics.insertionRequestedToResultMs())
    }

    @Test
    fun `durations are null until both endpoints exist`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        assertNull(metrics.tapToCaptureMs())
        assertNull(metrics.stopToInsertionResultMs())
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        assertEquals(0L, metrics.tapToCaptureMs())
    }

    @Test
    fun `counters and flags accumulate correctly`() {
        metrics.capturedFrames += 10
        metrics.acceptedFrames += 8
        metrics.rejectedFrames += 2
        metrics.inputTranscriptionCount += 3
        metrics.outputTranscriptionCount += 1
        metrics.turnCompleteArrived = true
        metrics.usedHardDeadline = true
        metrics.audioBufferOverflow = true

        assertEquals(10L, metrics.capturedFrames)
        assertEquals(8L, metrics.acceptedFrames)
        assertEquals(2L, metrics.rejectedFrames)
        assertEquals(3L, metrics.inputTranscriptionCount)
        assertEquals(1L, metrics.outputTranscriptionCount)
        assertTrue(metrics.turnCompleteArrived)
        assertTrue(metrics.usedHardDeadline)
        assertTrue(metrics.audioBufferOverflow)
    }

    @Test
    fun `recordWebSocketQueue tracks the maximum`() {
        metrics.recordWebSocketQueue(4)
        metrics.recordWebSocketQueue(2)
        metrics.recordWebSocketQueue(9)
        metrics.recordWebSocketQueue(7)
        assertEquals(9, metrics.maxWebSocketQueueSize)
    }

    @Test
    fun `snapshot maps derived fields and started and ended timestamps`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(12)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
        advanceMillis(5)
        metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
        advanceMillis(30)
        metrics.mark(MutableSessionMetrics.Event.FirstInputTranscript)
        advanceMillis(7)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(100)
        metrics.mark(MutableSessionMetrics.Event.TurnComplete)
        advanceMillis(60)
        metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        advanceMillis(80)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        metrics.acceptedFrames = 42
        metrics.rejectedFrames = 3
        metrics.maxWebSocketQueueSize = 16
        metrics.inputTranscriptionCount = 5
        metrics.outputTranscriptionCount = 2
        metrics.turnCompleteArrived = true
        metrics.usedHardDeadline = false
        metrics.audioBufferOverflow = false

        val snapshot = metrics.snapshot()

        assertEquals(SessionId("s1"), snapshot.sessionId)
        assertEquals(0L, snapshot.startedAtMillis)
        assertEquals(294L, snapshot.endedAtMillis)
        assertEquals(12L, snapshot.tapToCaptureMs)
        assertEquals(12L, snapshot.tapToSetupCompleteMs)
        assertEquals(17L, snapshot.tapToFirstAudioQueuedMs)
        assertEquals(30L, snapshot.firstAudioToFirstTranscriptMs)
        assertEquals(100L, snapshot.stopToTurnCompleteMs)
        assertEquals(160L, snapshot.stopToSettledMs)
        assertEquals(240L, snapshot.stopToInsertionResultMs)
        assertNull(snapshot.insertionRequestedToResultMs)
        assertEquals(42L, snapshot.acceptedFrames)
        assertEquals(3L, snapshot.rejectedFrames)
        assertEquals(16, snapshot.maxWebSocketQueueSize)
        assertEquals(5L, snapshot.inputTranscriptionCount)
        assertEquals(2L, snapshot.outputTranscriptionCount)
        assertTrue(snapshot.turnCompleteArrived)
        assertFalse(snapshot.usedHardDeadline)
        assertFalse(snapshot.audioBufferOverflow)
    }

    @Test
    fun `snapshot with no timestamps maps startedAt to zero and endedAt to null`() {
        val snapshot = metrics.snapshot()
        assertEquals(0L, snapshot.startedAtMillis)
        assertNull(snapshot.endedAtMillis)
        assertNull(snapshot.tapToCaptureMs)
        assertNull(snapshot.stopToInsertionResultMs)
    }

    @Test
    fun `summary contains only derived duration flag and counter tokens`() {
        metrics.mark(MutableSessionMetrics.Event.Tap)
        advanceMillis(12)
        metrics.mark(MutableSessionMetrics.Event.SetupComplete)
        advanceMillis(140)
        metrics.mark(MutableSessionMetrics.Event.Stop)
        advanceMillis(800)
        metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        metrics.acceptedFrames = 123
        metrics.rejectedFrames = 0
        metrics.maxWebSocketQueueSize = 32
        metrics.inputTranscriptionCount = 5
        metrics.turnCompleteArrived = true

        val summary = metrics.summary()

        assertEquals(
            "setup=12ms stopToInsert=800ms " +
                "captured=0 accepted=123 rejected=0 maxQueue=32 inputTx=5 outputTx=0 " +
                "turnComplete=true hardDeadline=false overflow=false",
            summary,
        )
        assertTrue("insertToResult" !in summary)
        assertTrue("transcript" !in summary)
        assertTrue("s1" !in summary)
    }

    @Test
    fun `summary omits null duration segments but keeps all counts and flags`() {
        val summary = metrics.summary()
        assertTrue("tapToCapture" !in summary)
        assertTrue("setup" !in summary)
        assertTrue("accepted=0" in summary)
        assertTrue("turnComplete=false" in summary)
    }
}
