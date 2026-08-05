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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
 * not socket-open ([awaitReady]). Setup is sent on open, audio is base64 PCM16
 * in `realtimeInput.audio` frames, and the turn ends with one
 * `clientContent.turnComplete` boundary ([endActivity]).
 *
 * Threading: OkHttp delivers listener callbacks on its dispatcher; this class
 * never blocks the caller's coroutine beyond the ready/event channels, and all
 * `send` calls are thread-safe OkHttp queue operations.
 */
class OkHttpGeminiLiveSession(
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val config: GeminiSessionConfig,
    private val metrics: MutableSessionMetrics? = null,
) : GeminiLiveSession {

    private val _events = Channel<GeminiEvent>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    /** True once the server delivered at least one inputTranscription this session.
     *  Echo (outputTranscription) candidates are only trusted after that: when the
     *  ASR is not transcribing the user's speech, the model's "echo" is an
     *  acknowledgment/greeting, never dictation. */
    private var sawInputTranscription = false

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

    override suspend fun sendAudio(chunk: AudioChunk): SendResult {
        if (!ready.isCompleted) return SendResult.Rejected(REASON_NOT_READY)
        val ws = socket ?: return SendResult.Rejected(REASON_CLOSED)
        val dataBase64 = Base64.getEncoder().encodeToString(chunk.pcm16Bytes)
        val sent = ws.send(GeminiLiveWire.buildAudioChunk(dataBase64, chunk.sampleRateHz))
        if (sent) {
            metrics?.recordWebSocketQueue(ws.queueSize().toInt())
        }
        return if (sent) SendResult.Accepted else SendResult.Rejected(REASON_CLOSED)
    }

    override suspend fun endActivity() {
        socket?.send(GeminiLiveWire.buildTurnComplete())
    }

    override suspend fun sendTextTurn(text: String) {
        socket?.send(GeminiLiveWire.buildTextTurn(text))
    }

    override fun events(): Flow<GeminiEvent> = _events.receiveAsFlow()

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            socket?.close(NORMAL_CLOSE_CODE, "session closed")
            socket = null
            ready.completeExceptionally(CancellationException("Session closed"))
            _events.close()
        }
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            GeminiLog.i(TAG, "onOpen code=${response.code} url=${redactUrl(wsUrl)}")
            metrics?.mark(MutableSessionMetrics.Event.SocketOpen)
            val setup = GeminiLiveWire.buildSetup(config)
            val sent = webSocket.send(setup)
            GeminiLog.i(TAG, "setupSent=$sent setup=$setup")
        }

        // The Gemini Live server sends every server->client message as a BINARY
        // frame (opcode 0x2), so OkHttp routes it to this overload rather than
        // the text one. Both decode to the same parser.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            GeminiLog.i(TAG, "onMessage ${text.take(200)}")
            when (val message = GeminiLiveWire.parseServerMessage(text)) {
                GeminiLiveWire.ServerMessage.SetupComplete -> {
                    metrics?.mark(MutableSessionMetrics.Event.SetupComplete)
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
        GeminiLog.i(
            TAG,
            "serverContent: inputTranscription=${message.inputTranscription?.take(80)} outputTranscription=${message.outputTranscription?.take(80)} textParts=${message.textParts.size} turnComplete=${message.turnComplete} interrupted=${message.interrupted}",
        )
        val candidates = ArrayList<ResultCandidate>()
        // Voice-to-text: the dictation source is inputTranscription (the user's
        // speech as recognized by the server's ASR). outputTranscription — the
        // transcription of the model's own audio reply — is only trusted as an
        // echo supplement AFTER inputTranscription has fired this session; when
        // the ASR is silent, the model's reply is an acknowledgment or greeting,
        // never dictation. modelTurn text is only a fallback for future
        // text-capable models.
        val inputTranscription = message.inputTranscription
        if (inputTranscription != null && inputTranscription.isNotEmpty()) {
            sawInputTranscription = true
            val m = metrics
            if (m != null) {
                m.inputTranscriptionCount += 1
                m.mark(MutableSessionMetrics.Event.FirstInputTranscript)
            }
            candidates.add(ResultCandidate(raw = inputTranscription, cleaned = null, language = config.language))
        } else if (sawInputTranscription && message.outputTranscription != null && message.outputTranscription.isNotEmpty()) {
            val m = metrics
            if (m != null) m.outputTranscriptionCount += 1
            candidates.add(ResultCandidate(raw = message.outputTranscription, cleaned = null, language = config.language))
        } else if (sawInputTranscription && message.textParts.isNotEmpty()) {
            message.textParts.forEach { part ->
                candidates.add(ResultCandidate(raw = part, cleaned = null, language = config.language))
            }
        }
        if (candidates.isNotEmpty()) {
            _events.trySend(GeminiEvent.TranscriptCandidates(candidates))
        }
        if (message.turnComplete) {
            val m = metrics
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
        const val FAIL_SETUP = "gemini_setup"
        const val FAIL_TRANSPORT = "gemini_transport"
    }
}
