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
    /** 0.6.2: explicit output token budget so an unknown server-side cap cannot
     *  silently truncate a long spoken reply. Null omits the field. */
    val maxOutputTokens: Int? = 8192,
    /** When true, the setup enables `inputAudioTranscription` so the server
     *  returns `serverContent.inputTranscription.text` for the user's speech. */
    val inputAudioTranscription: Boolean = true,
    /** When true, the setup enables `outputAudioTranscription` so the server
     *  transcribes the model's audio reply (`serverContent.outputTranscription`).
     *  0.4.1: this is the primary dictation source — the model is instructed to
     *  echo the user's speech (styled/polished/Latin), and the echo is read here. */
    val outputAudioTranscription: Boolean = true,
    /** When true (Release B default), the setup disables automatic activity
     *  detection (`realtimeInputConfig.automaticActivityDetection.disabled`) so
     *  the client must delimit push-to-talk utterances with explicit
     *  activityStart / activityEnd realtime-input boundaries. WhisperType is
     *  push-to-talk, so manual activity signaling is the preferred production
     *  design. */
    val automaticActivityDetectionDisabled: Boolean = true,
    /** 0.6.0 experimental: when true, setup declares
     *  `realtimeInputConfig.activityHandling = NO_INTERRUPTION` so a new activity
     *  (segment) does not cut off the model echoing the previous segment. */
    val activityHandlingNoInterruption: Boolean = false,
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
