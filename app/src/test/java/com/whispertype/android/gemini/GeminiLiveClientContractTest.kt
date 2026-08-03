package com.whispertype.android.gemini

import com.whispertype.android.audio.AudioChunk
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiLiveClientContractTest {

    @Test
    fun happyPathSetupAudioActivityEndTranscriptsAndSessionEnd() = runBlocking {
        val harness = ServerHarness()
        val baseUrl = harness.start()
        val logs = mutableListOf<String>()
        val client = newClient(baseUrl, logs)
        val connection = client.connect("session-1", FAKE_KEY, DEFAULT_CONFIG)

        val seen = CopyOnWriteArrayList<GeminiEvent>()
        val collectorScope = CoroutineScope(Dispatchers.Default + Job())
        collectorScope.launch { connection.events.collect { seen += it } }

        connection.sendAudio(AudioChunk(1, byteArrayOf(0, 1, 2, 3), 100L))
        connection.sendAudio(AudioChunk(2, byteArrayOf(4, 5), 120L))
        connection.sendAudio(AudioChunk(3, byteArrayOf(6), 140L))
        connection.sendActivityEnd()
        connection.sendActivityEnd()

        harness.awaitFrames(6)
        harness.send("""{"setupComplete":{}}""")
        harness.send("""{"serverContent":{"modelTurn":{"parts":[{"text":"hello "}]}}}""")
        harness.send(rawTranscriptFrame("hello"))
        harness.send("""{"serverContent":{"modelTurn":{"parts":[]},"turnComplete":true}}""")
        val events = seen.awaitSize(4)
        assertEquals(
            listOf(
                GeminiEvent.TranscriptUpdate(null, "hello "),
                GeminiEvent.TranscriptUpdate("hello", null),
                GeminiEvent.TurnComplete("hello", "hello "),
                GeminiEvent.SessionEnd("hello", "hello "),
            ),
            events,
        )

        val frames = harness.incoming
        assertEquals(6, frames.size)

        val setup = GeminiJson.parse(frames[0]) as JsonValue.Obj
        val setupBody = setup.entries["setup"] as JsonValue.Obj
        assertEquals(
            "models/${GeminiSessionConfig.DEFAULT_MODEL_ID}",
            (setupBody.entries["model"] as JsonValue.Str).value,
        )

        val realtimeInput = (GeminiJson.parse(frames[1]) as JsonValue.Obj).entries["realtimeInput"] as JsonValue.Obj
        val config = realtimeInput.entries["config"] as JsonValue.Obj
        val audio = config.entries["audio"] as JsonValue.Obj
        assertEquals(16000.0, (audio.entries["sampleRateHertz"] as JsonValue.Num).value, 0.0)
        val transcription = config.entries["transcription"] as JsonValue.Obj
        assertEquals(true, (transcription.entries["cleanedOutputTranscription"] as JsonValue.Bool).value)
        assertEquals(true, (transcription.entries["rawTranscription"] as JsonValue.Bool).value)

        val chunks = listOf(AudioChunk(1, byteArrayOf(0, 1, 2, 3), 100L), AudioChunk(2, byteArrayOf(4, 5), 120L), AudioChunk(3, byteArrayOf(6), 140L))
        chunks.forEachIndexed { index, chunk ->
            val mediaChunks = (GeminiJson.parse(frames[2 + index]) as JsonValue.Obj).entries["realtimeInput"] as JsonValue.Obj
            val media = mediaChunks.entries["mediaChunks"] as JsonValue.Arr
            val item = media.items[0] as JsonValue.Obj
            assertEquals("audio/pcm;rate=16000", (item.entries["mimeType"] as JsonValue.Str).value)
            assertEquals(Base64.getEncoder().encodeToString(chunk.pcm16Bytes), (item.entries["data"] as JsonValue.Str).value)
        }

        val activityEnd = (GeminiJson.parse(frames[5]) as JsonValue.Obj).entries["clientContent"] as JsonValue.Obj
        assertEquals(true, (activityEnd.entries["turnComplete"] as JsonValue.Bool).value)
        assertEquals(1, frames.count { it.contains("turnComplete") })

        connection.events.awaitNoFurtherEvents()

        val request = harness.server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertTrue(request!!.path!!.startsWith("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"))
        assertEquals(GeminiSessionConfig.DEFAULT_MODEL_ID, request.requestUrl?.queryParameter("model"))
        assertEquals(FAKE_KEY, request.requestUrl?.queryParameter("key"))

        assertTrue(logs.isNotEmpty())
        assertFalse(logs.any { it.contains(FAKE_KEY) })
        assertFalse(logs.any { it.contains("key=") })

        connection.close()
        collectorScope.cancel()
        harness.close()
    }

    private fun CopyOnWriteArrayList<GeminiEvent>.awaitSize(count: Int): List<GeminiEvent> {
        val deadline = System.currentTimeMillis() + 10_000
        while (size < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("expected $count events, received ${size}: $this")
            }
            Thread.sleep(10)
        }
        return this
    }

    @Test
    fun audioChunksAfterActivityEndAreDropped() = runBlocking {
        val harness = ServerHarness()
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-2", FAKE_KEY, DEFAULT_CONFIG)

        connection.sendAudio(AudioChunk(1, byteArrayOf(1), 100L))
        connection.sendActivityEnd()
        connection.sendAudio(AudioChunk(2, byteArrayOf(2), 110L))
        connection.sendAudio(AudioChunk(3, byteArrayOf(3), 120L))

        harness.awaitFrames(4)
        Thread.sleep(300)
        assertEquals(4, harness.incoming.size)
        assertTrue(harness.incoming[3].contains("turnComplete"))

        connection.close()
        harness.close()
    }

    @Test
    fun connectFailureWithHttp401IsAuthErrorWithoutRetry() = runBlocking {
        val harness = ServerHarness()
        harness.server.enqueue(MockResponse().setResponseCode(401))
        harness.server.start()
        val client = newClient(harness.baseUrl(), mutableListOf())
        val connection = client.connect("session-3", FAKE_KEY, DEFAULT_CONFIG)

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_AUTH_ERROR, failed.failure.code)
        assertFalse(failed.failure.recoverable)
        assertFalse(failed.failure.message.contains(FAKE_KEY))

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun connectFailureWithHttp500IsServerError() = runBlocking {
        val harness = ServerHarness()
        harness.server.enqueue(MockResponse().setResponseCode(500))
        harness.server.start()
        val client = newClient(harness.baseUrl(), mutableListOf())
        val connection = client.connect("session-4", FAKE_KEY, DEFAULT_CONFIG)

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_SERVER_ERROR, failed.failure.code)
        assertFalse(failed.failure.recoverable)

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun messageBeforeSetupCompleteIsProtocolError() = runBlocking {
        val harness = ServerHarness()
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-5", FAKE_KEY, DEFAULT_CONFIG)

        harness.send("""{"serverContent":{"modelTurn":{"parts":[{"text":"early"}]}}}""")

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_PROTOCOL_ERROR, failed.failure.code)
        assertFalse(failed.failure.recoverable)

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun interruptedAfterSetupCompleteIsInterruptedFailure() = runBlocking {
        val harness = ServerHarness()
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-6", FAKE_KEY, DEFAULT_CONFIG)

        harness.awaitFrames(1)
        harness.send("""{"setupComplete":{}}""")
        harness.send("""{"interrupted":true}""")

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_INTERRUPTED, failed.failure.code)
        assertTrue(failed.failure.recoverable)

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun activityEndTimeoutProducesTimedOutFailure() = runBlocking {
        val harness = ServerHarness()
        val config = GeminiSessionConfig(
            languageMode = LanguageMode.HINGLISH,
            activityEndTimeoutMillis = 150L,
        )
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-7", FAKE_KEY, config)

        connection.sendAudio(AudioChunk(1, byteArrayOf(7), 100L))
        connection.sendActivityEnd()
        harness.awaitFrames(4)

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_TIMEOUT, failed.failure.code)
        assertTrue(failed.failure.recoverable)

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun unexpectedServerCloseIsNetworkLost() = runBlocking {
        val harness = ServerHarness()
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-8", FAKE_KEY, DEFAULT_CONFIG)

        harness.awaitFrames(1)
        harness.send("""{"setupComplete":{}}""")
        harness.awaitUpgrade().close(1001, "going away")

        val events = connection.events.awaitCount(1)
        val failed = events.single() as GeminiEvent.Failed
        assertEquals(GeminiErrorMapper.CODE_NETWORK_LOST, failed.failure.code)
        assertTrue(failed.failure.recoverable)

        connection.events.awaitNoFurtherEvents()
        connection.close()
        harness.close()
    }

    @Test
    fun goAwayClosesCleanlyWithoutFailure() = runBlocking {
        val harness = ServerHarness()
        val client = newClient(harness.start(), mutableListOf())
        val connection = client.connect("session-9", FAKE_KEY, DEFAULT_CONFIG)

        harness.awaitFrames(1)
        harness.send("""{"setupComplete":{}}""")
        harness.send("""{"goAway":{"timeoutMs":1000}}""")

        harness.awaitServerClose()
        connection.events.awaitNoFurtherEvents()
        assertEquals(0, harness.incoming.count { it.contains("turnComplete") })

        connection.close()
        harness.close()
    }

    private fun newClient(baseUrl: String, logs: MutableList<String>): GeminiLiveClient =
        DefaultGeminiLiveClient(
            scope = TestScope(),
            logSink = GeminiLogSink { _, message -> logs += message },
            endpointBaseUrl = baseUrl,
        )

    private suspend fun Flow<GeminiEvent>.awaitCount(count: Int): List<GeminiEvent> =
        withTimeout(10_000) { take(count).toList() }

    private suspend fun Flow<GeminiEvent>.awaitNoFurtherEvents() {
        try {
            withTimeout(500) { drop(1).first() }
            fail("expected no further events")
        } catch (_: TimeoutCancellationException) {
        }
    }

    private fun rawTranscriptFrame(text: String): String {
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray())
        return """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"text/plain","data":"$encoded"}}]}}}"""
    }

    private class ServerHarness {
        val server = MockWebServer()
        val incoming = CopyOnWriteArrayList<String>()
        private val upgradeLatch = CountDownLatch(1)
        private val serverSocket = AtomicReference<WebSocket?>()
        private val serverClosedLatch = CountDownLatch(1)

        fun start(): String {
            server.enqueue(MockResponse().withWebSocketUpgrade(listener()))
            server.start()
            return baseUrl()
        }

        fun baseUrl(): String = server.url("/").toString().trimEnd('/')

        private fun listener(): WebSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket.set(webSocket)
                upgradeLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.add(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                serverClosedLatch.countDown()
            }
        }

        fun awaitUpgrade(): WebSocket {
            assertTrue("server never saw the WebSocket upgrade", upgradeLatch.await(5, TimeUnit.SECONDS))
            return serverSocket.get() ?: error("upgrade latch counted down without a server socket")
        }

        fun send(text: String) {
            awaitUpgrade().send(text)
        }

        fun awaitFrames(count: Int) {
            val deadline = System.currentTimeMillis() + 10_000
            while (incoming.size < count) {
                if (System.currentTimeMillis() > deadline) {
                    fail("expected $count frames, received ${incoming.size}: $incoming")
                }
                Thread.sleep(10)
            }
        }

        fun awaitServerClose() {
            assertTrue("client never closed the socket", serverClosedLatch.await(5, TimeUnit.SECONDS))
        }

        fun close() {
            try {
                server.shutdown()
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val FAKE_KEY: String = "TEST_FAKE_KEY_12345"
        val DEFAULT_CONFIG = GeminiSessionConfig(languageMode = LanguageMode.ENGLISH)
    }
}
