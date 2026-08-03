package com.whispertype.android.gemini

import com.whispertype.android.audio.AudioChunk
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp-backed [GeminiLiveConnection] that implements the session state machine.
 *
 * All WebSocket callbacks and all outbound operations are marshalled onto a single-thread
 * [CoroutineDispatcher] so state transitions are serialized. Events are emitted to a
 * [MutableSharedFlow] with a replay buffer so the terminal event survives a slow subscriber
 * (in particular, a [GeminiEvent.Failed] emitted before the caller starts collecting).
 *
 * There is no retry logic: any failure after the socket opens produces exactly one
 * [GeminiEvent.Failed] and a close. Per Implementation Plan §12 the caller must never retry
 * after audio transmission, so the client never reconnects or resends.
 */
internal class GeminiLiveWebSocket(
    private val sessionId: String,
    private val url: String,
    private val config: GeminiSessionConfig,
    private val okHttpClient: OkHttpClient,
    private val dispatcher: ExecutorService,
    private val scope: CoroutineScope,
    private val logSink: GeminiLogSink,
) : GeminiLiveConnection {

    private val eventStream = MutableSharedFlow<GeminiEvent>(replay = 1, extraBufferCapacity = 64)
    override val events: Flow<GeminiEvent> = eventStream

    private val assembler = GeminiTranscriptAssembler()
    private val opened = CompletableDeferred<Unit>()
    private val endTurnDeferred = CompletableDeferred<Unit>()

    private var webSocket: WebSocket? = null
    private var state = State.NEW
    private var setupCompleteReceived = false
    private var activityEndSent = false
    private var failureEmitted = false
    private var goAwayReceived = false
    private var closedByCaller = false
    private var activityEndTimeoutJob: Job? = null

    /**
     * Creates the socket and suspends until setup and configuration have been sent, or the
     * handshake failed. A failed handshake is delivered as [GeminiEvent.Failed], not thrown.
     */
    suspend fun open() {
        val socket = try {
            okHttpClient.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (_: Throwable) {
            failSynchronously(GeminiErrorMapper.mapConnectFailure(null, null))
            return
        }
        webSocket = socket
        try {
            opened.await()
        } catch (cancelled: CancellationException) {
            cancelSynchronously()
            throw cancelled
        }
    }

    override fun sendAudio(chunk: AudioChunk) {
        dispatch {
            if (state != State.OPEN || activityEndSent) return@dispatch
            val socket = webSocket ?: return@dispatch
            val message = GeminiLiveProtocol.buildAudioMessage(chunk.pcm16Bytes, config.sampleRateHz)
            if (!sendSafely(socket, message)) {
                logSink.log(TAG, "session $sessionId audio send failed seq=${chunk.sequenceNumber}")
            }
        }
    }

    override fun sendActivityEnd() {
        dispatch {
            if (activityEndSent || closedByCaller) return@dispatch
            if (state != State.OPEN && state != State.CLOSED) return@dispatch
            activityEndSent = true
            state = State.END_SENT
            val socket = webSocket
            if (socket != null) {
                sendSafely(socket, GeminiLiveProtocol.buildActivityEndMessage())
            }
            logSink.log(TAG, "session $sessionId activity end requested")
            scheduleActivityEndTimeout()
        }
    }

    override fun close() {
        dispatch {
            if (terminal()) return@dispatch
            closedByCaller = true
            state = State.CLOSED
            activityEndTimeoutJob?.cancel()
            endTurnDeferred.complete(Unit)
            logSink.log(TAG, "session $sessionId closed by caller")
            closeSocketAndShutdown(1000, "closed by caller")
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = dispatch { handleOpen() }

        override fun onMessage(webSocket: WebSocket, text: String) = dispatch { handleMessage(text) }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch {
            logSink.log(TAG, "session $sessionId peer closing code=$code")
            if (terminal()) return@dispatch
            closeSocketSafely(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch { handleClosed() }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch {
            handleFailure(t, response)
        }
    }

    private fun handleOpen() {
        if (state != State.NEW) return
        state = State.OPEN
        val socket = webSocket ?: return
        val setupSent = sendSafely(socket, GeminiLiveProtocol.buildSetupMessage(config.modelId, config.languageMode))
        val configSent = sendSafely(socket, GeminiLiveProtocol.buildRealtimeConfigMessage(config.sampleRateHz))
        if (setupSent && configSent) {
            logSink.log(TAG, "session $sessionId setup and config sent")
        } else {
            logSink.log(TAG, "session $sessionId setup or config send failed")
        }
        opened.complete(Unit)
    }

    private fun handleMessage(text: String) {
        if (terminal()) return
        val message = try {
            GeminiLiveProtocol.parseServerMessage(text)
        } catch (_: Exception) {
            emitFailure(GeminiErrorMapper.protocolError())
            return
        }
        when (message) {
            is ServerMessage.SetupComplete -> {
                if (!setupCompleteReceived) {
                    setupCompleteReceived = true
                    logSink.log(TAG, "session $sessionId setup complete")
                }
            }
            is ServerMessage.ServerError -> emitFailure(GeminiErrorMapper.mapServerError(message.httpStatusHint, message.serverCode))
            is ServerMessage.ServerContent -> handleServerContent(message)
            is ServerMessage.Interrupted -> {
                if (!setupCompleteReceived) {
                    emitFailure(GeminiErrorMapper.protocolError())
                } else {
                    handleInterrupted()
                }
            }
            is ServerMessage.GoAway -> {
                if (!setupCompleteReceived) {
                    emitFailure(GeminiErrorMapper.protocolError())
                } else {
                    goAwayReceived = true
                    logSink.log(TAG, "session $sessionId goAway received")
                    closeSocketSafely(1000, "go away")
                }
            }
            is ServerMessage.UsageMetadata, is ServerMessage.ToolCall, is ServerMessage.Unknown -> {
                if (!setupCompleteReceived) {
                    emitFailure(GeminiErrorMapper.protocolError())
                } else {
                    logSink.log(TAG, "session $sessionId unhandled message ${message::class.simpleName}")
                }
            }
        }
    }

    private fun handleServerContent(message: ServerMessage.ServerContent) {
        if (!setupCompleteReceived) {
            emitFailure(GeminiErrorMapper.protocolError())
            return
        }
        if (message.interrupted) {
            handleInterrupted()
            return
        }
        if (message.rawDelta != null || message.cleanedDelta != null) {
            assembler.append(message.rawDelta, message.cleanedDelta)
            emitEvent(GeminiEvent.TranscriptUpdate(message.rawDelta, message.cleanedDelta))
        }
        if (message.turnComplete) {
            handleTurnComplete()
        }
    }

    private fun handleTurnComplete() {
        when (state) {
            State.END_SENT -> {
                val candidates = assembler.completeTurn()
                state = State.END_COMPLETE
                activityEndTimeoutJob?.cancel()
                endTurnDeferred.complete(Unit)
                emitEvent(GeminiEvent.TurnComplete(candidates.raw, candidates.cleaned))
                emitEvent(GeminiEvent.SessionEnd(candidates.raw, candidates.cleaned))
                logSink.log(TAG, "session $sessionId session end emitted")
                closeSocketAndShutdown(1000, "session complete")
            }
            State.OPEN -> {
                val candidates = assembler.completeTurn()
                emitEvent(GeminiEvent.TurnComplete(candidates.raw, candidates.cleaned))
                logSink.log(TAG, "session $sessionId turn complete")
            }
            else -> Unit
        }
    }

    private fun handleInterrupted() {
        if (terminal()) return
        logSink.log(TAG, "session $sessionId interrupted")
        emitFailure(GeminiErrorMapper.interrupted())
    }

    private fun handleClosed() {
        if (terminal()) return
        if (goAwayReceived) {
            state = State.CLOSED
            logSink.log(TAG, "session $sessionId closed after goAway")
            shutdownDispatcherSafely()
            return
        }
        logSink.log(TAG, "session $sessionId socket closed unexpectedly")
        emitFailure(GeminiErrorMapper.mapSocketFailure(null, null))
    }

    private fun handleFailure(throwable: Throwable, response: Response?) {
        if (terminal()) return
        val failure = if (state == State.NEW) {
            GeminiErrorMapper.mapConnectFailure(throwable, response?.code)
        } else {
            GeminiErrorMapper.mapSocketFailure(throwable, response?.code)
        }
        logSink.log(TAG, "session $sessionId socket failure code=${failure.code}")
        emitFailure(failure)
    }

    private fun emitFailure(failure: GeminiFailure) {
        if (failureEmitted || terminal()) return
        failureEmitted = true
        state = State.FAILED
        activityEndTimeoutJob?.cancel()
        endTurnDeferred.complete(Unit)
        opened.complete(Unit)
        logSink.log(TAG, "session $sessionId failed code=${failure.code} recoverable=${failure.recoverable}")
        emitEvent(GeminiEvent.Failed(failure))
        closeSocketAndShutdown(1000, "failure")
    }

    private fun scheduleActivityEndTimeout() {
        activityEndTimeoutJob?.cancel()
        activityEndTimeoutJob = scope.launch(dispatcher.asCoroutineDispatcher()) {
            try {
                withTimeout(config.activityEndTimeoutMillis) { endTurnDeferred.await() }
            } catch (_: TimeoutCancellationException) {
                logSink.log(TAG, "session $sessionId activity end timeout")
                emitFailure(GeminiErrorMapper.timeout())
            }
        }
    }

    private fun failSynchronously(failure: GeminiFailure) {
        state = State.FAILED
        failureEmitted = true
        logSink.log(TAG, "session $sessionId failed synchronously code=${failure.code}")
        emitEvent(GeminiEvent.Failed(failure))
        opened.complete(Unit)
    }

    private fun cancelSynchronously() {
        state = State.CLOSED
        try {
            webSocket?.cancel()
        } catch (_: Throwable) {
        }
        opened.complete(Unit)
        shutdownDispatcherSafely()
    }

    private fun sendSafely(socket: WebSocket, text: String): Boolean = try {
        socket.send(text)
    } catch (_: Throwable) {
        false
    }

    private fun emitEvent(event: GeminiEvent) {
        eventStream.tryEmit(event)
    }

    private fun closeSocketSafely(code: Int, reason: String) {
        try {
            webSocket?.close(code, reason)
        } catch (_: Throwable) {
        }
    }

    private fun closeSocketAndShutdown(code: Int, reason: String) {
        closeSocketSafely(code, reason)
        shutdownDispatcherSafely()
    }

    private fun shutdownDispatcherSafely() {
        try {
            dispatcher.shutdown()
        } catch (_: Throwable) {
        }
    }

    private fun terminal(): Boolean =
        state == State.CLOSED || state == State.FAILED || state == State.END_COMPLETE

    private fun dispatch(block: () -> Unit) {
        try {
            dispatcher.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    private enum class State {
        NEW,
        OPEN,
        END_SENT,
        END_COMPLETE,
        FAILED,
        CLOSED,
    }

    private companion object {
        const val TAG: String = "WT-Gemini"
    }
}
