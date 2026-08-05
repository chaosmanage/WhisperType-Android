package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.SendResult
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8

/**
 * End-to-end WebSocket protocol tests against MockWebServer's WebSocket
 * upgrade. The server stands in for the Gemini Live endpoint: it acknowledges
 * setup, records client messages, and can push serverContent frames.
 */
class OkHttpGeminiLiveSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var serverSocket: ServerSocket
    private var activeClient: OkHttpClient? = null

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        serverSocket = ServerSocket()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(serverSocket)
                .build(),
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

    private fun newSession(
        config: GeminiSessionConfig = GeminiSessionConfig(model = "test-model"),
        target: MockWebServer = server,
    ): OkHttpGeminiLiveSession {
        val client = OkHttpClient()
        activeClient = client
        return OkHttpGeminiLiveSession(client, target.url("/live").toString(), config)
    }

    private fun CoroutineScope.bufferEvents(session: GeminiLiveSession): Channel<GeminiEvent> {
        val channel = Channel<GeminiEvent>(Channel.UNLIMITED)
        launch { session.events().collect { channel.trySend(it) } }
        return channel
    }

    private suspend fun <T> receiveWithTimeout(channel: Channel<T>): T = withTimeout(5_000) { channel.receive() }

    private fun awaitMessages(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms; messages=${serverSocket.clientMessages}")
    }

    // ------------------------------------------------------------------

    @Test
    fun `setup is the first client message and carries the configured model`() = runBlocking {
        val session = newSession(
            GeminiSessionConfig(model = "gemini-test-live", systemInstruction = "Transcribe only."),
        )
        session.awaitReady()

        awaitMessages { serverSocket.clientMessages.isNotEmpty() }
        val first = serverSocket.clientMessages.first()
        val setup = Json.parseToJsonElement(first).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-test-live", setup["model"]!!.jsonPrimitive.content)
        val modalities = setup["generationConfig"]!!.jsonObject["responseModalities"]!!
        assertEquals(
            listOf("AUDIO"),
            (modalities as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue(setup.containsKey("inputAudioTranscription"))
        session.close()
    }

    @Test
    fun `awaitReady completes on setupComplete and emits Ready`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)

        session.awaitReady()
        assertEquals(GeminiEvent.Ready, receiveWithTimeout(events))
        session.close()
    }

    @Test
    fun `sendAudio before ready is rejected`() = runBlocking {
        val noAckServer = MockWebServer()
        noAckServer.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        noAckServer.start()
        try {
            val session = newSession(target = noAckServer)
            val chunk = AudioChunk(0, byteArrayOf(1, 2), sampleRateHz = 16_000)
            assertEquals(SendResult.Rejected("session_not_ready"), session.sendAudio(chunk))
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            noAckServer.close()
        }
    }

    @Test
    fun `sendAudio sends base64 pcm with rate mime type`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = session.sendAudio(AudioChunk(0, bytes, sampleRateHz = 16_000))
        assertEquals(SendResult.Accepted, result)

        awaitMessages { serverSocket.clientMessages.any { it.contains("realtimeInput") } }
        val audio = Json.parseToJsonElement(serverSocket.clientMessages.first { it.contains("realtimeInput") })
            .jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals(Base64.getEncoder().encodeToString(bytes), audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        session.close()
    }

    @Test
    fun `endActivity sends a single turnComplete boundary`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        session.endActivity()
        awaitMessages { serverSocket.clientMessages.any { it.contains("clientContent") } }
        val content = Json.parseToJsonElement(serverSocket.clientMessages.first { it.contains("clientContent") })
            .jsonObject["clientContent"]!!.jsonObject
        assertTrue(content["turnComplete"]!!.jsonPrimitive.boolean)
        session.close()
    }

    @Test
    fun `modelTurn text emits TranscriptCandidates`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events) // consume Ready

        serverSocket.push("""{"serverContent":{"modelTurn":{"parts":[{"text":"hello world"}]}}}""")
        val event = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(event)
        assertEquals(listOf("hello world"), event.candidates.map { it.raw })
        assertEquals(null, event.candidates.first().cleaned)
        session.close()
    }

    @Test
    fun `inputTranscription emits a candidate`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"inputTranscription":{"text":"recognized speech"}}}""")
        val event = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(event)
        assertEquals(listOf("recognized speech"), event.candidates.map { it.raw })
        session.close()
    }

    @Test
    fun `serverContent turnComplete emits TurnComplete`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"turnComplete":true}}""")
        assertEquals(GeminiEvent.TurnComplete, receiveWithTimeout(events))
        session.close()
    }

    @Test
    fun `setupError fails awaitReady and emits Failed`() = runBlocking {
        val failing = MockWebServer()
        failing.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"setupError":{"error":{"message":"API key not valid."}}}""".encodeUtf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
                .build(),
        )
        failing.start()
        try {
            val session = newSession(target = failing)
            val events = bufferEvents(session)

            val error = assertFailsWith<GeminiLiveException> { session.awaitReady() }
            assertEquals("API key not valid.", error.failure.message)
            assertEquals("gemini_setup", error.failure.code)

            val failed = receiveWithTimeout(events)
            assertIs<GeminiEvent.Failed>(failed)
            assertEquals("API key not valid.", failed.failure.message)
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            failing.close()
        }
    }

    @Test
    fun `close during setup fails awaitReady with the close reason`() = runBlocking {
        val closing = MockWebServer()
        closing.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1008, "API key not valid. Please pass a valid API key.")
                    }
                })
                .build(),
        )
        closing.start()
        try {
            val session = newSession(target = closing)
            val error = assertFailsWith<GeminiLiveException> { session.awaitReady() }
            assertEquals("gemini_setup", error.failure.code)
            assertTrue(error.failure.message.contains("API key not valid"))
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            closing.close()
        }
    }

    @Test
    fun `close is idempotent`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.close()
        session.close() // must not throw
        assertTrue(true)
    }

    private inner class ServerSocket : WebSocketListener() {
        val clientMessages = CopyOnWriteArrayList<String>()
        @Volatile
        private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            // The live Gemini endpoint sends server->client messages as binary
            // frames; mirror that so the session's binary onMessage is covered.
            webSocket.send("""{"setupComplete":{}}""".encodeUtf8())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            clientMessages.add(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun push(message: String) {
            socket?.send(message.encodeUtf8())
        }
    }
}
