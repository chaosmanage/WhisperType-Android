package com.whispertype.android.gemini

import com.whispertype.android.audio.AudioChunk
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * Entry point for a Gemini Live dictation session (Implementation Plan §12).
 *
 * One connection is created per utterance. After audio transmission the caller must
 * never retry; failures are reported as [GeminiEvent.Failed] events and the connection
 * closes itself.
 */
interface GeminiLiveClient {

    /**
     * Opens a WebSocket to Gemini Live, sends the setup and realtime-input configuration
     * messages, and returns a ready [GeminiLiveConnection].
     *
     * The returned connection is usable even if the handshake failed: the failure is
     * delivered as a [GeminiEvent.Failed] event instead of an exception.
     */
    suspend fun connect(sessionId: String, apiKey: String, config: GeminiSessionConfig): GeminiLiveConnection
}

/**
 * A live session to Gemini. All methods are safe to call from any thread and must not
 * throw; outbound messages are serialized in call order.
 */
interface GeminiLiveConnection {

    /**
     * Streams one 20 ms PCM16 audio chunk. Chunks sent after [sendActivityEnd] are dropped.
     */
    fun sendAudio(chunk: AudioChunk)

    /**
     * Sends the activity-end boundary. Exactly one message is put on the wire; a second
     * call is ignored silently.
     */
    fun sendActivityEnd()

    /**
     * Cancels the session. No further events are emitted and nothing is sent.
     */
    fun close()

    /**
     * Session events. The flow does not complete; subscribers terminate on
     * [GeminiEvent.SessionEnd] or [GeminiEvent.Failed].
     */
    val events: Flow<GeminiEvent>
}

/** Events emitted by a [GeminiLiveConnection]. */
sealed interface GeminiEvent {

    /**
     * Partial transcript deltas. [raw] and [cleaned] are the deltas for this message only;
     * the caller appends them to build the running candidate.
     */
    data class TranscriptUpdate(val raw: String?, val cleaned: String?) : GeminiEvent

    /**
     * The model finished a turn. [raw] and [cleaned] are the full candidates assembled so far.
     */
    data class TurnComplete(val raw: String?, val cleaned: String?) : GeminiEvent

    /**
     * The session finished after the activity-end boundary. [finalRaw] and [finalCleaned]
     * are the final assembled candidates; the caller reads candidates from this event.
     */
    data class SessionEnd(val finalRaw: String?, val finalCleaned: String?) : GeminiEvent

    /**
     * Unrecoverable failure of the session. Exactly one [Failed] is emitted before the
     * connection closes itself. [GeminiFailure.code] is a stable diagnostic token.
     */
    data class Failed(val failure: GeminiFailure) : GeminiEvent
}

/** Typed, non-sensitive failure description. [message] never contains keys or URLs. */
data class GeminiFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

/** Non-sensitive log sink so tests can capture every string the client emits. */
fun interface GeminiLogSink {
    fun log(tag: String, message: String)

    companion object {
        /** Default sink writing to Android's logcat under the given tag. */
        fun android(): GeminiLogSink = GeminiLogSink { tag, message -> android.util.Log.d(tag, message) }
    }
}

/**
 * OkHttp-based [GeminiLiveClient].
 *
 * @param scope scope used for the post-activity-end timeout.
 * @param logSink receives only non-sensitive log lines; never keys, transcripts, or URLs.
 * @param endpointBaseUrl injectable for contract tests; defaults to the production endpoint.
 */
class DefaultGeminiLiveClient(
    private val scope: CoroutineScope,
    private val logSink: GeminiLogSink = GeminiLogSink.android(),
    endpointBaseUrl: String = DEFAULT_ENDPOINT_BASE_URL,
) : GeminiLiveClient {

    private val baseUrl: String = endpointBaseUrl.trimEnd('/')

    override suspend fun connect(sessionId: String, apiKey: String, config: GeminiSessionConfig): GeminiLiveConnection {
        val dispatcher: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wt-gemini-$sessionId")
        }
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectionTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.connectionTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val connection = GeminiLiveWebSocket(
            sessionId = sessionId,
            url = buildLiveUrl(apiKey, config.modelId),
            config = config,
            okHttpClient = httpClient,
            dispatcher = dispatcher,
            scope = scope,
            logSink = logSink,
        )
        connection.open()
        return connection
    }

    private fun buildLiveUrl(apiKey: String, modelId: String): String {
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        return "$baseUrl/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=$encodedKey&model=$modelId"
    }

    companion object {
        /** Production WebSocket endpoint base URL. */
        const val DEFAULT_ENDPOINT_BASE_URL: String = "wss://generativelanguage.googleapis.com"
    }
}
