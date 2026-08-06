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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun realtimeInputFrames(messages: List<String>): List<kotlinx.serialization.json.JsonObject> =
        messages.mapNotNull { raw ->
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@mapNotNull null
            root["realtimeInput"]?.jsonObject
        }

    private fun countMessages(messages: List<String>, key: String): Int =
        messages.count { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject["realtimeInput"]?.jsonObject?.containsKey(key) == true }
                .getOrDefault(false)
        }

    // ------------------------------------------------------------------

    @Test
    fun `setup is the first client message and carries the configured model and manual activity config`() = runBlocking {
        val session = newSession(
            GeminiSessionConfig(model = "gemini-test-live"),
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
        assertTrue(setup.containsKey("outputAudioTranscription"), "output transcription (echo) must be on by default")
        val automaticActivityDetection = setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject
        assertTrue(automaticActivityDetection["disabled"]!!.jsonPrimitive.boolean)
        assertFalse(setup.containsKey("systemInstruction"))
        assertFalse(first.contains("turns"))
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
    fun `startActivity before ready is rejected`() = runBlocking {
        val noAckServer = MockWebServer()
        noAckServer.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build(),
        )
        noAckServer.start()
        try {
            val session = newSession(target = noAckServer)
            assertEquals(SendResult.Rejected("session_not_ready"), session.startActivity())
            session.close()
        } finally {
            activeClient?.dispatcher?.cancelAll()
            activeClient?.connectionPool?.evictAll()
            noAckServer.close()
        }
    }

    @Test
    fun `startActivity sends exactly one activityStart realtime-input message`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        assertEquals(SendResult.Accepted, session.startActivity())
        awaitMessages { serverSocket.clientMessages.any { it.contains("activityStart") } }
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityStart"))
        assertFalse(serverSocket.clientMessages.any { it.contains("clientContent") })
        session.close()
    }

    @Test
    fun `duplicate startActivity sends no second wire message`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        awaitMessages { serverSocket.clientMessages.any { it.contains("activityStart") } }

        assertEquals(SendResult.Accepted, session.startActivity())
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityStart"))
        session.close()
    }

    @Test
    fun `audio before activity start is rejected`() = runBlocking {
        val session = newSession()
        session.awaitReady()

        val chunk = AudioChunk(0, byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(SendResult.Rejected("audio_before_activity_start"), session.sendAudio(chunk))
        assertEquals(0, countMessages(serverSocket.clientMessages, "realtimeInput"))
        session.close()
    }

    @Test
    fun `sendAudio sends base64 pcm with rate mime type after start`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()

        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = session.sendAudio(AudioChunk(0, bytes, sampleRateHz = 16_000))
        assertEquals(SendResult.Accepted, result)

        awaitMessages { serverSocket.clientMessages.any { it.contains("audio") } }
        val audio = realtimeInputFrames(serverSocket.clientMessages).first { it.containsKey("audio") }["audio"]!!.jsonObject
        assertEquals(Base64.getEncoder().encodeToString(bytes), audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        session.close()
    }

    @Test
    fun `dictation session sends exactly one activityStart then ordered audio then one activityEnd and no clientContent`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.sendAudio(AudioChunk(1, byteArrayOf(1, 2), sampleRateHz = 16_000))
        session.sendAudio(AudioChunk(2, byteArrayOf(3, 4), sampleRateHz = 16_000))
        assertEquals(SendResult.Accepted, session.endActivity())
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        val frames = realtimeInputFrames(serverSocket.clientMessages)
        assertEquals("activityStart", frames.first().keys.first())
        val audioFrames = frames.filter { it.containsKey("audio") }
        assertEquals(2, audioFrames.size)
        assertEquals("AQI=", audioFrames[0]["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("AwQ=", audioFrames[1]["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("activityEnd", frames.last().keys.first())
        assertTrue(serverSocket.clientMessages.none { it.contains("clientContent") })
        assertTrue(serverSocket.clientMessages.none { it.contains("audioStreamEnd") })
        session.close()
    }

    @Test
    fun `duplicate endActivity sends no second wire message`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.endActivity()
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        assertEquals(SendResult.Accepted, session.endActivity())
        assertEquals(1, countMessages(serverSocket.clientMessages, "activityEnd"))
        session.close()
    }

    @Test
    fun `audio after activity end is rejected`() = runBlocking {
        val session = newSession()
        session.awaitReady()
        session.startActivity()
        session.endActivity()
        awaitMessages { countMessages(serverSocket.clientMessages, "activityEnd") == 1 }

        val chunk = AudioChunk(3, byteArrayOf(1, 2), sampleRateHz = 16_000)
        assertEquals(SendResult.Rejected("audio_after_activity_end"), session.sendAudio(chunk))
        val audioCount = realtimeInputFrames(serverSocket.clientMessages).count { it.containsKey("audio") }
        assertEquals(0, audioCount)
        session.close()
    }

    @Test
    fun `automatic VAD session uses audioStreamEnd completion instead of activity boundaries`() = runBlocking {
        val session = newSession(GeminiSessionConfig(model = "test-model", automaticActivityDetectionDisabled = false))
        session.awaitReady()
        awaitMessages { serverSocket.clientMessages.isNotEmpty() }
        val setup = Json.parseToJsonElement(serverSocket.clientMessages.first()).jsonObject["setup"]!!.jsonObject
        assertFalse(setup.containsKey("realtimeInputConfig"))

        assertEquals(SendResult.Accepted, session.startActivity())
        assertEquals(SendResult.Accepted, session.endActivity())
        awaitMessages { serverSocket.clientMessages.any { it.contains("audioStreamEnd") } }

        val frames = realtimeInputFrames(serverSocket.clientMessages)
        assertTrue(frames.none { it.containsKey("activityStart") })
        assertTrue(frames.none { it.containsKey("activityEnd") })
        assertTrue(frames.last().containsKey("audioStreamEnd"))
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
    fun `outputTranscription emits an ECHO candidate and modelTurn text never emits`() = runBlocking {
        val session = newSession()
        val events = bufferEvents(session)
        session.awaitReady()
        receiveWithTimeout(events)

        serverSocket.push("""{"serverContent":{"outputTranscription":{"text":"We should meet on Thursday."}}}""")
        val echo = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(echo)
        assertEquals(listOf("We should meet on Thursday."), echo.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.ECHO, echo.source, "echo must be tagged as ECHO")

        serverSocket.push("""{"serverContent":{"modelTurn":{"parts":[{"text":"hello world"}]}}}""")
        serverSocket.push("""{"serverContent":{"inputTranscription":{"text":"the birch canoe"}}}""")
        val input = receiveWithTimeout(events)
        assertIs<GeminiEvent.TranscriptCandidates>(input)
        assertEquals(listOf("the birch canoe"), input.candidates.map { it.raw })
        assertEquals(GeminiEvent.TranscriptSource.INPUT, input.source)
        // modelTurn text must never emit a candidate.
        assertNull(events.tryReceive().getOrNull(), "modelTurn text must never emit a candidate")
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
