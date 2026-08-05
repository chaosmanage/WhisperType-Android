package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.AudioStartResult
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.model.SessionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Host-testable orchestration races (Release C8), run under virtual time with a
 * fake [DictationHost], [GeminiLiveSession], and [AudioPipeline].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationCoordinatorTest {

    private class FakeSession : GeminiLiveSession {
        val events = Channel<GeminiEvent>(Channel.UNLIMITED)
        var readyError: Throwable? = null
        var startResult: SendResult = SendResult.Accepted
        var endResult: SendResult = SendResult.Accepted
        var audioResult: SendResult = SendResult.Accepted
        var startCalls = 0
        var endCalls = 0
        var audioCalls = 0
        var closed = false

        override suspend fun awaitReady() {
            readyError?.let { throw it }
        }

        override suspend fun startActivity(): SendResult {
            startCalls++
            return startResult
        }

        override suspend fun sendAudio(chunk: AudioChunk): SendResult {
            audioCalls++
            return audioResult
        }

        override suspend fun endActivity(): SendResult {
            endCalls++
            return endResult
        }

        override fun events() = events.receiveAsFlow()

        override suspend fun close() {
            closed = true
            events.close()
        }
    }

    private class FakeCapture : AudioPipeline {
        val chunksChannel = Channel<AudioChunk>(Channel.UNLIMITED)
        override val chunks = chunksChannel
        override val amplitude: StateFlow<Float> = MutableStateFlow(0f)
        private val _failures = MutableSharedFlow<DictationFailure>(replay = 1)
        override val failures = _failures
        var startResult = AudioStartResult.Started
        var stopRequested = false
        var stopCalls = 0

        override fun start(): AudioStartResult = startResult

        override fun requestStop() {
            stopRequested = true
            chunksChannel.close()
        }

        override suspend fun awaitQuiescence(timeoutMs: Long): Boolean = true

        override fun stop() {
            stopCalls++
            chunksChannel.close()
        }

        suspend fun fail(failure: DictationFailure) {
            _failures.emit(failure)
        }
    }

    private class FakeHost : DictationHost {
        val published = mutableListOf<DictationState>()
        val insertions = mutableListOf<Pair<SessionId, String>>()
        val session = FakeSession()
        val capture = FakeCapture()
        var resolveResult: SessionResolve = SessionResolve.Ok(SessionResolution(session, LanguageMode.ENGLISH))
        var captureStart: CaptureStart = CaptureStart.Started(capture)
        var insertionAccepted = true

        override fun publish(state: DictationState) {
            published += state
        }

        override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve = resolveResult

        override fun startCapture(metrics: MutableSessionMetrics): CaptureStart = captureStart

        override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
            insertions += sessionId to text
            return insertionAccepted
        }
    }

    private fun states(host: FakeHost): List<DictationState> = host.published

    private fun listeningId(host: FakeHost): SessionId =
        (states(host).first { it is DictationState.Listening } as DictationState.Listening).sessionId

    private fun coordinator(scope: kotlinx.coroutines.test.TestScope, host: FakeHost): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            metricsFactory = { sessionId -> MutableSessionMetrics(sessionId) { 0L } },
        )

    @Test
    fun `duplicate START is rejected synchronously`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        assertTrue(coordinator.start())
        assertFalse(coordinator.start())
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `cancel during setup publishes Cancelled then resets to Idle and allows restart`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        assertTrue(coordinator.start())
        coordinator.cancel()
        advanceUntilIdle()

        assertIs<DictationState.Cancelled>(states(host).first { it is DictationState.Cancelled })
        assertEquals(DictationState.Idle, states(host).last())
        // After the delayed reset the same coordinator accepts a new session.
        assertTrue(coordinator.start())
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `STOP immediately after listening starts finalizes then fails with no transcript and resets`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        assertEquals(1, host.session.startCalls)

        coordinator.stop()
        advanceUntilIdle()

        val finalizing = states(host).first { it is DictationState.Finalizing }
        assertEquals(1, host.session.endCalls)
        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_no_transcript", (error as DictationState.Error).failure.code)
        assertEquals(DictationState.Idle, states(host).last())
        assertTrue(host.insertions.isEmpty())
    }

    @Test
    fun `STOP with a transcript inserts exactly once and insertion result completes the session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)

        coordinator.stop()
        // Trailing inputTranscription arrives after STOP; turn complete arrives too.
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = "the birch canoe slid", cleaned = null, language = LanguageMode.ENGLISH)),
            ),
        )
        host.session.events.send(GeminiEvent.TurnComplete)
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe slid", host.insertions[0].second)
        assertIs<DictationState.Inserting>(states(host).last())

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertIs<DictationState.Success>(states(host).first { it is DictationState.Success })
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `late insertion response for a cancelled session is ignored`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.cancel()
        advanceUntilIdle()

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertTrue(states(host).none { it is DictationState.Success })
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `capture read failure fails the session and tears down`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())

        host.capture.fail(DictationFailure(code = "MIC_READ", message = "Microphone read failed", recoverable = true))
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error }
        assertEquals("MIC_READ", (error as DictationState.Error).failure.code)
        assertTrue(host.session.closed)
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `rejected activity start fails promptly with a transport failure`() = runTest {
        val host = FakeHost()
        host.session.startResult = SendResult.Rejected("socket_closed")
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_transport", (error as DictationState.Error).failure.code)
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `STOP during Starting is ignored`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        coordinator.stop() // before the session even resolves
        advanceUntilIdle()

        // Not finalizing: the session still reached Listening.
        assertTrue(states(host).none { it is DictationState.Finalizing })
        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `no transcript before hard deadline produces no_transcript and single teardown`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        assertTrue(host.session.closed)
        assertEquals(1, states(host).count { it is DictationState.Error })
        assertEquals(DictationState.Idle, states(host).last())
    }
}
