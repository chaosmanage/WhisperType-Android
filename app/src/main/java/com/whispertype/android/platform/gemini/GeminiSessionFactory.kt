package com.whispertype.android.platform.gemini

import com.whispertype.android.core.model.MutableSessionMetrics
import java.time.Duration
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Constructs [OkHttpGeminiLiveSession]s against the Gemini Live WebSocket
 * endpoint. The API key is required per session; the URL is derived from the
 * key and the [GeminiSessionConfig] so callers never assemble endpoints.
 */
object GeminiSessionFactory {

    /** Default Live model used when the caller does not pin one. */
    const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

    fun create(
        apiKey: String,
        config: GeminiSessionConfig = GeminiSessionConfig(model = DEFAULT_MODEL),
        client: OkHttpClient = defaultClient(),
        metrics: MutableSessionMetrics? = null,
    ): OkHttpGeminiLiveSession {
        require(apiKey.isNotEmpty()) { "apiKey must not be empty" }
        return OkHttpGeminiLiveSession(client, buildWsUrl(apiKey, config), config, metrics)
    }

    /** WebSocket endpoint for the [GeminiSessionConfig.apiVersion] path segment. */
    fun buildWsUrl(apiKey: String, config: GeminiSessionConfig): String =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.${config.apiVersion}." +
            "GenerativeService.BidiGenerateContent?key=$apiKey"

    /** Long-lived client: no read timeout, periodic pings to keep the socket warm. */
    fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .pingInterval(Duration.ofSeconds(20))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
}
