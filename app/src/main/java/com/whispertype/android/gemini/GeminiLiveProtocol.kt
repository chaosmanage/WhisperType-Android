package com.whispertype.android.gemini

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Wire protocol for Gemini Live BidiGenerateContent over WebSocket.
 *
 * Message shapes are documented in docs/GEMINI_LIVE_PROTOCOL.md. JSON is handled by a
 * small dependency-free codec (see [GeminiJson]) so the protocol is fully unit-testable on
 * the JVM: the Android framework's org.json classes are stubbed in local unit tests and no
 * test dependency override is permitted in this module.
 */
internal object GeminiLiveProtocol {

    private const val ENGLISH_INSTRUCTION: String =
        "You are a dictation engine. Transcribe the user's spoken audio into written English " +
            "exactly as heard. Output only the transcript. Do not add comments, preambles, " +
            "explanations, or corrections."
    private const val HINGLISH_INSTRUCTION: String =
        "You are a dictation engine. Transcribe the user's spoken audio into Hinglish: Hindi " +
            "romanized in Latin script, mixed naturally with English, exactly as spoken. Use only " +
            "Latin script. Output only the transcript. Do not add comments, preambles, " +
            "explanations, or corrections."

    /** System instruction for the configured [LanguageMode]. */
    fun systemInstruction(languageMode: LanguageMode): String = when (languageMode) {
        LanguageMode.ENGLISH -> ENGLISH_INSTRUCTION
        LanguageMode.HINGLISH -> HINGLISH_INSTRUCTION
    }

    /** First client message; must be sent before anything else on the socket. */
    fun buildSetupMessage(modelId: String, languageMode: LanguageMode): String {
        val model = GeminiJson.quote("models/$modelId")
        val instruction = GeminiJson.quote(systemInstruction(languageMode))
        return """{"setup":{"model":$model,"generationConfig":{"responseModalities":["AUDIO"]},""" +
            """"systemInstruction":{"parts":[{"text":$instruction}]}}}"""
    }

    /** Audio input format plus raw and cleaned transcription, merged into one config message. */
    fun buildRealtimeConfigMessage(sampleRateHz: Int): String =
        """{"realtimeInput":{"config":{"audio":{"sampleRateHertz":$sampleRateHz},""" +
            """"transcription":{"cleanedOutputTranscription":true,"rawTranscription":true}}}}"""

    /** One audio chunk, exactly one entry in mediaChunks. */
    fun buildAudioMessage(pcm16Bytes: ByteArray, sampleRateHz: Int): String {
        val encoded = Base64.getEncoder().encodeToString(pcm16Bytes)
        val mimeType = GeminiJson.quote("audio/pcm;rate=$sampleRateHz")
        val data = GeminiJson.quote(encoded)
        return """{"realtimeInput":{"mediaChunks":[{"mimeType":$mimeType,"data":$data}]}}"""
    }

    /** Activity-end boundary; the caller sends exactly one. */
    fun buildActivityEndMessage(): String = """{"clientContent":{"turnComplete":true}}"""

    /**
     * Parses one server message. Throws [IllegalArgumentException] for malformed JSON or a
     * non-object root; the caller treats that as a protocol error.
     */
    fun parseServerMessage(text: String): ServerMessage {
        val root = GeminiJson.parse(text) as? JsonValue.Obj
            ?: throw IllegalArgumentException("Server message is not a JSON object")
        val entries = root.entries
        if (entries.containsKey("setupComplete")) return ServerMessage.SetupComplete

        val error = entries["error"] as? JsonValue.Obj
        if (error != null) {
            return ServerMessage.ServerError(
                httpStatusHint = error.entries["code"]?.intOrNull(),
                serverCode = error.entries["status"]?.stringOrNull(),
            )
        }

        val content = entries["serverContent"] as? JsonValue.Obj
        if (content != null) return parseServerContent(content)

        if (entries.containsKey("interrupted")) return ServerMessage.Interrupted
        if (entries.containsKey("goAway")) return ServerMessage.GoAway
        if (entries.containsKey("usageMetadata")) return ServerMessage.UsageMetadata
        if (entries.containsKey("toolCall")) return ServerMessage.ToolCall
        return ServerMessage.Unknown
    }

    private fun parseServerContent(content: JsonValue.Obj): ServerMessage.ServerContent {
        val entries = content.entries
        val turnComplete = entries["turnComplete"]?.boolOrNull() ?: false
        val interrupted = entries["interrupted"]?.boolOrNull() ?: false

        val rawBuilder = StringBuilder()
        val cleanedBuilder = StringBuilder()

        val modelTurn = entries["modelTurn"] as? JsonValue.Obj
        val parts = modelTurn?.entries?.get("parts") as? JsonValue.Arr
        if (parts != null) {
            for (part in parts.items) {
                val partObject = part as? JsonValue.Obj ?: continue
                val partEntries = partObject.entries
                val text = partEntries["text"]?.stringOrNull()
                if (text != null) {
                    cleanedBuilder.append(text)
                    continue
                }
                val inlineData = partEntries["inlineData"] as? JsonValue.Obj
                if (inlineData != null) {
                    val mimeType = inlineData.entries["mimeType"]?.stringOrNull() ?: ""
                    if (mimeType.startsWith("audio/")) continue
                    if (mimeType == "text/plain") {
                        val data = inlineData.entries["data"]?.stringOrNull()
                        if (data != null) {
                            val decoded = decodeText(data)
                            if (decoded != null) rawBuilder.append(decoded)
                        }
                    }
                }
            }
        }

        val inputTranscription = entries["inputTranscription"] as? JsonValue.Obj
        inputTranscription?.entries?.get("text")?.stringOrNull()?.let { rawBuilder.append(it) }
        val outputTranscription = entries["outputTranscription"] as? JsonValue.Obj
        outputTranscription?.entries?.get("text")?.stringOrNull()?.let { cleanedBuilder.append(it) }

        return ServerMessage.ServerContent(
            rawDelta = rawBuilder.toString().ifEmpty { null },
            cleanedDelta = cleanedBuilder.toString().ifEmpty { null },
            turnComplete = turnComplete,
            interrupted = interrupted,
        )
    }

    private fun decodeText(base64Text: String): String? = try {
        String(Base64.getDecoder().decode(base64Text), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Parsed server message types handled by the session state machine. */
internal sealed interface ServerMessage {
    data object SetupComplete : ServerMessage
    data class ServerContent(
        val rawDelta: String?,
        val cleanedDelta: String?,
        val turnComplete: Boolean,
        val interrupted: Boolean,
    ) : ServerMessage

    data object Interrupted : ServerMessage
    data object GoAway : ServerMessage
    data object UsageMetadata : ServerMessage
    data class ServerError(val httpStatusHint: Int?, val serverCode: String?) : ServerMessage
    data object ToolCall : ServerMessage
    data object Unknown : ServerMessage
}

/** Minimal JSON document model used by [GeminiLiveProtocol]. */
internal sealed interface JsonValue {
    data class Obj(val entries: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** Dependency-free JSON parser and string escaper with deterministic output. */
internal object GeminiJson {

    /** Parses a JSON document; throws [IllegalArgumentException] on malformed input. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (parser.atEnd()) throw IllegalArgumentException("Empty JSON document")
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("Trailing content at offset ${parser.offset}")
        return value
    }

    /** Returns [text] wrapped in JSON string quotes with all required escaping. */
    fun quote(text: String): String = "\"" + escape(text) + "\""

    /** JSON string escaping: quotes, backslashes, control characters, and non-ASCII is kept verbatim. */
    fun escape(text: String): String {
        val builder = StringBuilder()
        for (ch in text) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch.isISOControl()) {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        return builder.toString()
    }

    private class Parser(private val text: String) {
        var offset: Int = 0

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        fun parseValue(): JsonValue {
            if (atEnd()) throw malformed()
            return when (val ch = text[offset]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expect("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expect("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expect("null")
                    JsonValue.Null
                }
                else -> {
                    if (ch == '-' || ch.isDigit()) parseNumber() else throw malformed()
                }
            }
        }

        fun parseObject(): JsonValue.Obj {
            offset++
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (offset < text.length && text[offset] == '}') {
                offset++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (offset >= text.length || text[offset] != '"') throw malformed()
                val key = parseString()
                skipWhitespace()
                if (offset >= text.length || text[offset] != ':') throw malformed()
                offset++
                skipWhitespace()
                entries[key] = parseValue()
                skipWhitespace()
                when {
                    offset >= text.length -> throw malformed()
                    text[offset] == ',' -> offset++
                    text[offset] == '}' -> {
                        offset++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw malformed()
                }
            }
        }

        fun parseArray(): JsonValue.Arr {
            offset++
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (offset < text.length && text[offset] == ']') {
                offset++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(parseValue())
                skipWhitespace()
                when {
                    offset >= text.length -> throw malformed()
                    text[offset] == ',' -> offset++
                    text[offset] == ']' -> {
                        offset++
                        return JsonValue.Arr(items)
                    }
                    else -> throw malformed()
                }
            }
        }

        fun parseString(): String {
            offset++
            val builder = StringBuilder()
            while (true) {
                if (offset >= text.length) throw malformed()
                val ch = text[offset++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (offset >= text.length) throw malformed()
                        when (val escaped = text[offset++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (offset + 4 > text.length) throw malformed()
                                val hex = text.substring(offset, offset + 4)
                                val code = hex.toIntOrNull(16) ?: throw malformed()
                                builder.append(code.toChar())
                                offset += 4
                            }
                            else -> throw malformed()
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        fun parseNumber(): JsonValue.Num {
            val start = offset
            while (offset < text.length) {
                val ch = text[offset]
                if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                    offset++
                } else {
                    break
                }
            }
            val raw = text.substring(start, offset)
            val number = raw.toDoubleOrNull() ?: throw malformed()
            return JsonValue.Num(number)
        }

        fun expect(literal: String) {
            if (!text.startsWith(literal, offset)) throw malformed()
            offset += literal.length
        }

        fun malformed(): IllegalArgumentException = IllegalArgumentException("Malformed JSON at offset $offset")
    }
}

internal fun JsonValue.stringOrNull(): String? = (this as? JsonValue.Str)?.value

internal fun JsonValue.boolOrNull(): Boolean? = (this as? JsonValue.Bool)?.value

internal fun JsonValue.intOrNull(): Int? = (this as? JsonValue.Num)?.value?.toInt()
