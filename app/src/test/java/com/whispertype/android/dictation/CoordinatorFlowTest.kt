package com.whispertype.android.dictation

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import com.whispertype.android.accessibility.InsertionOutcome
import com.whispertype.android.accessibility.TargetToken
import com.whispertype.android.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.audio.AudioCapture
import com.whispertype.android.audio.AudioChunk
import com.whispertype.android.gemini.GeminiEvent
import com.whispertype.android.gemini.GeminiFailure
import com.whispertype.android.gemini.GeminiLiveConnection
import com.whispertype.android.gemini.LanguageMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end coordinator flow tests against fakes. The injected [kotlinx.coroutines.CoroutineScope]
 * is the runTest [TestScope.backgroundScope], so every session coroutine runs on the test scheduler
 * and is cancelled automatically when a test ends.
 *
 * Note on driving: the coordinator runs entirely in [TestScope.backgroundScope], and
 * [advanceUntilIdle] stops advancing virtual time as soon as only background work remains,
 * so it cannot drive the session forward; tests therefore advance time by discrete steps
 * and pump with runCurrent. Events are emitted only AFTER stop() because SessionEnd is
 * consumed from the finalize channel that doFinalize opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorFlowTest {

    @Before
    fun installFakeAccessibilityService() {
        WhisperTypeAccessibilityService.shared = FakeAccessibilityService()
    }

    @After
    fun uninstallFakeAccessibilityService() {
        WhisperTypeAccessibilityService.shared = null
    }

    @Test
    fun beginDrivesStartingToListeningAndForwardsAudio() = runTest {
        val h = buildHarness()
        assertTrue(h.coordinator.begin(token()))
        assertTrue(h.coordinator.state.value is DictationState.Starting)
        runCurrent()
        assertTrue(h.coordinator.state.value is DictationState.Listening)

        h.capture.pushChunk()
        assertEquals(1, h.connection.sent.count { it == "audio" })
        flush()
        assertEquals(1, h.connection.sent.count { it == "audio" })
    }

    @Test
    fun stopFinalizesAndStopsCaptureBeforeActivityEnd() = runTest {
        val h = buildHarness()
        h.coordinator.begin(token())
        runCurrent()
        h.capture.pushChunk()
        flush()

        h.coordinator.stop()
        advanceTimeBy(100)
        runCurrent()

        assertTrue(h.capture.stopped)
        val endIndex = h.connection.sent.indexOf("end")
        assertTrue("activity-end must be sent", endIndex >= 0)
        assertEquals(1, h.connection.sent.count { it == "end" })
        assertTrue(h.connection.sent.subList(0, endIndex).all { it == "audio" })
        assertTrue(h.connection.sent.subList(endIndex + 1, h.connection.sent.size).none { it == "audio" })
        assertEquals(endIndex, h.connection.sent.lastIndex)
    }

    @Test
    fun sessionEndInsertsCleanedTextThenSuccessThenAutoDismisses() = runTest {
        val h = buildHarness(insertionResult = InsertionOutcome.Success)
        h.coordinator.begin(token())
        runCurrent()
        h.coordinator.stop()
        advanceTimeBy(100)
        runCurrent()

        h.connection.emit(GeminiEvent.SessionEnd("raw text", "cleaned text"))
        flush()

        assertEquals(listOf("cleaned text"), h.insertionCalls)
        val st = h.coordinator.state.value as DictationState.Success
        assertEquals(12, st.insertedTextLength)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(DictationState.DockedReady, h.coordinator.state.value)
    }

    @Test
    fun emptyCleanedCandidateFallsBackToRaw() = runTest {
        val h = buildHarness(insertionResult = InsertionOutcome.Success)
        h.coordinator.begin(token())
        runCurrent()
        h.coordinator.stop()
        advanceTimeBy(100)
        runCurrent()

        h.connection.emit(GeminiEvent.SessionEnd("raw good", ""))
        flush()

        assertEquals(listOf("raw good"), h.insertionCalls)
    }

    @Test
    fun insertionFailureOffersCopyOnceAndDismiss() = runTest {
        val h = buildHarness(insertionResult = InsertionOutcome.Failure("field unreachable", true))
        h.coordinator.begin(token())
        runCurrent()
        h.coordinator.stop()
        advanceTimeBy(100)
        runCurrent()

        h.connection.emit(GeminiEvent.SessionEnd("raw text", "cleaned text"))
        flush()

        val st = h.coordinator.state.value as DictationState.CopyAvailable
        assertEquals("cleaned text", st.resultText)

        h.coordinator.copyResult()
        assertEquals(listOf("cleaned text"), h.copyCalls)
        h.coordinator.copyResult()
        assertEquals(1, h.copyCalls.size)
        assertTrue(h.coordinator.state.value is DictationState.CopyAvailable)

        h.coordinator.dismissCopy()
        assertEquals(DictationState.DockedReady, h.coordinator.state.value)
    }

    @Test
    fun failedEventMidSessionTransitionsToErrorAndInsertsNothing() = runTest {
        val h = buildHarness()
        h.coordinator.begin(token())
        runCurrent()

        h.connection.emit(GeminiEvent.Failed(GeminiFailure("SERVER_ERROR", "boom", false)))
        flush()

        val st = h.coordinator.state.value as DictationState.Error
        assertEquals("SERVER_ERROR", st.failure.code)
        assertTrue(h.insertionCalls.isEmpty())
    }

    @Test
    fun beginWhileSessionActiveReturnsFalse() = runTest {
        val h = buildHarness()
        assertTrue(h.coordinator.begin(token()))
        assertFalse(h.coordinator.begin(token()))
        runCurrent()
        assertTrue(h.coordinator.state.value is DictationState.Listening)
    }

    @Test
    fun waitingForResultTimesOutIntoError() = runTest {
        val h = buildHarness()
        h.coordinator.begin(token())
        runCurrent()

        h.coordinator.stop()
        advanceTimeBy(20_000)
        advanceUntilIdle()

        val st = h.coordinator.state.value as DictationState.Error
        assertEquals("TIMEOUT_WAITING_RESULT", st.failure.code)
    }

    @Test
    fun sessionEndAfterCancelIsIgnored() = runTest {
        val h = buildHarness()
        h.coordinator.begin(token())
        runCurrent()

        h.coordinator.cancel()
        assertEquals(DictationState.Cancelled, h.coordinator.state.value)

        h.connection.emit(GeminiEvent.SessionEnd("raw text", "cleaned text"))
        flush()

        assertTrue(h.insertionCalls.isEmpty())
        assertTrue(h.connection.sent.contains("close"))
    }

    private fun TestScope.buildHarness(insertionResult: InsertionOutcome? = InsertionOutcome.Success): Harness {
        val insertionCalls = mutableListOf<String>()
        val copyCalls = mutableListOf<String>()
        val connection = FakeConnection()
        val capture = FakeCapture()
        val coordinator = DictationCoordinator(
            context = FakeContext(),
            apiKeyProvider = { "AIza-test" },
            inserter = { _, text ->
                insertionCalls.add(text)
                insertionResult
            },
            copier = { text ->
                copyCalls.add(text)
                true
            },
            clientFactory = { _, _, _ -> connection },
            captureFactory = { capture },
            foregroundServiceLauncher = { },
            modeProvider = { LanguageMode.HINGLISH },
            idleStateProvider = { DictationState.DockedReady },
            clock = { 0L },
            scope = backgroundScope,
        )
        return Harness(coordinator, connection, capture, insertionCalls, copyCalls)
    }

    private fun token(sessionId: String = "test-session"): TargetToken = TargetToken(
        sessionId = sessionId,
        inputGeneration = 1L,
        packageName = "com.example",
        displayId = 0,
        windowId = null,
        fieldId = null,
        inputType = 1,
        initialSelectionStart = 0,
        initialSelectionEnd = 0,
        imeBounds = Rect(),
    )

    private suspend fun TestScope.flush() {
        repeat(3) { runCurrent() }
    }

    private class Harness(
        val coordinator: DictationCoordinator,
        val connection: FakeConnection,
        val capture: FakeCapture,
        val insertionCalls: MutableList<String>,
        val copyCalls: MutableList<String>,
    )

    private class FakeConnection : GeminiLiveConnection {
        val sent = mutableListOf<String>()
        private val eventsFlow = MutableSharedFlow<GeminiEvent>(extraBufferCapacity = 16)

        override val events: Flow<GeminiEvent> = eventsFlow

        override fun sendAudio(chunk: AudioChunk) {
            sent += "audio"
        }

        override fun sendActivityEnd() {
            sent += "end"
        }

        override fun close() {
            sent += "close"
        }

        fun emit(event: GeminiEvent) {
            eventsFlow.tryEmit(event)
        }
    }

    private class FakeCapture : AudioCapture(clock = { 0L }) {
        val chunks = ArrayDeque<AudioChunk>()
        var started = false
        var stopped = false
        private var onChunkCb: ((AudioChunk) -> Unit)? = null

        override fun start(onChunk: (AudioChunk) -> Unit): AudioStartResult {
            started = true
            onChunkCb = onChunk
            return AudioStartResult.Started
        }

        override fun stop() {
            stopped = true
        }

        fun pushChunk() {
            val chunk = AudioChunk(chunks.size.toLong(), ByteArray(640), 0L)
            chunks.addLast(chunk)
            onChunkCb?.invoke(chunk)
        }
    }

    private class FakeAccessibilityService : WhisperTypeAccessibilityService() {
        override fun isUsable(): Boolean = true
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.whispertype.android.test"

        override fun startService(service: Intent?): ComponentName? =
            ComponentName("com.whispertype.android.test", "FakeService")

        override fun startForegroundService(service: Intent?): ComponentName? =
            ComponentName("com.whispertype.android.test", "FakeService")
    }
}
