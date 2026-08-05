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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        var readyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var startResult: SendResult = SendResult.Accepted
        var endResult: SendResult = SendResult.Accepted
        var audioResult: SendResult = SendResult.Accepted
        var startCalls = 0
        var endCalls = 0
        var audioCalls = 0
        var closed = false
        val receivedChunks = mutableListOf<AudioChunk>()

        override suspend fun awaitReady() {
            readyError?.let { throw it }
            readyGate?.await()
        }

        override suspend fun startActivity(): SendResult {
            startCalls++
            return startResult
        }

        override suspend fun sendAudio(chunk: AudioChunk): SendResult {
            audioCalls++
            receivedChunks += chunk
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
        val finished = mutableListOf<Pair<DictationState, MutableSessionMetrics>>()
        var resolveResult: SessionResolve = SessionResolve.Ok(SessionResolution(session, LanguageMode.ENGLISH))
        var captureStart: CaptureStart = CaptureStart.Started(capture)
        var insertionAccepted = true

        override fun publish(state: DictationState) {
            published += state
        }

        override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve = resolveResult

        override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart = captureStart

        override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
            insertions += sessionId to text
            return insertionAccepted
        }

        override fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics) {
            finished += state to metrics
        }
    }

    private fun states(host: FakeHost): List<DictationState> = host.published

    private fun FakeHost.last(): DictationState = published.last()

    private fun listeningId(host: FakeHost): SessionId =
        (states(host).first { it is DictationState.Listening } as DictationState.Listening).sessionId

    private fun coordinator(scope: kotlinx.coroutines.test.TestScope, host: FakeHost): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            metricsFactory = { sessionId -> MutableSessionMetrics(sessionId) { 0L } },
        )

    private fun coordinator(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
        config: DictationCoordinator.Config,
    ): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = config,
            metricsFactory = { sessionId -> MutableSessionMetrics(sessionId) { 0L } },
        )

    private fun chunk(seq: Long): AudioChunk = AudioChunk(seq, ByteArray(640) { it.toByte() }, sampleRateHz = 16_000)

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

    // ------------------------------------------------------------------
    // Release E: transcript settlement and fast finalization
    // ------------------------------------------------------------------

    private suspend fun sendTranscript(host: FakeHost, text: String) {
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = text, cleaned = null, language = LanguageMode.ENGLISH)),
            ),
        )
    }

    @Test
    fun `transcript before turn completion settles via debounce and inserts the final revision`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "schedule")
        host.session.events.send(GeminiEvent.TurnComplete)
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("schedule the meeting", host.insertions[0].second)
        assertFalse(
            coordinator.activeMetrics()!!.usedHardDeadline,
            "settlement must come from the settle debounce, not the hard deadline",
        )
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `multiple revisions during debounce settle exactly once with the longest value`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "schedule")
        advanceTimeBy(100)
        sendTranscript(host, "schedule the")
        advanceTimeBy(100)
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("schedule the meeting", host.insertions[0].second)
        assertFalse(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `transcript arriving just before the deadline is settled and inserted`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(2_800)
        sendTranscript(host, "the birch canoe")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe", host.insertions[0].second)
        assertTrue(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `deadline cannot be extended by repeated transcript revisions`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        // Continuous revisions keep resetting the debounce; the absolute 3s
        // deadline must still settle regardless.
        for (i in 1..29) {
            advanceTimeBy(100)
            sendTranscript(host, "schedule the meeting please")
        }
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("schedule the meeting please", host.insertions[0].second)
        assertTrue(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `turn complete before STOP is retained and used during finalization`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        // Server completes the turn while still listening (E7).
        host.session.events.send(GeminiEvent.TurnComplete)
        advanceTimeBy(1)

        coordinator.stop()
        runCurrent()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("hello world", host.insertions[0].second)
        assertFalse(coordinator.activeMetrics()!!.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `clearly provisional single-character transcript at the hard deadline fails`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(2_900)
        sendTranscript(host, "t")
        advanceTimeBy(200) // crosses the 3s deadline with only "t"

        assertTrue(host.insertions.isEmpty())
        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_no_transcript", (error as DictationState.Error).failure.code)
        assertTrue(coordinator.activeMetrics()!!.usedHardDeadline)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // Release F: immediate capture and pre-ready buffering
    // ------------------------------------------------------------------

    @Test
    fun `cold session buffers pre-ready audio and drains in strict order once ready`() = runTest {
        val host = FakeHost()
        val session = host.session
        session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceTimeBy(1)

        val connecting = states(host).filterIsInstance<DictationState.Listening>().last()
        assertTrue(connecting.connecting, "cold path must publish recording-while-connecting")

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        advanceTimeBy(1)
        assertEquals(0, session.audioCalls, "audio must be buffered while the session connects")

        session.readyGate!!.complete(Unit)
        host.capture.chunksChannel.send(chunk(2))
        advanceTimeBy(1)

        assertEquals(listOf(0L, 1L, 2L), session.receivedChunks.map { it.sequence })
        assertFalse(
            states(host).filterIsInstance<DictationState.Listening>().last().connecting,
            "the connecting indicator must clear once the session is ready",
        )
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `pre-ready buffer overflow fails with connection-too-slow`() = runTest {
        val host = FakeHost()
        val session = host.session
        session.readyGate = kotlinx.coroutines.CompletableDeferred() // never becomes ready
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(preReadyMaxFrames = 2),
        )
        coordinator.start()
        advanceTimeBy(1)

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        advanceTimeBy(1)
        host.capture.chunksChannel.send(chunk(2)) // overflow
        advanceTimeBy(1)

        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_connection_too_slow", (error as DictationState.Error).failure.code)
        assertTrue(session.audioCalls == 0)
        advanceUntilIdle()
    }
}
