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
import com.whispertype.android.core.model.SettlePath
import com.whispertype.android.core.model.SettlementReason
import com.whispertype.android.core.model.TerminalOutcome
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
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
        var endGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var startResult: SendResult = SendResult.Accepted
        var endResult: SendResult = SendResult.Accepted
        var audioResult: SendResult = SendResult.Accepted
        var startCalls = 0
        var endCalls = 0
        var audioCalls = 0
        var closed = false
        val receivedChunks = mutableListOf<AudioChunk>()
        val wireCalls = mutableListOf<String>()

        override suspend fun awaitReady() {
            readyError?.let { throw it }
            readyGate?.await()
        }

        override suspend fun startActivity(): SendResult {
            startCalls++
            wireCalls += "start"
            return startResult
        }

        override suspend fun sendAudio(chunk: AudioChunk): SendResult {
            audioCalls++
            receivedChunks += chunk
            wireCalls += "audio:${chunk.sequence}"
            return audioResult
        }

        override suspend fun endActivity(): SendResult {
            endCalls++
            endGate?.await()
            wireCalls += "end"
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
        var queueCapacity = 0
        var queueDepth = 0

        override val frameQueueCapacity: Int get() = queueCapacity

        override fun queuedFrameDepth(chunk: AudioChunk): Int = queueDepth

        fun setAmplitude(level: Float) {
            (amplitude as MutableStateFlow<Float>).value = level
        }

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
        val finishedTranscripts = mutableListOf<String?>()
        var resolveResult: SessionResolve = SessionResolve.Ok(SessionResolution(session, LanguageMode.ENGLISH))
        var captureStart: CaptureStart = CaptureStart.Started(capture)
        var insertionAccepted = true
        var transliterateResult: String? = null
        val transliterateCalls = mutableListOf<String>()
        var clipboardResult = true
        val clipboardCopies = mutableListOf<Pair<SessionId, String>>()

        override fun publish(state: DictationState) {
            published += state
        }

        override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve = resolveResult

        override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart = captureStart

        override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
            insertions += sessionId to text
            return insertionAccepted
        }

        override suspend fun copyToClipboard(sessionId: SessionId, text: String): Boolean {
            clipboardCopies += sessionId to text
            return clipboardResult
        }

        override suspend fun transliterateToLatin(sessionId: SessionId, text: String): String? {
            transliterateCalls += text
            return transliterateResult
        }

        override fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?) {
            finished += state to metrics
            finishedTranscripts += transcript
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
            config = DictationCoordinator.Config(insertionResultTimeoutMs = 0)
                .withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { scope.testScheduler.currentTime * 1_000_000L }
            },
        )

    private fun coordinator(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
        config: DictationCoordinator.Config,
    ): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = config.copy(insertionResultTimeoutMs = 0).withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { scope.testScheduler.currentTime * 1_000_000L }
            },
        )

    /** Keeps the capture-shutdown dispatcher on the virtual scheduler so the
     *  host tests stay deterministic instead of hopping to Dispatchers.IO. */
    private fun DictationCoordinator.Config.withTestShutdownDispatcher(
        scope: kotlinx.coroutines.test.TestScope,
    ): DictationCoordinator.Config {
        val interceptor = scope.coroutineContext[ContinuationInterceptor]
        return if (interceptor is CoroutineDispatcher) {
            copy(captureShutdownDispatcher = interceptor)
        } else {
            this
        }
    }

    private fun chunk(
        seq: Long,
        frameMillis: Int = 20,
        capturedAtNanos: Long? = null,
    ): AudioChunk = AudioChunk(
        sequence = seq,
        pcm16Bytes = ByteArray(640) { it.toByte() },
        sampleRateHz = 16_000,
        frameMillis = frameMillis,
        capturedAtMonotonicNanos = capturedAtNanos,
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
        assertTrue(error.failure.retryAllowed)
        assertTrue(host.insertions.isEmpty())
        // Retryable error persists until dismissed/retried.
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
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
    fun `interruption and go-away notice do not terminate an active session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()

        host.session.events.send(GeminiEvent.Interrupted)
        host.session.events.send(GeminiEvent.GoAway("12.5s"))
        runCurrent()

        assertIs<DictationState.Listening>(states(host).last())
        coordinator.cancel()
        advanceUntilIdle()
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
        assertTrue(error.failure.retryAllowed)
        // Retryable errors persist until the user dismisses or retries.
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
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
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertTrue(error.failure.retryAllowed)
        assertIs<DictationState.Error>(states(host).last())
        coordinator.dismiss()
        advanceUntilIdle()
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
    fun `duplicate echo does not reset the global quiet debounce`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendEcho(host, "hello world")
        runCurrent()
        advanceTimeBy(200)
        sendEcho(host, "hello world")
        runCurrent()
        advanceTimeBy(101)
        runCurrent()

        assertEquals(listOf("hello world"), host.insertions.map { it.second })
        assertEquals(1L, coordinator.activeMetrics()!!.outputTranscriptionCount)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `raw revision arriving after echo grace settles after quiet not hard deadline`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(
                hardDeadlineMs = 5_000,
                echoFallbackWaitMs = 2_000,
                settleDebounceMs = 600,
            ),
        )
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(2_100)
        sendTranscript(host, "late but complete raw transcript")
        runCurrent()
        advanceTimeBy(599)
        assertTrue(host.insertions.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals("late but complete raw transcript", host.insertions.single().second)
        val metrics = coordinator.activeMetrics()!!
        assertEquals(SettlementReason.RAW_FALLBACK_TIMEOUT, metrics.settlementReason)
        assertFalse(metrics.usedHardDeadline)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `generation complete is a hint and a later echo resets global quiet`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "we should meet")
        host.session.events.send(GeminiEvent.GenerationComplete)
        runCurrent()
        advanceTimeBy(200)
        assertTrue(host.insertions.isEmpty(), "the lifecycle hint cannot bypass transcript quiet")

        sendEcho(host, "We should meet.")
        runCurrent()
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty(), "the echo revision must restart global quiet")
        advanceTimeBy(2)
        runCurrent()

        assertEquals("We should meet.", host.insertions.single().second)
        val metrics = coordinator.activeMetrics()!!
        assertTrue(metrics.generationCompleteArrived)
        assertEquals(SettlementReason.GENERATION_COMPLETE_QUIET, metrics.settlementReason)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `settlement is prohibited until activity end is queued`() = runTest {
        val host = FakeHost()
        host.session.endGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendEcho(host, "hello world")
        host.session.events.send(GeminiEvent.TurnComplete)
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue(host.insertions.isEmpty())
        assertEquals(null, coordinator.activeMetrics()!!.activityEndQueuedAt)

        host.session.endGate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals("hello world", host.insertions.single().second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `transcript arriving just before the deadline is settled and inserted`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(hardDeadlineMs = 3_000, echoFallbackWaitMs = 4_000),
        )
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
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(hardDeadlineMs = 3_000, echoFallbackWaitMs = 4_000),
        )
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
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(hardDeadlineMs = 3_000, echoFallbackWaitMs = 4_000),
        )
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
    fun `STOP before readiness waits then preserves start audio end ordering`() = runTest {
        val host = FakeHost()
        host.session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(readyAfterStopTimeoutMs = 1_000),
        )
        coordinator.start()
        runCurrent()

        host.capture.chunksChannel.send(chunk(0))
        host.capture.chunksChannel.send(chunk(1))
        runCurrent()
        coordinator.stop()
        runCurrent()

        assertTrue(host.session.wireCalls.isEmpty(), "capture closure is not readiness")
        assertEquals(0, host.session.endCalls)

        host.session.readyGate!!.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("start", "audio:0", "audio:1", "end"),
            host.session.wireCalls,
        )
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `STOP before readiness fails after the bounded wait`() = runTest {
        val host = FakeHost()
        host.session.readyGate = kotlinx.coroutines.CompletableDeferred()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(readyAfterStopTimeoutMs = 500),
        )
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        advanceTimeBy(501)
        runCurrent()

        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_setup_timeout", error.failure.code)
        assertTrue(host.session.wireCalls.isEmpty())
        coordinator.dismiss()
        advanceUntilIdle()
    }

    @Test
    fun `rejected audio send fails immediately and never queues activity end`() = runTest {
        val host = FakeHost()
        host.session.audioResult = SendResult.Rejected("socket_closed")
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()

        host.capture.chunksChannel.send(chunk(0))
        runCurrent()

        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_transport", error.failure.code)
        assertEquals(1L, coordinator.activeMetrics()!!.rejectedFrames)
        assertEquals(0L, coordinator.activeMetrics()!!.acceptedFrames)
        assertEquals(0, host.session.endCalls)
        coordinator.dismiss()
        advanceUntilIdle()
    }

    @Test
    fun `audio queue metadata records atomic depth and age high water marks`() = runTest {
        val host = FakeHost()
        host.capture.queueCapacity = 8
        host.capture.queueDepth = 5
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceTimeBy(25)

        host.capture.chunksChannel.send(chunk(0, capturedAtNanos = 0L))
        runCurrent()

        val metrics = coordinator.activeMetrics()!!
        assertEquals(1L, metrics.capturedFrames)
        assertEquals(1L, metrics.acceptedFrames)
        assertEquals(5, metrics.maxFrameQueueDepth)
        assertEquals(25L, metrics.maxFrameQueueAgeMs())
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
        assertTrue(coordinator.activeMetrics()!!.audioBufferOverflow)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // Failsafes: lenient fallback, rejection diagnostics, retry
    // ------------------------------------------------------------------

    @Test
    fun `lenient fallback inserts rejected user speech instead of erroring`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        // English mode with a Devanagari transcript: strict validation rejects it
        // (DEVANAGARI), but the lenient failsafe inserts the user's own speech.
        sendTranscript(host, "नमस्ते दोस्तों")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("नमस्ते दोस्तों", host.insertions[0].second)
        assertEquals("DEVANAGARI", coordinator.activeMetrics()!!.lastRejection)
        assertTrue(coordinator.activeMetrics()!!.usedLenientFallback)
        coordinator.onInsertionResult(host.insertions[0].first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `retry clears a retryable error and starts a fresh session`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertTrue(error.failure.retryAllowed)

        assertTrue(coordinator.retry())
        advanceUntilIdle()
        assertIs<DictationState.Listening>(states(host).last())
        // The errored session logged its outcome exactly once.
        assertEquals(1, host.finished.count { it.first is DictationState.Error })
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `retry is refused while a session is still running`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        assertFalse(coordinator.retry(), "retry must be refused during an active session")
        coordinator.cancel()
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // 0.4.0: auto-stop timeout (silence + hard cap) and history transcript
    // ------------------------------------------------------------------

    private fun coordinatorWith(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
        autoStopSeconds: Long = 0,
        maxRecordingSeconds: Long = 0,
    ): DictationCoordinator =
        DictationCoordinator(
            scope = scope,
            host = host,
            config = DictationCoordinator.Config(
                autoStopSeconds = { autoStopSeconds },
                maxRecordingSeconds = { maxRecordingSeconds },
            ).withTestShutdownDispatcher(scope),
            metricsFactory = { sessionId -> MutableSessionMetrics(sessionId) { 0L } },
        )

    @Test
    fun `auto-stop hard cap finalizes even with continuous speech`() = runTest {
        val host = FakeHost()
        host.capture.setAmplitude(0.5f) // continuous speech: silence never accumulates
        val coordinator = coordinatorWith(this, host, maxRecordingSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        advanceTimeBy(2_200)
        assertTrue(states(host).any { it is DictationState.Finalizing }, "cap must fire at N seconds")
        assertEquals(1, host.session.endCalls)
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `auto-stop silence fires after N seconds of no speech`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())
        host.capture.setAmplitude(0f) // silence

        advanceTimeBy(2_200)
        assertTrue(states(host).any { it is DictationState.Finalizing }, "silence must auto-stop")
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `speech resets the silence timer so auto-stop waits for N seconds of quiet`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 2)
        coordinator.start()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        // Speak at t=1s, go silent at t=2s.
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0.5f)
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0f)

        // Silence is now ~0s: the 2s silence timer must NOT fire at t≈2.2s.
        advanceTimeBy(200)
        assertFalse(states(host).any { it is DictationState.Finalizing }, "recent speech must hold off silence auto-stop")

        // ~2s of quiet later it fires.
        advanceTimeBy(2_000)
        assertTrue(states(host).any { it is DictationState.Finalizing })
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `auto-stop never fires before Listening`() = runTest {
        val host = FakeHost()
        val coordinator = coordinatorWith(this, host, autoStopSeconds = 1)
        coordinator.start()
        coordinator.cancel() // cancelled while connecting/starting
        advanceTimeBy(2_000)
        assertFalse(states(host).any { it is DictationState.Finalizing })
        advanceUntilIdle()
    }

    @Test
    fun `segmentation closes and reopens the activity at a pause`() = runTest {
        // 0.6.0 experimental: with segmentAtSilence on, a quiet pause closes the
        // current activity and reopens it so the model echoes the segment while
        // the user keeps talking.
        val host = FakeHost()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(segmentAtSilence = { true }, segmentSilenceMs = 700),
        )
        coordinator.start()
        runCurrent()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())
        assertEquals(listOf("start"), host.session.wireCalls)

        // Speak (no silence), then pause for >= 700ms.
        host.capture.setAmplitude(0.5f)
        advanceTimeBy(1_000)
        host.capture.setAmplitude(0f)
        advanceTimeBy(800)
        runCurrent()

        assertEquals(listOf("start", "end", "start"), host.session.wireCalls)
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `segmentation does not fire without the setting or during speech`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host) // segmentAtSilence default false
        coordinator.start()
        runCurrent()
        advanceTimeBy(1)
        assertIs<DictationState.Listening>(states(host).last())

        host.capture.setAmplitude(0f)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("start"), host.session.wireCalls, "no activity reopen without the setting")
        coordinator.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `onSessionFinished carries the settled transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
        assertEquals(listOf<String?>("hello world"), host.finishedTranscripts)
    }

    @Test
    fun `onSessionFinished transcript is null when nothing settled`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        assertTrue(host.insertions.isEmpty())
        assertTrue(host.finishedTranscripts.isEmpty(), "a persistent retryable error has not reset yet")
        coordinator.dismiss()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), host.finishedTranscripts)
    }

    // ------------------------------------------------------------------
    // 0.4.1: echo (outputTranscription) is the primary dictation source
    // ------------------------------------------------------------------

    private suspend fun sendEcho(host: FakeHost, text: String) {
        host.session.events.send(
            GeminiEvent.TranscriptCandidates(
                listOf(com.whispertype.android.core.model.ResultCandidate(raw = text, cleaned = null, language = LanguageMode.ENGLISH)),
                source = GeminiEvent.TranscriptSource.ECHO,
            ),
        )
    }

    @Test
    fun `echo transcript is preferred over raw input at settlement`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "um we should like meet") // raw input arrives first
        sendEcho(host, "We should meet.") // styled echo covers the raw content
        advanceTimeBy(700) // past the 600ms settle debounce

        assertEquals(1, host.insertions.size)
        assertEquals("We should meet.", host.insertions[0].second)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `echo-disabled session settles on the raw ASR right after quiet elapses`() = runTest {
        // 0.6.0 NONE/LOW instant path: no output echo is configured, so the raw
        // ASR is the dictation source and settlement needs no echo-fallback wait.
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH, echoEnabled = false),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "the birch canoe slid on the smooth planks")
        advanceTimeBy(249)
        assertTrue(host.insertions.isEmpty(), "must still wait for the quiet debounce")
        advanceTimeBy(2)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe slid on the smooth planks", host.insertions[0].second)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `echo-disabled session inserts raw even when no echo will ever arrive`() = runTest {
        val host = FakeHost()
        host.resolveResult = SessionResolve.Ok(
            SessionResolution(host.session, LanguageMode.ENGLISH, echoEnabled = false),
        )
        val coordinator = coordinator(this, host)
        coordinator.start()
        runCurrent()
        coordinator.stop()
        runCurrent()

        // The server sends only inputTranscription (echo channel disabled).
        sendTranscript(host, "please order a large pepperoni")
        advanceTimeBy(300)
        runCurrent()

        assertEquals(1, host.insertions.size)
        assertEquals("please order a large pepperoni", host.insertions[0].second)
        assertEquals(SettlePath.RAW_ONLY, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(host.insertions.single().first, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `echo absent falls back to raw input after the echo-fallback window`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host) // echoFallbackWaitMs default 2000
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "the birch canoe slid")
        advanceTimeBy(1_000)
        assertTrue(host.insertions.isEmpty(), "must wait for the echo before the fallback window")

        advanceTimeBy(1_500) // crosses the 2s fallback
        assertEquals(1, host.insertions.size)
        assertEquals("the birch canoe slid", host.insertions[0].second)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `an arriving echo prevents the fast raw fallback from settling`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "we should meet tomorrow") // raw arrives immediately
        advanceTimeBy(500)
        sendEcho(host, "We should meet tomorrow.")
        advanceTimeBy(2_500) // well past the echo-fallback window

        assertEquals(1, host.insertions.size)
        assertEquals("We should meet tomorrow.", host.insertions[0].second, "a complete echo must win over raw")
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // 0.4.2 reliability: completeness gate, delta echo, audio recovery
    // ------------------------------------------------------------------

    @Test
    fun `partial echo salvages the complete raw instead of losing words`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendEcho(host, "We should") // echo truncated to the first words
        sendTranscript(host, "We should meet on Thursday") // complete raw ASR
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("We should meet on Thursday", host.insertions[0].second)
        assertEquals(SettlePath.ECHO_PARTIAL_RAW, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `one word summary echo with a complete raw never loses the words`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendEcho(host, "Meeting") // model condensed the whole turn to one word
        sendTranscript(host, "The team meeting is scheduled for nine in the morning")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("The team meeting is scheduled for nine in the morning", host.insertions[0].second)
        assertEquals(SettlePath.ECHO_PARTIAL_RAW, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `delta-style echo chunks are accumulated into the full echo`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        // The server streams outputTranscription as word deltas (0.4.2 probe).
        sendEcho(host, "This is")
        advanceTimeBy(200)
        sendEcho(host, " a test")
        advanceTimeBy(200)
        sendEcho(host, " of the")
        advanceTimeBy(200)
        sendEcho(host, " system.")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("This is a test of the system.", host.insertions[0].second)
        // No raw arrived in this session, so the reconstructed echo is the only
        // source (ECHO_ONLY); the completeness gate has no baseline to compare.
        assertEquals(SettlePath.ECHO_ONLY, coordinator.activeMetrics()!!.settlePath)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `settle during a long echo gap still salvages the raw`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        // Echo deltas stop for longer than the debounce; settlement happens with
        // only the first delta, so the complete raw must be salvaged.
        sendEcho(host, "The quick brown")
        sendTranscript(host, "The quick brown fox jumps over the lazy dog")
        advanceTimeBy(700)
        assertEquals(1, host.insertions.size)
        assertEquals("The quick brown fox jumps over the lazy dog", host.insertions[0].second)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `tiny echo-only result is rejected for a long captured duration`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(
            this,
            host,
            DictationCoordinator.Config(hardDeadlineMs = 2_000),
        )
        coordinator.start()
        runCurrent()
        host.capture.chunksChannel.send(chunk(0, frameMillis = 60_000))
        runCurrent()
        coordinator.stop()
        runCurrent()

        sendEcho(host, "brief reply")
        host.session.events.send(GeminiEvent.GenerationComplete)
        runCurrent()
        advanceTimeBy(700)

        assertTrue(host.insertions.isEmpty())
        assertTrue(states(host).none { it is DictationState.Error })

        advanceTimeBy(1_400)
        runCurrent()
        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("gemini_no_transcript", error.failure.code)
        assertEquals(SettlePath.NONE, coordinator.activeMetrics()!!.settlePath)
        coordinator.dismiss()
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // 0.5.0 Hinglish: echo-only settlement, Latin output, live transliteration
    // ------------------------------------------------------------------

    private fun hinglishCoordinator(
        scope: kotlinx.coroutines.test.TestScope,
        host: FakeHost,
    ): DictationCoordinator {
        host.resolveResult = SessionResolve.Ok(SessionResolution(host.session, LanguageMode.HINGLISH))
        return coordinator(scope, host)
    }

    @Test
    fun `Hinglish with a complete Latin echo inserts the echo without transliterating`() = runTest {
        val host = FakeHost()
        val coordinator = hinglishCoordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "आज का मौसम बहुत अच्छा है") // raw ASR is Devanagari
        sendEcho(host, "Aaj ka mausam bahut achcha hai.") // instructed Latin echo covers it
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("Aaj ka mausam bahut achcha hai.", host.insertions[0].second)
        assertTrue(host.transliterateCalls.isEmpty(), "a complete echo must not transliterate")
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `Hinglish with no echo transliterates the Devanagari raw to Latin`() = runTest {
        val host = FakeHost()
        host.transliterateResult = "Aaj ka mausam bahut achcha hai aur ham picnic par jaa sakte hain"
        val coordinator = hinglishCoordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "आज का मौसम बहुत अच्छा है और हम पिकनिक पर जा सकते हैं")
        advanceUntilIdle()

        assertEquals(1, host.transliterateCalls.size)
        assertEquals(1, host.insertions.size)
        assertEquals("Aaj ka mausam bahut achcha hai aur ham picnic par jaa sakte hain", host.insertions[0].second)
        val metrics = coordinator.activeMetrics()!!
        assertTrue(metrics.repairStartedAt != null)
        assertTrue(metrics.repairCompletedAt != null)
        assertEquals(0L, metrics.repairDurationMs())
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `Hinglish with a partial echo transliterates the complete raw to Latin`() = runTest {
        val host = FakeHost()
        host.transliterateResult = "Aaj ka mausam bahut achcha hai aur ham picnic par jaa sakte hain"
        val coordinator = hinglishCoordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendEcho(host, "Aaj ka") // truncated echo
        sendTranscript(host, "आज का मौसम बहुत अच्छा है और हम पिकनिक पर जा सकते हैं")
        advanceUntilIdle()

        assertEquals(1, host.transliterateCalls.size)
        assertEquals(1, host.insertions.size)
        assertEquals("Aaj ka mausam bahut achcha hai aur ham picnic par jaa sakte hain", host.insertions[0].second)
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    @Test
    fun `Hinglish never inserts the raw Devanagari text`() = runTest {
        val host = FakeHost()
        host.transliterateResult = null // transliteration unavailable
        val coordinator = hinglishCoordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        runCurrent()

        sendTranscript(host, "आज का मौसम बहुत अच्छा है")
        advanceUntilIdle()

        assertTrue(host.insertions.isEmpty(), "Devanagari raw must never be inserted")
        val error = states(host).first { it is DictationState.Error }
        assertEquals("gemini_no_transcript", (error as DictationState.Error).failure.code)
    }

    @Test
    fun `Hinglish transliteration failure falls back to a partial Latin echo`() = runTest {
        val host = FakeHost()
        host.transliterateResult = null
        val coordinator = hinglishCoordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()

        sendEcho(host, "Aaj ka mausam") // partial Latin echo
        sendTranscript(host, "आज का मौसम बहुत अच्छा है और हम पिकनिक पर जा सकते हैं")
        advanceUntilIdle()

        assertEquals(1, host.insertions.size)
        assertEquals("Aaj ka mausam", host.insertions[0].second, "best available Latin text is kept")
        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // Exactly-once insertion result timeout
    // ------------------------------------------------------------------

    @Test
    fun `missing insertion result times out without retrying the commit`() = runTest {
        val host = FakeHost()
        val coordinator = DictationCoordinator(
            scope = this,
            host = host,
            config = DictationCoordinator.Config(
                insertionResultTimeoutMs = 500,
                returnToIdleMs = 1_000,
            ).withTestShutdownDispatcher(this),
            metricsFactory = { sessionId ->
                MutableSessionMetrics(sessionId) { testScheduler.currentTime * 1_000_000L }
            },
        )
        coordinator.start()
        runCurrent()
        val sessionId = listeningId(host)
        coordinator.stop()
        runCurrent()
        sendTranscript(host, "commit this once")
        host.session.events.send(GeminiEvent.TurnComplete)
        runCurrent()
        advanceTimeBy(601)
        runCurrent()

        assertIs<DictationState.Inserting>(states(host).last())
        assertEquals(1, host.insertions.size)

        advanceTimeBy(501)
        runCurrent()
        val error = states(host).filterIsInstance<DictationState.Error>().last()
        assertEquals("insert_result_timeout", error.failure.code)
        assertFalse(error.failure.retryAllowed)
        assertEquals(TerminalOutcome.TIMED_OUT, coordinator.activeMetrics()!!.terminalOutcome)
        assertFalse(coordinator.retry(), "an ambiguous commit must never be retried")

        coordinator.onInsertionResult(sessionId, InsertionResult.Inserted)
        runCurrent()
        assertEquals(1, host.insertions.size)
        assertTrue(states(host).none { it is DictationState.Success })
        advanceUntilIdle()
        assertEquals(DictationState.Idle, states(host).last())
    }

    // ------------------------------------------------------------------
    // 0.5.8: clipboard fallback when the transcript cannot reach a field
    // ------------------------------------------------------------------

    @Test
    fun `failed insertion with no focused field copies the transcript to clipboard`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "hello world"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
        assertTrue(
            host.finished.last().second.copiedToClipboardAt != null,
            "the session must record the clipboard fallback metric",
        )
    }

    @Test
    fun `failed insertion with an unreachable input connection copies the transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "schedule the meeting")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_connection_unavailable",
                    message = "Could not reach the focused text field. Tap the field and try again.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "schedule the meeting"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `failed insertion with a stale target copies the transcript`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "the birch canoe slid")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_stale",
                    message = "The focused field changed before insertion. Tap the field and try again.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(sessionId to "the birch canoe slid"), host.clipboardCopies)
        assertIs<DictationState.CopiedToClipboard>(
            states(host).first { it is DictationState.CopiedToClipboard },
        )
        assertEquals(DictationState.Idle, states(host).last())
    }

    @Test
    fun `a protected field failure never copies to clipboard and surfaces an error`() = runTest {
        val host = FakeHost()
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "do not leak me")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_not_safe",
                    message = "A protected field was focused; text was not inserted.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(host.clipboardCopies.isEmpty(), "a secure field must never copy the transcript")
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertEquals("insert_target_not_safe", error.failure.code)
    }

    @Test
    fun `clipboard copy failure falls back to an Error surface`() = runTest {
        val host = FakeHost()
        host.clipboardResult = false
        val coordinator = coordinator(this, host)
        coordinator.start()
        advanceUntilIdle()
        val sessionId = listeningId(host)
        coordinator.stop()
        sendTranscript(host, "hello world")
        advanceUntilIdle()

        coordinator.onInsertionResult(
            sessionId,
            InsertionResult.Failed(
                DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, host.clipboardCopies.size)
        val error = states(host).first { it is DictationState.Error } as DictationState.Error
        assertEquals("insert_target_ineligible", error.failure.code)
        assertTrue(states(host).none { it is DictationState.CopiedToClipboard })
    }
}
