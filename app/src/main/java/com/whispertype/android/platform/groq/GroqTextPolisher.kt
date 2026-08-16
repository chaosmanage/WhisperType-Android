package com.whispertype.android.platform.groq

import com.whispertype.android.core.contracts.TextPolishContract
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.PolishOutcome
import com.whispertype.android.core.model.TranscriptionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 0.7.0: [TextPolishContract] backed by the Groq chat-completions REST
 * endpoint (verified live with a real key, 2026-08-16). One attempt sends the
 * settled raw ASR text with the [PolishPrompts] prompt and delivers the
 * model's reply through [onTranscript]. [onOutcome] fires exactly once:
 *
 *  - HTTP 200 with a non-blank choice: SUCCESS.
 *  - HTTP 429: RATE_LIMITED. HTTP 5xx: SERVER_ERROR.
 *  - blank/absent content: EMPTY_RESPONSE.
 *  - transport failure: NETWORK_ERROR. Anything else: OTHER.
 *  - a drive while another is active: CANCELLED (new caller).
 *
 * Typed outcomes and integer usage only ([onUsage]) — the polished text is
 * delivered via the sink and never logged. Attempts are first-wins;
 * [cancelAttempt] aborts the in-flight call without reporting.
 */
class GroqTextPolisher(
    private val apiKey: String,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    override val languageMode: LanguageMode,
    override val style: TranscriptionStyle,
    private val model: String = GroqEndpoints.CHAT_MODEL,
    /**
     * Used for a single retry when [model] returns 429. The free tier's binding
     * constraint is tokens-per-minute *per model* (measured: 6,000 TPM on
     * `llama-3.1-8b-instant`), so the fallback has its own budget and rescues a
     * dictation whose primary bucket is momentarily drained. Null disables it.
     */
    private val fallbackModel: String? = GroqEndpoints.CHAT_MODEL_FALLBACK,
    private val url: String = GroqEndpoints.CHAT_COMPLETIONS_URL,
    private val nowNanos: () -> Long = { System.nanoTime() },
) : TextPolishContract {

    override val maxAttempts: Int = if (fallbackModel == null) 1 else 2

    override var onTranscript: ((String) -> Unit)? = null
    override var onUsage: ((Long, Long) -> Unit)? = null

    private var attemptJob: Job? = null
    private var inFlight: Call? = null
    private var onOutcome: ((PolishOutcome, Long) -> Unit)? = null
    private var cancelled = false
    private var warmUpStarted = false

    override fun driveAttempt(
        text: String,
        onOutcome: (PolishOutcome, Long) -> Unit,
        onRequestStarted: () -> Unit,
    ) {
        if (attemptJob?.isActive == true) {
            onOutcome(PolishOutcome.CANCELLED, 0L)
            return
        }
        cancelled = false
        this.onOutcome = onOutcome
        attemptJob = scope.launch {
            val startedAt = nowNanos()
            var outcome = issue(text, model, onRequestStarted)
            // One rate-limit rescue on the fallback model's separate budget.
            if (outcome == PolishOutcome.RATE_LIMITED && fallbackModel != null && !cancelled) {
                outcome = issue(text, fallbackModel) { }
            }
            if (!cancelled) {
                report(outcome, (nowNanos() - startedAt) / NANOS_PER_MILLISECOND)
            }
        }
    }

    private suspend fun issue(
        text: String,
        modelId: String,
        onRequestStarted: () -> Unit,
    ): PolishOutcome {
        val call = okHttpClient.newCall(buildRequest(text, modelId))
        inFlight = call
        onRequestStarted()
        val outcome = awaitOutcome(call)
        inFlight = null
        return outcome
    }

    private fun buildRequest(text: String, modelId: String): Request {
        val messages = PolishPrompts.buildMessages(text, languageMode, style)
        val body = buildJsonObject {
            put("model", modelId)
            // 0.8.0: deterministic. Any sampling makes the level calibration
            // (especially "do not restructure" at MEDIUM) unreproducible.
            put("temperature", 0)
            put("max_tokens", 1024)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        })
                    }
                },
            )
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun awaitOutcome(call: Call): PolishOutcome {
        val response = try {
            withContext(Dispatchers.IO) { call.execute() }
        } catch (e: IOException) {
            return PolishOutcome.NETWORK_ERROR
        }
        val bodyText = runCatching { response.body.string() }.getOrNull()
        response.close()
        if (!response.isSuccessful) {
            return when (response.code) {
                429 -> PolishOutcome.RATE_LIMITED
                in 500..599 -> PolishOutcome.SERVER_ERROR
                else -> PolishOutcome.OTHER
            }
        }
        val parsed = runCatching { Json.parseToJsonElement(bodyText.orEmpty()).jsonObject }.getOrNull()
        val content = parsed?.get("choices")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
        val usage = parsed?.get("usage")?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.long
        val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.long
        if (promptTokens != null || totalTokens != null) {
            onUsage?.invoke(promptTokens ?: 0L, totalTokens ?: 0L)
        }
        val cleaned = content?.let(::unwrapModelReply)
        return if (cleaned?.isNotBlank() == true) {
            onTranscript?.invoke(cleaned)
            PolishOutcome.SUCCESS
        } else {
            PolishOutcome.EMPTY_RESPONSE
        }
    }

    /**
     * Strips a wrapper the model added around its own answer — a leading
     * "Here is …:" line, or surrounding quotes. This unwraps the *model's*
     * packaging; it never edits the transcript itself (that is the model's job
     * per [PolishPrompts], and over-editing is caught by `PolishGuard`).
     */
    private fun unwrapModelReply(reply: String): String {
        var text = reply.trim()
        val firstBreak = text.indexOf('\n')
        if (firstBreak > 0) {
            val head = text.take(firstBreak).trim()
            if (head.endsWith(":") && head.length <= PREAMBLE_MAX_LENGTH) {
                text = text.drop(firstBreak + 1).trim()
            }
        }
        val quoted = (text.startsWith("\"") && text.endsWith("\"")) ||
            (text.startsWith("'") && text.endsWith("'"))
        if (quoted && text.length >= 2) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun report(outcome: PolishOutcome, elapsedMillis: Long) {
        val callback = this.onOutcome
        if (callback != null) {
            this.onOutcome = null
            callback(outcome, elapsedMillis)
        }
    }

    /**
     * Opens the TLS/HTTP2 connection to the Groq host ahead of the real call by
     * issuing a tiny unauthenticated request and discarding the result. Saves
     * the handshake (200-400 ms on mobile) from the settlement path. Failures
     * are ignored — this is purely opportunistic.
     */
    override fun warmUp() {
        if (warmUpStarted) return
        warmUpStarted = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val probe = Request.Builder().url(url).head().build()
                    okHttpClient.newCall(probe).execute().close()
                }
            }
        }
    }

    override fun cancelAttempt() {
        cancelled = true
        inFlight?.cancel()
        inFlight = null
        attemptJob?.cancel()
        attemptJob = null
    }

    override fun close() {
        cancelAttempt()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PREAMBLE_MAX_LENGTH = 60
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}