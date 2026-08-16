package com.whispertype.android.platform.groq

import com.whispertype.android.core.audio.AudioPickleCodec
import com.whispertype.android.core.contracts.TextPolishContract
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.PolishOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.7.0: GroqSpeechProvider protocol tests against MockWebServer's WebSocket
 * upgrade. The server stands in for the Groq audio endpoint: it records the
 * pickle payloads and may push streamed transcript frames. No live key is ever
 * needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroqSpeechProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var serverSocket: ServerSocket
    private var activeClient: OkHttpClient? = null

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        serverSocket = ServerSocket()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(serverSocket).build(),
        )
        server.start()
    }

    @AfterTest
    fun tearDown() {
        activeClient?.dispatcher?.cancelAll()
        activeClient?.connectionPool?.evictAll()
        activeClient?.dispatcher?.executorService?.shutdown()
        server.close()
    }

    private fun provider(
        scope: kotlinx.coroutines.CoroutineScope,
        deadlineMillis: Long = 60_000,
    ): GroqSpeechProvider {
        val client = OkHttpClient()
        activeClient = client
        return GroqSpeechProvider(
            client = GroqWebSocketClient(
                apiKey = "gsk_test-0123456789",
                url = server.url("/openai/v1/audio/transcriptions").toString(),
                okHttpClient = client,
            ),
            scope = scope,
            deadlineMillis = deadlineMillis,
        )
    }

    private fun chunk(seq: Long): AudioChunk = AudioChunk(
        sequence = seq,
        pcm16Bytes = ByteArray(640) { (it % 32).toByte() },
        sampleRateHz = 16_000,
        frameMillis = 20,
        capturedAtMonotonicNanos = seq * 20_000_000,
    )

    private fun awaitMessages(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private class Outcome(
        var outcome: PolishOutcome? = null,
        var elapsedMillis: Long = -1,
        var requestedStarted: Boolean = false,
        var transcript: String? = null,
    )

    private suspend fun drive(
        provider: TextPolishContract,
        audio: kotlinx.coroutines.flow.Flow<AudioChunk>,
        frames: Long = 3,
        result: Outcome,
    ) {
        provider.onTranscript = { result.transcript = it }
        provider.driveAttempt(
            audio = audio,
            frames = frames,
            onOutcome = { outcome, elapsed -> result.outcome = outcome; result.elapsedMillis = elapsed },
            onRequestStarted = { result.requestedStarted = true },
        )
    }

    // ------------------------------------------------------------------

    @Test
    fun `the provider streams one pickle payload and reports success on completion`() = runTest {
        val provider = provider(this)
        val result = Outcome()
        drive(provider, flowOf(chunk(0), chunk(1), chunk(2)), result = result)
        runCurrent()

        awaitMessages { serverSocket.binaryPayloads.isNotEmpty() }
        runCurrent()

        val payload = serverSocket.binaryPayloads.single()
        val decoded = AudioPickleCodec.decode(payload)
        assertNotNull(decoded, "the binary message must decode as an audio pickle")
        assertEquals(3, decoded.frames.size)
        assertEquals(1, decoded.frameFeatureCount)
        assertTrue(result.requestedStarted, "onRequestStarted must fire after the first write")
        assertEquals(PolishOutcome.SUCCESS, result.outcome)
        assertTrue(result.elapsedMillis >= 0)
        provider.close()
    }

    @Test
    fun `peer text frames are delivered to the transcript sink`() = runTest {
        val provider = provider(this)
        val result = Outcome()
        drive(provider, flowOf(chunk(0), chunk(1), chunk(2)), result = result)
        runCurrent()

        awaitMessages { serverSocket.binaryPayloads.isNotEmpty() }
        serverSocket.pushText("hello world")
        awaitMessages { result.transcript != null }

        assertEquals("hello world", result.transcript)
        provider.close()
    }

    @Test
    fun `a flow that never completes reports TIMEOUT at the deadline`() = runTest {
        val provider = provider(this, deadlineMillis = 100)
        val result = Outcome()
        val neverEnds = flow {
            emit(chunk(0))
            kotlinx.coroutines.awaitCancellation()
        }
        drive(provider, neverEnds, result = result)
        runCurrent()

        advanceTimeBy(101)
        runCurrent()

        assertEquals(PolishOutcome.TIMEOUT, result.outcome)
        assertTrue(result.elapsedMillis >= 0)
        provider.close()
    }

    @Test
    fun `a second drive while an attempt is active reports CANCELLED`() = runTest {
        val provider = provider(this)
        val result = Outcome()
        val neverEnds = flow {
            emit(chunk(0))
            kotlinx.coroutines.awaitCancellation()
        }
        drive(provider, neverEnds, result = result)
        runCurrent()

        val second = Outcome()
        drive(provider, flowOf(chunk(0)), result = second)
        assertEquals(PolishOutcome.CANCELLED, second.outcome)

        provider.cancelAttempt()
        runCurrent()
        provider.close()
    }

    @Test
    fun `the server closing the socket reports NETWORK_ERROR`() = runTest {
        val provider = provider(this)
        val result = Outcome()
        val inFlight = flow {
            emit(chunk(0))
            kotlinx.coroutines.awaitCancellation()
        }
        drive(provider, inFlight, result = result)
        runCurrent()

        serverSocket.closeOnFirstMessage = true
        awaitMessages { result.outcome != null }

        assertEquals(PolishOutcome.NETWORK_ERROR, result.outcome)
        provider.close()
    }

    @Test
    fun `cancelAttempt reports nothing further and clears the attempt`() = runTest {
        val provider = provider(this)
        val result = Outcome()
        val neverEnds = flow {
            emit(chunk(0))
            kotlinx.coroutines.awaitCancellation()
        }
        drive(provider, neverEnds, result = result)
        runCurrent()

        provider.cancelAttempt()
        runCurrent()
        assertNull(result.outcome, "cancellation must not surface an outcome")
        provider.close()
    }

    // ------------------------------------------------------------------

    private inner class ServerSocket : WebSocketListener() {
        val binaryPayloads = CopyOnWriteArrayList<ByteArray>()
        @Volatile
        private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binaryPayloads.add(bytes.toByteArray())
            if (closeOnFirstMessage) webSocket.close(1000, "server shutdown")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        @Volatile
        var closeOnFirstMessage = false

        fun pushText(text: String) {
            socket?.send(text)
        }
    }
}