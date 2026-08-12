package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.privacy.LogRedactor
import com.whispertype.android.core.transcript.TranscriptAccumulator
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Transport-level failure carrying a typed, non-sensitive [DictationFailure]. */
class GeminiLiveException(val failure: DictationFailure) : Exception(failure.message)

/**
 * [GeminiLiveSession] over an OkHttp [WebSocket] to the Gemini Live
 * `BidiGenerateContent` endpoint. Readiness is the server's `setupComplete`,
 * not socket-open ([awaitReady]). Setup is sent on open; audio is base64 PCM16
 * in `realtimeInput.audio` frames; the push-to-talk activity is delimited by an
 * explicit `realtimeInput.activityStart` / `activityEnd` pair (manual activity
 * detection, the production design), or `audioStreamEnd:true` under automatic
 * VAD.
 *
 * Session state machine (Release B4):
 *   Connecting -> Ready -> ActivityStarted -> ActivityEnded -> Closed
 *
 * Enforced rules:
 *  - [startActivity] before Ready is rejected; a duplicate start sends no wire
 *    message; start after end/close is rejected.
 *  - [sendAudio] before start or after end is rejected.
 *  - [endActivity] before start or after close is rejected; a duplicate end
 *    sends no wire message.
 *  - A failed `WebSocket.send` returns [SendResult.Rejected] immediately.
 *  - [close] is idempotent and takes the session to Closed from any state.
 *
 * Outbound state transitions and WebSocket sends share one lock, so setup,
 * activity boundaries, audio, and close cannot be reordered by concurrent
 * callers. The atomic state also lets OkHttp callbacks terminate the session.
 */
class OkHttpGeminiLiveSession(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val config: GeminiSessionConfig,
    private val metrics: MutableSessionMetrics? = null,
    private val sendTextFrame: (WebSocket, String) -> Boolean = { webSocket, text ->
        webSocket.send(text)
    },
) : GeminiLiveSession {

    private enum class State { Connecting, Ready, ActivityStarted, ActivityEnded, Closed }

    private val _events = Channel<GeminiEvent>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val state = AtomicReference(State.Connecting)
    private val outboundLock = Any()

    private var socket: WebSocket? = client.newWebSocket(
        Request.Builder().url(wsUrl).build(),
        listener(),
    )

    override suspend fun awaitReady() {
        try {
            withTimeout(READY_TIMEOUT_MS) { ready.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GeminiLiveException(
                failure(
                    FAIL_SETUP,
                    "Timed out waiting for the Gemini session to start.",
                    recoverable = true,
                ),
            )
        }
    }

    override suspend fun startActivity(): SendResult = synchronized(outboundLock) {
        when (state.get()) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> {
                val ws = socket
                if (ws == null) {
                    state.compareAndSet(State.Ready, State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                if (config.automaticActivityDetectionDisabled) {
                    if (!sendTextFrame(ws, GeminiLiveWire.buildActivityStart())) {
                        state.set(State.Closed)
                        return@synchronized SendResult.Rejected(REASON_CLOSED)
                    }
                }
                if (!state.compareAndSet(State.Ready, State.ActivityStarted)) {
                    return@synchronized sendResultFor(state.get())
                }
                if (config.automaticActivityDetectionDisabled) {
                    metrics?.mark(MutableSessionMetrics.Event.ActivityStartQueued)
                }
                SendResult.Accepted
            }
            State.ActivityStarted -> SendResult.Accepted // duplicate start: no second wire message
            State.ActivityEnded -> SendResult.Rejected(REASON_ACTIVITY_ENDED)
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override suspend fun sendAudio(chunk: AudioChunk): SendResult = withContext(Dispatchers.IO) {
        synchronized(outboundLock) {
            when (state.get()) {
                State.Connecting -> return@synchronized SendResult.Rejected(REASON_NOT_READY)
                State.Ready -> return@synchronized SendResult.Rejected(REASON_AUDIO_BEFORE_START)
                State.ActivityEnded -> return@synchronized SendResult.Rejected(REASON_AUDIO_AFTER_END)
                State.Closed -> return@synchronized SendResult.Rejected(REASON_CLOSED)
                State.ActivityStarted -> Unit
            }
            val ws = socket ?: return@synchronized SendResult.Rejected(REASON_CLOSED)
            val dataBase64 = Base64.getEncoder().encodeToString(chunk.pcm16Bytes)
            val sent = sendTextFrame(
                ws,
                GeminiLiveWire.buildAudioChunk(dataBase64, chunk.sampleRateHz),
            )
            if (!sent) {
                failTransport(ws, DETAIL_AUDIO_SEND)
                return@synchronized SendResult.Rejected(REASON_CLOSED)
            }
            metrics?.recordWebSocketQueue(ws.queueSize().toInt())
            SendResult.Accepted
        }
    }

    override suspend fun endActivity(): SendResult = synchronized(outboundLock) {
        when (state.get()) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> SendResult.Rejected(REASON_ACTIVITY_NOT_STARTED)
            State.ActivityStarted -> {
                val ws = socket
                if (ws == null) {
                    state.compareAndSet(State.ActivityStarted, State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                val message =
                    if (config.automaticActivityDetectionDisabled) {
                        GeminiLiveWire.buildActivityEnd()
                    } else {
                        GeminiLiveWire.buildAudioStreamEnd()
                    }
                if (!sendTextFrame(ws, message)) {
                    state.set(State.Closed)
                    return@synchronized SendResult.Rejected(REASON_CLOSED)
                }
                if (!state.compareAndSet(State.ActivityStarted, State.ActivityEnded)) {
                    return@synchronized sendResultFor(state.get())
                }
                metrics?.mark(MutableSessionMetrics.Event.ActivityEndQueued)
                SendResult.Accepted
            }
            State.ActivityEnded -> SendResult.Accepted // duplicate end: no second wire message
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override fun events(): Flow<GeminiEvent> = _events.receiveAsFlow()

    /**
     * Sends [text] as a manually delimited realtime activity and collects the
     * model's spoken reply via `outputTranscription` (delta-accumulated). Gemini
     * 3.1 ongoing text is never completed with `clientContent.turnComplete`.
     */
    override suspend fun requestEchoFor(text: String): String? {
        if (state.get() != State.Ready) return null
        if (startActivity() != SendResult.Accepted) return null
        val textQueued = synchronized(outboundLock) {
            if (state.get() != State.ActivityStarted) return@synchronized false
            val ws = socket ?: return@synchronized false
            if (!sendTextFrame(ws, GeminiLiveWire.buildRealtimeText(text))) {
                state.set(State.Closed)
                return@synchronized false
            }
            true
        }
        if (!textQueued) return null
        if (endActivity() != SendResult.Accepted) return null

        val accumulator = TranscriptAccumulator(appendDeltas = true)
        val terminal = withTimeoutOrNull(ECHO_TIMEOUT_MS) {
            events().firstOrNull { event ->
                when (event) {
                    is GeminiEvent.TranscriptCandidates -> {
                        if (event.source == GeminiEvent.TranscriptSource.ECHO) {
                            event.candidates.forEach { accumulator.accept(it.raw) }
                        }
                        false
                    }
                    GeminiEvent.TurnComplete,
                    GeminiEvent.Interrupted,
                    GeminiEvent.SessionEnd -> true
                    is GeminiEvent.Failed -> true
                    else -> false
                }
            }
        } ?: return null
        return if (terminal == GeminiEvent.TurnComplete) accumulator.settledText() else null
    }

    override suspend fun close() {
        synchronized(outboundLock) {
            if (closed.compareAndSet(false, true)) {
                state.set(State.Closed)
                socket?.close(NORMAL_CLOSE_CODE, "session closed")
                socket = null
                ready.completeExceptionally(CancellationException("Session closed"))
                _events.close()
            }
        }
    }

    private fun sendResultFor(s: State): SendResult = when (s) {
        State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
        State.Ready -> SendResult.Rejected(REASON_NOT_READY)
        State.ActivityStarted -> SendResult.Accepted
        State.ActivityEnded -> SendResult.Rejected(REASON_ACTIVITY_ENDED)
        State.Closed -> SendResult.Rejected(REASON_CLOSED)
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            GeminiLog.i(TAG, "onOpen code=${response.code} url=${redactUrl(wsUrl)}")
            metrics?.mark(MutableSessionMetrics.Event.SocketOpen)
            synchronized(outboundLock) {
                if (state.get() != State.Connecting) return@synchronized
                val sent = sendTextFrame(webSocket, GeminiLiveWire.buildSetup(config))
                GeminiLog.i(TAG, "setupSent=$sent")
                if (!sent) {
                    failTransport(webSocket, DETAIL_SETUP_SEND)
                }
            }
        }

        // The Gemini Live server sends every server->client message as a BINARY
        // frame (opcode 0x2), so OkHttp routes it to this overload rather than
        // the text one. Both decode to the same parser.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (state.get() == State.Closed) return
            when (val message = GeminiLiveWire.parseServerMessage(text)) {
                GeminiLiveWire.ServerMessage.SetupComplete -> {
                    if (state.compareAndSet(State.Connecting, State.Ready)) {
                        metrics?.mark(MutableSessionMetrics.Event.SetupComplete)
                        ready.complete(Unit)
                        _events.trySend(GeminiEvent.Ready)
                    }
                }

                is GeminiLiveWire.ServerMessage.SetupError -> {
                    val failure = failure(FAIL_SETUP, message.message, recoverable = true)
                    val transitioned = state.getAndSet(State.Closed) != State.Closed
                    ready.completeExceptionally(GeminiLiveException(failure))
                    if (transitioned && !closed.get()) {
                        _events.trySend(GeminiEvent.Failed(failure))
                    }
                }

                is GeminiLiveWire.ServerMessage.ServerContent -> onServerContent(message)

                is GeminiLiveWire.ServerMessage.GoAway ->
                    _events.trySend(GeminiEvent.GoAway(message.timeLeft))

                is GeminiLiveWire.ServerMessage.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            GeminiLog.w(TAG, "onFailure type=${t.javaClass.simpleName} httpCode=${response?.code}")
            val failure = failure(
                FAIL_TRANSPORT,
                sanitizeTransportDetail(t.message, DETAIL_CONNECTION_FAILED),
                recoverable = true,
            )
            val transitioned = state.getAndSet(State.Closed) != State.Closed
            ready.completeExceptionally(GeminiLiveException(failure))
            if (transitioned && !closed.get()) {
                _events.trySend(GeminiEvent.Failed(failure))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosing code=$code at=${System.currentTimeMillis()}")
            if (!closed.get() && !ready.isCompleted) {
                ready.completeExceptionally(
                    GeminiLiveException(
                        failure(
                            FAIL_SETUP,
                            sanitizeTransportDetail(
                                reason,
                                "Connection closed (code $code) before setup.",
                            ),
                            recoverable = true,
                        ),
                    ),
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosed code=$code")
            val transitioned = state.getAndSet(State.Closed) != State.Closed
            if (transitioned && !closed.get()) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        GeminiLiveException(
                            failure(
                                FAIL_SETUP,
                                sanitizeTransportDetail(
                                    reason,
                                    "Connection closed (code $code) before setup.",
                                ),
                                recoverable = true,
                            ),
                        ),
                    )
                }
                _events.trySend(GeminiEvent.SessionEnd)
            }
        }
    }

    private fun onServerContent(message: GeminiLiveWire.ServerMessage.ServerContent) {
        // Aggregate debug-only counters and flags; never log transcript content,
        // audio, or full server frames (Release A1/A2, D8).
        GeminiLog.i(
            TAG,
            "serverContent: inputTx=${message.inputTranscription?.length ?: 0} outputTx=${message.outputTranscription?.length ?: 0} textParts=${message.textParts.size} generationComplete=${message.generationComplete} interrupted=${message.interrupted} turnComplete=${message.turnComplete}",
        )
        // 0.4.1 echo architecture: the dictation source is outputTranscription —
        // the model's spoken reply, which the systemInstruction turns into a
        // verbatim, polished, Latin-script echo of the user's speech. The raw
        // inputTranscription (ASR, not instruction-influenced) is emitted as the
        // fast fallback. Never log transcript text.
        val m = metrics
        val inputTranscription = message.inputTranscription
        if (inputTranscription != null && inputTranscription.isNotEmpty()) {
            if (m != null) {
                m.inputTranscriptionCount += 1
                m.mark(MutableSessionMetrics.Event.FirstInputTranscript)
            }
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = inputTranscription, cleaned = null, language = config.language)),
                    source = GeminiEvent.TranscriptSource.INPUT,
                ),
            )
        }
        val outputTranscription = message.outputTranscription
        if (outputTranscription != null && outputTranscription.isNotEmpty()) {
            if (m != null) m.outputTranscriptionCount += 1
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = outputTranscription, cleaned = null, language = config.language)),
                    source = GeminiEvent.TranscriptSource.ECHO,
                ),
            )
        }
        if (message.generationComplete) {
            _events.trySend(GeminiEvent.GenerationComplete)
        }
        if (message.interrupted) {
            _events.trySend(GeminiEvent.Interrupted)
        }
        if (message.turnComplete) {
            if (m != null) {
                m.turnCompleteArrived = true
                m.mark(MutableSessionMetrics.Event.TurnComplete)
            }
            _events.trySend(GeminiEvent.TurnComplete)
        }
    }

    private fun failTransport(webSocket: WebSocket, detail: String) {
        val failure = failure(FAIL_TRANSPORT, detail, recoverable = true)
        val transitioned = state.getAndSet(State.Closed) != State.Closed
        ready.completeExceptionally(GeminiLiveException(failure))
        if (transitioned && !closed.get()) {
            _events.trySend(GeminiEvent.Failed(failure))
        }
        webSocket.cancel()
    }

    private fun failure(code: String, detail: String, recoverable: Boolean): DictationFailure =
        DictationFailure(
            code = code,
            message = detail,
            recoverable = recoverable,
            retryAllowed = recoverable,
        )

    private fun sanitizeTransportDetail(detail: String?, fallback: String): String =
        detail
            ?.takeIf { it.isNotBlank() }
            ?.let(LogRedactor::sanitize)
            ?: fallback

    private fun redactUrl(url: String): String = LogRedactor.sanitize(url)

    private companion object {
        const val TAG = "OkHttpGeminiLiveSession"
        const val READY_TIMEOUT_MS = 15_000L
        const val ECHO_TIMEOUT_MS = 15_000L
        const val NORMAL_CLOSE_CODE = 1000
        const val REASON_NOT_READY = "session_not_ready"
        const val REASON_CLOSED = "socket_closed"
        const val REASON_AUDIO_BEFORE_START = "audio_before_activity_start"
        const val REASON_AUDIO_AFTER_END = "audio_after_activity_end"
        const val REASON_ACTIVITY_NOT_STARTED = "activity_not_started"
        const val REASON_ACTIVITY_ENDED = "activity_ended"
        const val FAIL_SETUP = "gemini_setup"
        const val FAIL_TRANSPORT = "gemini_transport"
        const val DETAIL_SETUP_SEND = "The Gemini setup message could not be queued."
        const val DETAIL_AUDIO_SEND = "A Gemini audio frame could not be queued."
        const val DETAIL_CONNECTION_FAILED = "The Gemini connection failed."
    }
}
