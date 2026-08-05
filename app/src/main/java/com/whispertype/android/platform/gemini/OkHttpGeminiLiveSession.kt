package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.GeminiEvent
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
) : GeminiLiveSession {

    private val _events = Channel<GeminiEvent>(Channel.UNLIMITED)
    private val ready = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    private var socket: WebSocket? = client.newWebSocket(
        Request.Builder().url(wsUrl).build(),
        listener(),
    )

    override suspend fun awaitReady() {
        withTimeout(READY_TIMEOUT_MS) { ready.await() }
    }

    override suspend fun sendAudio(chunk: AudioChunk): SendResult {
        if (!ready.isCompleted) return SendResult.Rejected(REASON_NOT_READY)
        val ws = socket ?: return SendResult.Rejected(REASON_CLOSED)
        val dataBase64 = Base64.getEncoder().encodeToString(chunk.pcm16Bytes)
        val sent = ws.send(GeminiLiveWire.buildAudioChunk(dataBase64, chunk.sampleRateHz))
        return if (sent) SendResult.Accepted else SendResult.Rejected(REASON_CLOSED)
    }

    override suspend fun endActivity() {
        socket?.send(GeminiLiveWire.buildTurnComplete())
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
            webSocket.send(GeminiLiveWire.buildSetup(config))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = GeminiLiveWire.parseServerMessage(text)) {
                GeminiLiveWire.ServerMessage.SetupComplete -> {
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
            ready.completeExceptionally(t)
            if (!closed.get()) {
                _events.trySend(
                    GeminiEvent.Failed(failure(FAIL_TRANSPORT, t.message ?: "Connection failed", recoverable = true)),
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) {
                _events.trySend(GeminiEvent.SessionEnd)
            }
        }
    }

    private fun onServerContent(message: GeminiLiveWire.ServerMessage.ServerContent) {
        val candidates = ArrayList<ResultCandidate>()
        if (message.textParts.isNotEmpty()) {
            message.textParts.forEach { part ->
                candidates.add(ResultCandidate(raw = part, cleaned = null, language = config.language))
            }
        }
        val inputTranscription = message.inputTranscription
        if (candidates.isEmpty() && inputTranscription != null && inputTranscription.isNotEmpty()) {
            candidates.add(ResultCandidate(raw = inputTranscription, cleaned = null, language = config.language))
        }
        if (candidates.isNotEmpty()) {
            _events.trySend(GeminiEvent.TranscriptCandidates(candidates))
        }
        if (message.turnComplete) {
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

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
        const val NORMAL_CLOSE_CODE = 1000
        const val REASON_NOT_READY = "session_not_ready"
        const val REASON_CLOSED = "socket_closed"
        const val FAIL_SETUP = "gemini_setup"
        const val FAIL_TRANSPORT = "gemini_transport"
    }
}
