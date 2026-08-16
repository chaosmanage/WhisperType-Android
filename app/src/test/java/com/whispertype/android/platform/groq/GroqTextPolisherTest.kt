package com.whispertype.android.platform.groq

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.PolishOutcome
import com.whispertype.android.core.model.TranscriptionStyle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * REST contract tests for [GroqTextPolisher] against MockWebServer. The mock
 * stands in for the Groq chat-completions endpoint; every dial asserts the
 * request shape (bearer auth, JSON body with the model + settled text) and the
 * typed outcome mapping. No live Groq key is ever required.
 */
class GroqTextPolisherTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @AfterTest
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.close()
    }

    private fun polisher(
        scope: CoroutineScope,
        style: TranscriptionStyle = TranscriptionStyle.MEDIUM,
        model: String = "test-model",
    ) = GroqTextPolisher(
        apiKey = "test-key",
        okHttpClient = client,
        scope = scope,
        languageMode = LanguageMode.ENGLISH,
        style = style,
        model = model,
        url = server.url("/openai/v1/chat/completions").toString(),
        nowNanos = { 0L },
    )

    private suspend fun drive(
        scope: CoroutineScope,
        polish: GroqTextPolisher,
        text: String = "the raw speech",
    ): Pair<PolishOutcome, Long> {
        val done = CompletableDeferred<Pair<PolishOutcome, Long>>()
        polish.driveAttempt(
            text = text,
            onOutcome = { outcome, elapsedMillis -> done.complete(outcome to elapsedMillis) },
        )
        val outcome = withTimeout(10_000) { done.await() }
        polish.close()
        return outcome
    }

    @Test
    fun `successful dial delivers the polished text and usage`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "choices": [
                        { "index": 0, "message": { "role": "assistant", "content": "Polished words." } }
                      ],
                      "usage": { "prompt_tokens": 42, "completion_tokens": 7, "total_tokens": 49 }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val polish = polisher(this)
        var delivered: String? = null
        var usage: Pair<Long, Long>? = null
        polish.onTranscript = { delivered = it }
        polish.onUsage = { prompt, total -> usage = prompt to total }

        val (outcome, elapsed) = drive(this, polish)

        assertEquals(PolishOutcome.SUCCESS, outcome)
        assertEquals("Polished words.", delivered)
        assertEquals(42L to 49L, usage)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertEquals("/openai/v1/chat/completions", recorded.url.encodedPath)
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("\"model\":\"test-model\""), "body must carry the model")
        assertTrue(body.contains("the raw speech"), "body must carry the settled raw text")
        assertEquals(0L, elapsed)
    }

    @Test
    fun `rate limit is rescued once on the fallback model`() = runBlocking {
        // Free-tier tokens-per-minute is per model (measured: 6,000 TPM on the
        // 8B model), so a 429 is retried once on the fallback model's own budget.
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"message":{"content":"Rescued text."}}]}""")
                .build(),
        )
        val polish = polisher(this)
        var delivered: String? = null
        polish.onTranscript = { delivered = it }

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.SUCCESS, outcome)
        assertEquals("Rescued text.", delivered)
        val first = server.takeRequest().body!!.utf8()
        val second = server.takeRequest().body!!.utf8()
        assertTrue(first.contains("test-model"), "first attempt uses the primary model")
        assertTrue(
            second.contains(GroqEndpoints.CHAT_MODEL_FALLBACK),
            "the retry must use the fallback model's separate budget",
        )
    }

    @Test
    fun `rate limit on both models reports RATE_LIMITED`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val polish = polisher(this)

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.RATE_LIMITED, outcome)
    }

    @Test
    fun `rate limit is not retried when no fallback is configured`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val polish = GroqTextPolisher(
            apiKey = "test-key",
            okHttpClient = client,
            scope = this,
            languageMode = LanguageMode.ENGLISH,
            style = TranscriptionStyle.MEDIUM,
            model = "test-model",
            fallbackModel = null,
            url = server.url("/openai/v1/chat/completions").toString(),
            nowNanos = { 0L },
        )

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.RATE_LIMITED, outcome)
        assertEquals(1, polish.maxAttempts)
    }

    @Test
    fun `server error maps to SERVER_ERROR`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        val polish = polisher(this)

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.SERVER_ERROR, outcome)
    }

    @Test
    fun `blank choice content maps to EMPTY_RESPONSE`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "choices": [
                        { "index": 0, "message": { "role": "assistant", "content": "   " } }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val polish = polisher(this)

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.EMPTY_RESPONSE, outcome)
    }

    @Test
    fun `transport failure maps to NETWORK_ERROR`() = runBlocking {
        val down = MockWebServer()
        down.start()
        val target = down.url("/openai/v1/chat/completions").toString()
        down.close()
        val polish = GroqTextPolisher(
            apiKey = "test-key",
            okHttpClient = client,
            scope = this,
            languageMode = LanguageMode.ENGLISH,
            style = TranscriptionStyle.MEDIUM,
            url = target,
            nowNanos = { 0L },
        )

        val (outcome, _) = drive(this, polish)

        assertEquals(PolishOutcome.NETWORK_ERROR, outcome)
    }

    @Test
    fun `drive while another attempt is active reports CANCELLED to the new caller`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"message":{"content":"one"}}]}""")
                .build(),
        )
        val polish = polisher(this)
        val firstDone = CompletableDeferred<PolishOutcome>()
        polish.driveAttempt(
            text = "first",
            onOutcome = { outcome, _ -> firstDone.complete(outcome) },
        )
        val secondDone = CompletableDeferred<PolishOutcome>()
        polish.driveAttempt(
            text = "second",
            onOutcome = { outcome, _ -> secondDone.complete(outcome) },
        )

        val secondOutcome = withTimeout(10_000) { secondDone.await() }
        val firstOutcome = withTimeout(10_000) { firstDone.await() }
        polish.close()

        assertEquals(PolishOutcome.CANCELLED, secondOutcome)
        assertEquals(PolishOutcome.SUCCESS, firstOutcome)
    }

    @Test
    fun `cancel attempt aborts without reporting`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"choices":[{"message":{"content":"late"}}]}""")
                .build(),
        )
        val polish = polisher(this)
        val reported = CompletableDeferred<Unit>()
        polish.driveAttempt(
            text = "raw",
            onOutcome = { _, _ -> reported.complete(Unit) },
        )
        polish.cancelAttempt()
        delay(500)
        assertTrue(!reported.isCompleted, "a cancelled attempt must never report an outcome")
        polish.close()
    }
}