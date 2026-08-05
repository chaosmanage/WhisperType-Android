package com.whispertype.android.platform.gemini

import com.whispertype.android.core.model.LanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

class GeminiLiveWireTest {

    private val config = GeminiSessionConfig(
        model = "gemini-test-live",
        responseModalities = listOf("AUDIO"),
        systemInstruction = "Transcribe speech only.",
        inputSampleRateHz = 16_000,
        language = LanguageMode.HINGLISH,
    )

    // ------------------------------------------------------------------
    // Client -> server builders
    // ------------------------------------------------------------------

    @Test
    fun `buildSetup emits model with models prefix`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        assertEquals("models/gemini-test-live", setup["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildSetup emits responseModalities and systemInstruction`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        val generationConfig = setup["generationConfig"]!!.jsonObject
        val modalities = generationConfig["responseModalities"]!!.jsonArray
        assertEquals(listOf("AUDIO"), modalities.map { it.jsonPrimitive.content })

        val instruction = setup["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Transcribe speech only.", instruction)
    }

    @Test
    fun `buildSetup enables inputAudioTranscription by default`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(config)).jsonObject
        val setup = root["setup"]!!.jsonObject
        assertTrue(setup.containsKey("inputAudioTranscription"))
    }

    @Test
    fun `buildSetup omits inputAudioTranscription when disabled`() {
        val bare = GeminiSessionConfig(model = "m", inputAudioTranscription = false)
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(bare)).jsonObject
        assertFalse(root["setup"]!!.jsonObject.containsKey("inputAudioTranscription"))
    }

    @Test
    fun `buildSetup omits systemInstruction when null`() {
        val bare = GeminiSessionConfig(model = "m")
        val root = Json.parseToJsonElement(GeminiLiveWire.buildSetup(bare)).jsonObject
        assertFalse(root["setup"]!!.jsonObject.containsKey("systemInstruction"))
    }

    @Test
    fun `buildAudioChunk carries base64 data and pcm mime type`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildAudioChunk("AQIDBA==", 16_000)).jsonObject
        val audio = root["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("AQIDBA==", audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildTurnComplete sets clientContent turnComplete true`() {
        val root = Json.parseToJsonElement(GeminiLiveWire.buildTurnComplete()).jsonObject
        val clientContent = root["clientContent"]!!.jsonObject
        assertTrue(clientContent["turnComplete"]!!.jsonPrimitive.boolean)
    }

    // ------------------------------------------------------------------
    // Server -> client parsing
    // ------------------------------------------------------------------

    @Test
    fun `setupComplete parses to SetupComplete`() {
        val msg = GeminiLiveWire.parseServerMessage("""{"setupComplete":{}}""")
        assertEquals(GeminiLiveWire.ServerMessage.SetupComplete, msg)
    }

    @Test
    fun `setupError parses message from nested error`() {
        val raw = """{"setupError":{"error":{"message":"API key not valid.","status":"INVALID_ARGUMENT"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.SetupError
        assertEquals("API key not valid.", msg.message)
    }

    @Test
    fun `top-level error parses to SetupError`() {
        val raw = """{"error":{"code":404,"message":"models/gemini-2.5-flash-live-preview not found","status":"NOT_FOUND"}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.SetupError
        assertEquals("models/gemini-2.5-flash-live-preview not found", msg.message)
    }

    @Test
    fun `serverContent modelTurn text parts are extracted in order`() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"first"},{"text":"second"}]}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals(listOf("first", "second"), msg.textParts)
        assertNull(msg.inputTranscription)
        assertFalse(msg.turnComplete)
        assertFalse(msg.interrupted)
    }

    @Test
    fun `serverContent inputTranscription is extracted`() {
        val raw = """{"serverContent":{"inputTranscription":{"text":"recognized speech"}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals("recognized speech", msg.inputTranscription)
        assertTrue(msg.textParts.isEmpty())
    }

    @Test
    fun `serverContent lifecycle flags parse`() {
        val raw = """{"serverContent":{"interrupted":false,"turnComplete":true}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertTrue(msg.turnComplete)
        assertFalse(msg.interrupted)
    }

    @Test
    fun `goAway parses`() {
        assertEquals(
            GeminiLiveWire.ServerMessage.GoAway,
            GeminiLiveWire.parseServerMessage("""{"goAway":{"reconnect":false}}"""),
        )
    }

    @Test
    fun `unknown and malformed messages parse to Unknown`() {
        val tool = """{"toolCall":{"id":"1","functionCalls":[]}}"""
        assertTrue(GeminiLiveWire.parseServerMessage(tool) is GeminiLiveWire.ServerMessage.Unknown)
        assertTrue(GeminiLiveWire.parseServerMessage("not json") is GeminiLiveWire.ServerMessage.Unknown)
    }

    @Test
    fun `json is configured to ignore unknown keys`() {
        // Parsing must tolerate fields the client does not model.
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"ok","groundingMetadata":{}}]},"extra":{"x":1}}}"""
        val msg = GeminiLiveWire.parseServerMessage(raw) as GeminiLiveWire.ServerMessage.ServerContent
        assertEquals(listOf("ok"), msg.textParts)
    }
}
