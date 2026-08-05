package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * The outbound state is one [AtomicReference], so concurrent callers (the
 * audio sender and the finalizer) cannot violate the state machine.
 */
class OkHttpGeminiLiveSession(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val config: GeminiSessionConfig,
    private val metrics: MutableSessionMetrics? = null,
) : GeminiLiveSession {

    private enum class State { Connecting, Ready, ActivityStarted, ActivityEnded, Closed }

    private val _events = Channel<GeminiEvent>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val state = AtomicReference(State.Connecting)

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

    override suspend fun startActivity(): SendResult {
        val current = state.get()
        return when (current) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> {
                if (state.compareAndSet(State.Ready, State.ActivityStarted)) {
                    val ws = socket
                    if (ws == null) {
                        state.set(State.Closed)
                        return SendResult.Rejected(REASON_CLOSED)
                    }
                    if (config.automaticActivityDetectionDisabled) {
                        val sent = ws.send(GeminiLiveWire.buildActivityStart())
                        if (!sent) {
                            state.set(State.Closed)
                            return SendResult.Rejected(REASON_CLOSED)
                        }
                        metrics?.mark(MutableSessionMetrics.Event.ActivityStartQueued)
                    }
                    SendResult.Accepted
                } else {
                    // Lost the race to another starter or a close.
                    sendResultFor(state.get())
                }
            }
            State.ActivityStarted -> SendResult.Accepted // duplicate start: no second wire message
            State.ActivityEnded -> SendResult.Rejected(REASON_ACTIVITY_ENDED)
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override suspend fun sendAudio(chunk: AudioChunk): SendResult = withContext(Dispatchers.IO) {
        when (state.get()) {
            State.Connecting -> return@withContext SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> return@withContext SendResult.Rejected(REASON_AUDIO_BEFORE_START)
            State.ActivityEnded -> return@withContext SendResult.Rejected(REASON_AUDIO_AFTER_END)
            State.Closed -> return@withContext SendResult.Rejected(REASON_CLOSED)
            State.ActivityStarted -> Unit
        }
        val ws = socket ?: return@withContext SendResult.Rejected(REASON_CLOSED)
        val dataBase64 = Base64.getEncoder().encodeToString(chunk.pcm16Bytes)
        val sent = ws.send(GeminiLiveWire.buildAudioChunk(dataBase64, chunk.sampleRateHz))
        if (sent) {
            metrics?.recordWebSocketQueue(ws.queueSize().toInt())
        }
        if (sent) SendResult.Accepted else SendResult.Rejected(REASON_CLOSED)
    }

    override suspend fun endActivity(): SendResult {
        val current = state.get()
        return when (current) {
            State.Connecting -> SendResult.Rejected(REASON_NOT_READY)
            State.Ready -> SendResult.Rejected(REASON_ACTIVITY_NOT_STARTED)
            State.ActivityStarted -> {
                if (state.compareAndSet(State.ActivityStarted, State.ActivityEnded)) {
                    val ws = socket
                    if (ws == null) {
                        state.set(State.Closed)
                        return SendResult.Rejected(REASON_CLOSED)
                    }
                    val message =
                        if (config.automaticActivityDetectionDisabled) {
                            GeminiLiveWire.buildActivityEnd()
                        } else {
                            GeminiLiveWire.buildAudioStreamEnd()
                        }
                    val sent = ws.send(message)
                    if (!sent) {
                        state.set(State.Closed)
                        return SendResult.Rejected(REASON_CLOSED)
                    }
                    metrics?.mark(MutableSessionMetrics.Event.ActivityEndQueued)
                    SendResult.Accepted
                } else {
                    sendResultFor(state.get())
                }
            }
            State.ActivityEnded -> SendResult.Accepted // duplicate end: no second wire message
            State.Closed -> SendResult.Rejected(REASON_CLOSED)
        }
    }

    override fun events(): Flow<GeminiEvent> = _events.receiveAsFlow()

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            state.set(State.Closed)
            socket?.close(NORMAL_CLOSE_CODE, "session closed")
            socket = null
            ready.completeExceptionally(CancellationException("Session closed"))
            _events.close()
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
            val sent = webSocket.send(GeminiLiveWire.buildSetup(config))
            GeminiLog.i(TAG, "setupSent=$sent")
        }

        // The Gemini Live server sends every server->client message as a BINARY
        // frame (opcode 0x2), so OkHttp routes it to this overload rather than
        // the text one. Both decode to the same parser.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = GeminiLiveWire.parseServerMessage(text)) {
                GeminiLiveWire.ServerMessage.SetupComplete -> {
                    metrics?.mark(MutableSessionMetrics.Event.SetupComplete)
                    state.compareAndSet(State.Connecting, State.Ready)
                    ready.complete(Unit)
                    _events.trySend(GeminiEvent.Ready)
                }

                is GeminiLiveWire.ServerMessage.SetupError -> {
                    val failure = failure(FAIL_SETUP, message.message, recoverable = true)
                    ready.completeExceptionally(GeminiLiveException(failure))
                    _events.trySend(GeminiEvent.Failed(failure))
                }

                is GeminiLiveWire.ServerMessage.ServerContent -> onServerContent(message)

                GeminiLiveWire.ServerMessage.GoAway -> _events.trySend(GeminiEvent.SessionEnd)

                is GeminiLiveWire.ServerMessage.Unknown -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            GeminiLog.w(TAG, "onFailure ${t.message} httpCode=${response?.code}")
            ready.completeExceptionally(t)
            if (!closed.get()) {
                _events.trySend(
                    GeminiEvent.Failed(failure(FAIL_TRANSPORT, t.message ?: "Connection failed", recoverable = true)),
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosing code=$code reason=$reason at=${System.currentTimeMillis()}")
            if (!closed.get() && !ready.isCompleted) {
                ready.completeExceptionally(
                    GeminiLiveException(failure(FAIL_SETUP, reason.ifBlank { "Connection closed (code $code) before setup." }, recoverable = true)),
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            GeminiLog.i(TAG, "onClosed code=$code reason=$reason")
            if (!closed.get()) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(
                        GeminiLiveException(failure(FAIL_SETUP, reason.ifBlank { "Connection closed (code $code) before setup." }, recoverable = true)),
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
            "serverContent: inputTx=${message.inputTranscription?.length ?: 0} outputTx=${message.outputTranscription?.length ?: 0} textParts=${message.textParts.size} turnComplete=${message.turnComplete}",
        )
        // The dictation source is inputTranscription (the user's speech as
        // recognized by the server's ASR). outputTranscription (the model's own
        // audio reply) and modelTurn text are never user dictation: they can
        // carry greetings, acknowledgments, or instruction echoes, so they are
        // never selected as candidates. Only their presence is counted for
        // diagnostics; their text is never logged.
        val m = metrics
        if (message.outputTranscription != null && message.outputTranscription.isNotEmpty() && m != null) {
            m.outputTranscriptionCount += 1
        }
        val inputTranscription = message.inputTranscription
        if (inputTranscription != null && inputTranscription.isNotEmpty()) {
            if (m != null) {
                m.inputTranscriptionCount += 1
                m.mark(MutableSessionMetrics.Event.FirstInputTranscript)
            }
            _events.trySend(
                GeminiEvent.TranscriptCandidates(
                    listOf(ResultCandidate(raw = inputTranscription, cleaned = null, language = config.language)),
                ),
            )
        }
        if (message.turnComplete) {
            if (m != null) {
                m.turnCompleteArrived = true
                m.mark(MutableSessionMetrics.Event.TurnComplete)
            }
            _events.trySend(GeminiEvent.TurnComplete)
        }
    }

    private fun failure(code: String, detail: String, recoverable: Boolean): DictationFailure =
        DictationFailure(
            code = code,
            message = detail,
            recoverable = recoverable,
            retryAllowed = recoverable,
        )

    private fun redactUrl(url: String): String {
        // Strip the api key query parameter; never log authenticated URLs (§FR-6).
        return url.replace(Regex("key=[^&]*"), "key=<redacted>")
    }

    private companion object {
        const val TAG = "OkHttpGeminiLiveSession"
        const val READY_TIMEOUT_MS = 15_000L
        const val NORMAL_CLOSE_CODE = 1000
        const val REASON_NOT_READY = "session_not_ready"
        const val REASON_CLOSED = "socket_closed"
        const val REASON_AUDIO_BEFORE_START = "audio_before_activity_start"
        const val REASON_AUDIO_AFTER_END = "audio_after_activity_end"
        const val REASON_ACTIVITY_NOT_STARTED = "activity_not_started"
        const val REASON_ACTIVITY_ENDED = "activity_ended"
        const val FAIL_SETUP = "gemini_setup"
        const val FAIL_TRANSPORT = "gemini_transport"
    }
}
