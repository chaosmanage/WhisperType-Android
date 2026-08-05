package com.whispertype.android.platform.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure, host-testable mapping of the Gemini Live WebSocket
 * (`BidiGenerateContent`) wire messages. Building and parsing never touch a
 * socket; [OkHttpGeminiLiveSession] only forwards the produced JSON strings and
 * feeds received strings back through [parseServerMessage].
 *
 * Wire contract (per the Live API reference):
 *  - the first client message is `{"setup": {model, generationConfig, systemInstruction}}`
 *    and the server replies `setupComplete` (or `setupError`);
 *  - audio is sent as `{"realtimeInput": {"audio": {"data": <base64>, "mimeType": "audio/pcm;rate=16000"}}}`;
 *  - the client ends its turn with `{"clientContent": {"turnComplete": true}}`;
 *  - the server streams `serverContent` objects carrying `modelTurn.parts[].text`,
 *    `inputTranscription.text`, `turnComplete`, and `interrupted`.
 */
object GeminiLiveWire {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    // ------------------------------------------------------------------
    // Client -> server
    // ------------------------------------------------------------------

    /** The mandatory first message: session setup with model + config. */
    fun buildSetup(config: GeminiSessionConfig): String {
        val setup = buildJsonObject {
            put("model", "models/${config.model}")
            put(
                "generationConfig",
                buildJsonObject {
                    put(
                        "responseModalities",
                        kotlinx.serialization.json.buildJsonArray {
                            config.responseModalities.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                },
            )
            // Voice-to-text: enable transcription of the user's speech so the
            // server returns serverContent.inputTranscription.text (the dictation
            // source). The model's own output stays audio and is never read.
            // NOTE: the Live API rejects a languageCode field on this config
            // ("unknown name language code"), so no language is sent here; the
            // Hinglish Latin-script bias comes from the systemInstruction instead.
            if (config.inputAudioTranscription) {
                put("inputAudioTranscription", buildJsonObject {})
            }
            // Echo fallback: outputAudioTranscription transcribes the model's own
            // audio reply. When the model is instructed to repeat the user's words
            // verbatim, outputTranscription.text is the dictation text — used as a
            // fallback because the server does not always deliver inputTranscription.
            if (config.outputAudioTranscription) {
                put("outputAudioTranscription", buildJsonObject {})
            }
            // Push-to-talk manual activity signaling (Release A experiment): with
            // automatic detection disabled, the client delimits each utterance
            // with explicit activityStart / activityEnd realtime-input boundaries.
            if (config.automaticActivityDetectionDisabled) {
                put(
                    "realtimeInputConfig",
                    buildJsonObject {
                        put(
                            "automaticActivityDetection",
                            buildJsonObject { put("disabled", true) },
                        )
                    },
                )
            }
            config.systemInstruction?.let { instruction ->
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("text", instruction) })
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject { put("setup", setup) }.toString()
    }

    /** One realtime audio chunk. [dataBase64] is base64 of raw PCM16 bytes. */
    fun buildAudioChunk(dataBase64: String, sampleRateHz: Int): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put(
                        "audio",
                        buildJsonObject {
                            put("data", dataBase64)
                            put("mimeType", "audio/pcm;rate=$sampleRateHz")
                        },
                    )
                },
            )
        }.toString()

    /** Client signals the end of its turn and that generation may begin. */
    fun buildTurnComplete(): String =
        buildJsonObject {
            put("clientContent", buildJsonObject { put("turnComplete", true) })
        }.toString()

    /**
     * Explicit realtime activity boundary: start of a push-to-talk utterance
     * (manual activity detection). Sent as a realtime-input message before the
     * first audio frame.
     */
    fun buildActivityStart(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("activityStart", buildJsonObject {}) },
            )
        }.toString()

    /**
     * Explicit realtime activity boundary: end of a push-to-talk utterance
     * (manual activity detection). Sent as a realtime-input message after the
     * final audio frame.
     */
    fun buildActivityEnd(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("activityEnd", buildJsonObject {}) },
            )
        }.toString()

    /**
     * Realtime completion signal used with automatic activity detection: marks
     * the end of the microphone stream. A realtime-input message with Boolean
     * `true`.
     */
    fun buildAudioStreamEnd(): String =
        buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject { put("audioStreamEnd", true) },
            )
        }.toString()

    /**
     * Explicit realtime activity boundary: start of a push-to-talk utterance
     * (manual activity detection). Sent as a realtime-input message before the
     * first audio frame.
     */
    fun parseServerMessage(raw: String): ServerMessage =
        try {
            val root = json.parseToJsonElement(raw).jsonObject
            when {
                root.containsKey("setupComplete") -> ServerMessage.SetupComplete
                root.containsKey("setupError") -> parseSetupError(root)
                root.containsKey("error") -> parseTopLevelError(root)
                root.containsKey("serverContent") -> parseServerContent(root)
                root.containsKey("goAway") -> ServerMessage.GoAway
                else -> ServerMessage.Unknown(raw)
            }
        } catch (_: Throwable) {
            ServerMessage.Unknown(raw)
        }

    private fun parseSetupError(root: JsonObject): ServerMessage {
        val error = root["setupError"]?.jsonObject
        val message = error?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        return ServerMessage.SetupError(
            message ?: "Unknown setup error",
        )
    }

    private fun parseTopLevelError(root: JsonObject): ServerMessage {
        val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        return ServerMessage.SetupError(
            message ?: "Unknown setup error",
        )
    }

    private fun parseServerContent(root: JsonObject): ServerMessage {
        val content = root["serverContent"]?.jsonObject
        val textParts = content?.get("modelTurn")
            ?.jsonObject
            ?.get("parts")
            ?.let { parts -> extractTextParts(parts) }
            ?: emptyList()
        val inputTranscription = content
            ?.get("inputTranscription")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
        val outputTranscription = content
            ?.get("outputTranscription")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull
        val turnComplete = content?.get("turnComplete")?.jsonPrimitive?.booleanOrNull ?: false
        val interrupted = content?.get("interrupted")?.jsonPrimitive?.booleanOrNull ?: false
        return ServerMessage.ServerContent(
            textParts = textParts,
            inputTranscription = inputTranscription,
            outputTranscription = outputTranscription,
            turnComplete = turnComplete,
            interrupted = interrupted,
        )
    }

    private fun extractTextParts(parts: kotlinx.serialization.json.JsonElement): List<String> {
        val array = parts as? JsonArray ?: return emptyList()
        val texts = ArrayList<String>()
        for (part in array) {
            val obj = part as? JsonObject ?: continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            texts.add(text)
        }
        return texts
    }

    /**
     * The parts of a server message that map to session behavior. Fields are
     * intentionally nullable/flags rather than nested objects so the session
     * stays free of JsonElement handling.
     */
    sealed interface ServerMessage {
        /** `setupComplete` received; audio transmission may begin. */
        data object SetupComplete : ServerMessage

        /** `setupError` received; the session cannot proceed. */
        data class SetupError(val message: String) : ServerMessage

        /**
         * A `serverContent` frame. [textParts] are model text parts (empty for
         * a pure input-transcription echo), [inputTranscription] is the
         * recognized user speech for that frame, [outputTranscription] is the
         * model's own audio reply transcribed (the echo fallback), and the
         * flags describe the turn lifecycle.
         */
        data class ServerContent(
            val textParts: List<String>,
            val inputTranscription: String?,
            val outputTranscription: String?,
            val turnComplete: Boolean,
            val interrupted: Boolean,
        ) : ServerMessage

        /** `goAway` received; the server is closing the session. */
        data object GoAway : ServerMessage

        /** A message with no session action (tool calls, grounding, unknown). */
        data class Unknown(val raw: String) : ServerMessage
    }
}
