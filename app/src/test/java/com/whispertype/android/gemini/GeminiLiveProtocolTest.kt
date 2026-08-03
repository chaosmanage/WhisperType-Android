package com.whispertype.android.gemini

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {

    @Test
    fun setupMessageHasExactShape() {
        val message = GeminiLiveProtocol.buildSetupMessage(GeminiSessionConfig.DEFAULT_MODEL_ID, LanguageMode.ENGLISH)
        val root = GeminiJson.parse(message) as JsonValue.Obj
        val setup = root.entries["setup"] as JsonValue.Obj
        assertEquals("models/${GeminiSessionConfig.DEFAULT_MODEL_ID}", (setup.entries["model"] as JsonValue.Str).value)
        val generationConfig = setup.entries["generationConfig"] as JsonValue.Obj
        val modalities = generationConfig.entries["responseModalities"] as JsonValue.Arr
        assertEquals(listOf(JsonValue.Str("AUDIO")), modalities.items)
        val instruction = setup.entries["systemInstruction"] as JsonValue.Obj
        val parts = instruction.entries["parts"] as JsonValue.Arr
        val text = ((parts.items[0] as JsonValue.Obj).entries["text"] as JsonValue.Str).value
        assertTrue(text.contains("English"))
        assertFalse(text.contains("Latin"))
    }

    @Test
    fun setupMessageHinglishInstructionIsLatinOnly() {
        val message = GeminiLiveProtocol.buildSetupMessage(GeminiSessionConfig.DEFAULT_MODEL_ID, LanguageMode.HINGLISH)
        val root = GeminiJson.parse(message) as JsonValue.Obj
        val setup = root.entries["setup"] as JsonValue.Obj
        val instruction = setup.entries["systemInstruction"] as JsonValue.Obj
        val parts = instruction.entries["parts"] as JsonValue.Arr
        val text = ((parts.items[0] as JsonValue.Obj).entries["text"] as JsonValue.Str).value
        assertTrue(text.contains("Hinglish"))
        assertTrue(text.contains("Latin"))
    }

    @Test
    fun realtimeConfigMessageHasExactShape() {
        assertEquals(
            """{"realtimeInput":{"config":{"audio":{"sampleRateHertz":16000},"transcription":{"cleanedOutputTranscription":true,"rawTranscription":true}}}}""",
            GeminiLiveProtocol.buildRealtimeConfigMessage(16000),
        )
    }

    @Test
    fun audioMessageHasExactShapeWithBase64Data() {
        val pcm = ByteArray(4) { it.toByte() }
        val expectedData = Base64.getEncoder().encodeToString(pcm)
        assertEquals(
            """{"realtimeInput":{"mediaChunks":[{"mimeType":"audio/pcm;rate=16000","data":"$expectedData"}]}}""",
            GeminiLiveProtocol.buildAudioMessage(pcm, 16000),
        )
    }

    @Test
    fun activityEndMessageHasExactShape() {
        assertEquals(
            """{"clientContent":{"turnComplete":true}}""",
            GeminiLiveProtocol.buildActivityEndMessage(),
        )
    }

    @Test
    fun setupCompleteParsed() {
        val message = GeminiLiveProtocol.parseServerMessage("""{"setupComplete":{}}""")
        assertTrue(message is ServerMessage.SetupComplete)
    }

    @Test
    fun textPartParsedAsCleanedDelta() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"text":" hello "}]}}}""",
        ) as ServerMessage.ServerContent
        assertEquals(" hello ", message.cleanedDelta)
        assertNull(message.rawDelta)
        assertFalse(message.turnComplete)
    }

    @Test
    fun textPlainInlineDataParsedAsRawDelta() {
        val rawText = "raw transcript"
        val encoded = Base64.getEncoder().encodeToString(rawText.toByteArray())
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"text/plain","data":"$encoded"}}]}}}""",
        ) as ServerMessage.ServerContent
        assertEquals(rawText, message.rawDelta)
        assertNull(message.cleanedDelta)
    }

    @Test
    fun audioInlineDataIsIgnored() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=16000","data":"AAAA"}}]}}}""",
        ) as ServerMessage.ServerContent
        assertNull(message.rawDelta)
        assertNull(message.cleanedDelta)
    }

    @Test
    fun mixedPartsMappedToSeparateStreams() {
        val rawText = "second"
        val encoded = Base64.getEncoder().encodeToString(rawText.toByteArray())
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[{"text":"first"},{"inlineData":{"mimeType":"text/plain","data":"$encoded"}}]}}}""",
        ) as ServerMessage.ServerContent
        assertEquals("first", message.cleanedDelta)
        assertEquals(rawText, message.rawDelta)
    }

    @Test
    fun inputAndOutputTranscriptionFieldsParsed() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"raw input"},"outputTranscription":{"text":"cleaned output"}}}""",
        ) as ServerMessage.ServerContent
        assertEquals("raw input", message.rawDelta)
        assertEquals("cleaned output", message.cleanedDelta)
    }

    @Test
    fun turnCompleteFlagParsed() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"modelTurn":{"parts":[]},"turnComplete":true}}""",
        ) as ServerMessage.ServerContent
        assertTrue(message.turnComplete)
    }

    @Test
    fun interruptedFlagParsed() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"serverContent":{"interrupted":true}}""",
        ) as ServerMessage.ServerContent
        assertTrue(message.interrupted)
    }

    @Test
    fun topLevelInterruptedParsed() {
        assertTrue(GeminiLiveProtocol.parseServerMessage("""{"interrupted":true}""") is ServerMessage.Interrupted)
    }

    @Test
    fun goAwayParsed() {
        assertTrue(GeminiLiveProtocol.parseServerMessage("""{"goAway":{"timeoutMs":2000}}""") is ServerMessage.GoAway)
    }

    @Test
    fun usageMetadataIgnoredType() {
        assertTrue(
            GeminiLiveProtocol.parseServerMessage(
                """{"usageMetadata":{"promptTokenCount":1,"totalTokenCount":2}}""",
            ) is ServerMessage.UsageMetadata,
        )
    }

    @Test
    fun toolCallParsedAsToolCall() {
        assertTrue(
            GeminiLiveProtocol.parseServerMessage(
                """{"toolCall":{"functionCalls":[{"id":"1","name":"fn","args":{}}]}}""",
            ) is ServerMessage.ToolCall,
        )
    }

    @Test
    fun unknownMessageParsedAsUnknown() {
        assertTrue(GeminiLiveProtocol.parseServerMessage("""{"someFutureField":true}""") is ServerMessage.Unknown)
    }

    @Test
    fun httpStyleErrorPayloadParsed() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"error":{"code":400,"message":"request invalid","status":"INVALID_ARGUMENT"}}""",
        ) as ServerMessage.ServerError
        assertEquals(400, message.httpStatusHint)
        assertEquals("INVALID_ARGUMENT", message.serverCode)
    }

    @Test
    fun grpcStyleErrorPayloadParsed() {
        val message = GeminiLiveProtocol.parseServerMessage(
            """{"error":{"code":16,"status":"UNAUTHENTICATED"}}""",
        ) as ServerMessage.ServerError
        assertEquals(16, message.httpStatusHint)
        assertEquals("UNAUTHENTICATED", message.serverCode)
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.parseServerMessage("""{"serverContent":""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.parseServerMessage("not json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiLiveProtocol.parseServerMessage("")
        }
    }

    @Test
    fun quoteEscapesSpecialCharacters() {
        assertEquals("\"a\\\"b\"", GeminiJson.quote("a\"b"))
        assertEquals("\"a\\\\b\"", GeminiJson.quote("a\\b"))
        assertEquals("\"a\\nb\"", GeminiJson.quote("a\nb"))
        assertEquals("\"a\\u0001b\"", GeminiJson.quote("a\u0001b"))
        assertEquals("\"caf\u00E9\"", GeminiJson.quote("caf\u00E9"))
    }

    @Test
    fun jsonParserHandlesNestedStructuresAndNumbers() {
        val value = GeminiJson.parse(
            """{"a":[1,2.5,-3],"b":{"c":true,"d":null,"e":"text\u0021"}}""",
        )
        val root = value as JsonValue.Obj
        val array = root.entries["a"] as JsonValue.Arr
        assertEquals(3, array.items.size)
        assertEquals(1.0, (array.items[0] as JsonValue.Num).value, 0.0)
        assertEquals(2.5, (array.items[1] as JsonValue.Num).value, 0.0)
        assertEquals(-3.0, (array.items[2] as JsonValue.Num).value, 0.0)
        val inner = root.entries["b"] as JsonValue.Obj
        assertEquals(true, (inner.entries["c"] as JsonValue.Bool).value)
        assertTrue(inner.entries["d"] is JsonValue.Null)
        assertEquals("text!", (inner.entries["e"] as JsonValue.Str).value)
    }

    @Test
    fun malformedJsonRejectedByParser() {
        assertThrows(IllegalArgumentException::class.java) { GeminiJson.parse("""{"a":}""") }
        assertThrows(IllegalArgumentException::class.java) { GeminiJson.parse("""{"a" 1}""") }
        assertThrows(IllegalArgumentException::class.java) { GeminiJson.parse("""[1,]""") }
        assertThrows(IllegalArgumentException::class.java) { GeminiJson.parse("""{"a":"unterminated}""") }
        assertThrows(IllegalArgumentException::class.java) { GeminiJson.parse("""{"a":1} extra""") }
    }
}
