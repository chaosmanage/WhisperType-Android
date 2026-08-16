package com.whispertype.android.platform.groq

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 0.7.0: thin OkHttp WebSocket client for the Groq polish stream.
 *
 * The client only moves bytes and text: binary messages carry the encoded
 * frame-of-reference pickle (see AudioPickleCodec), text messages carry the
 * peer's streamed transcript frames. No transcript content is ever logged or
 * retained here.
 *
 * [GroqSocketListener] callbacks fire on the OkHttp I/O thread; the provider
 * posts them onto its own dispatcher.
 *
 * Sends issued before the socket opens are buffered (bounded by the max
 * in-flight payloads) and flushed in order when [onOpen] fires, so a provider
 * can write payloads immediately after [connect] without racing the upgrade.
 */
class GroqWebSocketClient(
    private val apiKey: String,
    private val url: String,
    private val okHttpClient: OkHttpClient,
    private val maxPendingPayloads: Int = MAX_PENDING_PAYLOADS,
) {

    private var socket: WebSocket? = null
    private var socketOpen = false
    private val pendingBinary = ArrayDeque<ByteString>()
    private var closed = false

    fun connect(listener: GroqSocketListener): Boolean {
        if (socket != null) return false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpen = true
                flushPending(webSocket)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinary(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                listener.onClosed()
            }
        })
        return true
    }

    /** Sends one binary payload, buffering it until the socket opens. False
     *  only when the socket is closed or the pending buffer is full. */
    fun sendBinary(payload: ByteArray): Boolean {
        if (closed) return false
        val bytes = ByteString.of(*payload)
        synchronized(pendingBinary) {
            if (socketOpen) {
                socketOpen = socket?.send(bytes) ?: false
                return socketOpen
            }
            if (pendingBinary.size >= maxPendingPayloads) return false
            pendingBinary.addLast(bytes)
            return true
        }
    }

    fun sendText(text: String): Boolean = socket?.send(text) ?: false

    fun close() {
        if (closed) return
        closed = true
        socket?.close(1000, null)
        socket = null
        socketOpen = false
        pendingBinary.clear()
    }

    private fun flushPending(webSocket: WebSocket) {
        synchronized(pendingBinary) {
            while (pendingBinary.isNotEmpty()) {
                if (!webSocket.send(pendingBinary.removeFirst())) break
            }
        }
    }

    private companion object {
        /** The provider streams one payload per drive, so a tiny buffer suffices. */
        const val MAX_PENDING_PAYLOADS = 4
    }
}

/** 0.7.0: peer events from [GroqWebSocketClient]. */
interface GroqSocketListener {
    fun onOpen()
    fun onBinary(bytes: ByteArray)
    fun onText(text: String)
    fun onClosing()
    fun onFailure(t: Throwable)
    fun onClosed()
}