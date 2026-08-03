package com.whispertype.android.gemini

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Maps transport and server failures to stable, non-sensitive [GeminiFailure] values.
 *
 * Messages never contain exception text, API keys, or URLs. AUTH_ERROR is detected from
 * HTTP status 400/401/403 on the WebSocket upgrade (present in the upgrade failure
 * message, e.g. "Expected HTTP 101 response but was '401 Unauthorized'") or from an
 * equivalent server error payload.
 */
object GeminiErrorMapper {

    const val CODE_NETWORK_LOST: String = "NETWORK_LOST"
    const val CODE_CONNECT_FAILED: String = "CONNECT_FAILED"
    const val CODE_PROTOCOL_ERROR: String = "PROTOCOL_ERROR"
    const val CODE_TIMEOUT: String = "TIMEOUT"
    const val CODE_AUTH_ERROR: String = "AUTH_ERROR"
    const val CODE_SERVER_ERROR: String = "SERVER_ERROR"
    const val CODE_INTERRUPTED: String = "INTERRUPTED"
    const val CODE_UNKNOWN: String = "UNKNOWN"

    /** Maps a failure that happened before the WebSocket was opened. */
    fun mapConnectFailure(error: Throwable?, httpStatus: Int?): GeminiFailure {
        val status = httpStatus ?: extractHttpStatus(error?.message)
        if (status in 400..403) return authError()
        if (status in 500..599) return serverError(status)
        if (isTimeout(error)) return timeout()
        return connectFailed()
    }

    /** Maps a failure that happened after the WebSocket was opened. */
    fun mapSocketFailure(error: Throwable?, httpStatus: Int?): GeminiFailure {
        val status = httpStatus ?: extractHttpStatus(error?.message)
        if (status in 400..403) return authError()
        if (isTimeout(error)) return timeout()
        return networkLost()
    }

    /** Maps a server error payload, e.g. {"error":{"code":400,"status":"INVALID_ARGUMENT"}}. */
    fun mapServerError(httpStatusHint: Int?, serverCode: String?): GeminiFailure {
        val status = httpStatusHint
        if (status in 400..403) return authError()
        if (status in 500..599) return serverError(status)
        when (serverCode?.uppercase()) {
            "UNAUTHENTICATED", "PERMISSION_DENIED" -> return authError()
        }
        return serverError(status)
    }

    fun connectFailed(): GeminiFailure = GeminiFailure(
        code = CODE_CONNECT_FAILED,
        message = "Could not connect to the speech service. Check your network connection and try again.",
        recoverable = true,
    )

    fun networkLost(): GeminiFailure = GeminiFailure(
        code = CODE_NETWORK_LOST,
        message = "The connection to the speech service was lost.",
        recoverable = true,
    )

    fun authError(): GeminiFailure = GeminiFailure(
        code = CODE_AUTH_ERROR,
        message = "The speech service rejected this session. Check the API key.",
        recoverable = false,
    )

    fun serverError(httpStatus: Int?): GeminiFailure = GeminiFailure(
        code = CODE_SERVER_ERROR,
        message = if (httpStatus == null) {
            "The speech service reported an error."
        } else {
            "The speech service reported an error ($httpStatus)."
        },
        recoverable = false,
    )

    fun protocolError(): GeminiFailure = GeminiFailure(
        code = CODE_PROTOCOL_ERROR,
        message = "The speech service sent an unexpected message.",
        recoverable = false,
    )

    fun timeout(): GeminiFailure = GeminiFailure(
        code = CODE_TIMEOUT,
        message = "The speech service did not respond in time.",
        recoverable = true,
    )

    fun interrupted(): GeminiFailure = GeminiFailure(
        code = CODE_INTERRUPTED,
        message = "Speech recognition was interrupted.",
        recoverable = true,
    )

    fun unknown(): GeminiFailure = GeminiFailure(
        code = CODE_UNKNOWN,
        message = "An unexpected speech service error occurred.",
        recoverable = false,
    )

    private val HTTP_VERSION_STATUS = Regex("HTTP/\\d\\.\\d\\s+(\\d{3})")
    private val UPGRADE_REFUSED_STATUS = Regex("Expected\\s+HTTP\\s+101\\s+response\\s+but\\s+was\\s+'?(\\d{3})")

    private fun extractHttpStatus(message: String?): Int? {
        if (message == null) return null
        HTTP_VERSION_STATUS.find(message)?.let { return it.groupValues[1].toIntOrNull() }
        UPGRADE_REFUSED_STATUS.find(message)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun isTimeout(error: Throwable?): Boolean {
        if (error is SocketTimeoutException || error is InterruptedIOException) return true
        val message = error?.message?.lowercase() ?: return false
        return message.contains("timeout") || message.contains("timed out")
    }
}
