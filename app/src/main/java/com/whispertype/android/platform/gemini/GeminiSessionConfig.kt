package com.whispertype.android.platform.gemini

import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.LanguageMode

/**
 * Immutable configuration for one [OkHttpGeminiLiveSession]. The model name
 * and the WebSocket API path version are explicit so tests and future upgrades
 * never depend on a hard-coded endpoint.
 */
class GeminiSessionConfig(
    /** Full model identifier without the `models/` prefix, e.g. `gemini-3.1-flash-live-preview`. */
    val model: String,
    /** Server audio/text output modalities. WhisperType is voice-to-text, so the
     *  Live model runs in AUDIO modality (the supported mode for the
     *  voice-only Live models); the dictation text is read from the server's
     *  `inputTranscription`, not from the model's own (audio) output. */
    val responseModalities: List<String> = listOf("AUDIO"),
    /** When true, the setup enables `inputAudioTranscription` so the server
     *  returns `serverContent.inputTranscription.text` for the user's speech. */
    val inputAudioTranscription: Boolean = true,
    /** When true, the setup enables `outputAudioTranscription` so the server
     *  transcribes the model's audio reply (`serverContent.outputTranscription`).
     *  Used as the echo fallback dictation source when `inputTranscription` is
     *  not delivered. */
    val outputAudioTranscription: Boolean = true,
    /** When true, the setup disables automatic activity detection
     *  (`realtimeInputConfig.automaticActivityDetection.disabled`) so the client
     *  must delimit push-to-talk utterances with explicit activityStart /
     *  activityEnd realtime-input boundaries. Kept internal to probes/tests
     *  during the Release A protocol experiment; never exposed as a user setting. */
    val automaticActivityDetectionDisabled: Boolean = false,
    /** Optional instruction the model applies for the whole session. */
    val systemInstruction: String? = null,
    /** Input PCM16 sample rate advertised in the audio mime type. */
    val inputSampleRateHz: Int = GemAudioFormat.SAMPLE_RATE_HZ,
    /** API version segment in the WebSocket path. */
    val apiVersion: String = DEFAULT_API_VERSION,
    /** Language mode stamped on emitted [com.whispertype.android.core.model.ResultCandidate]s. */
    val language: LanguageMode = LanguageMode.ENGLISH,
) {
    companion object {
        const val DEFAULT_API_VERSION = "v1beta"
    }
}
