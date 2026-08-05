package com.whispertype.android.platform.gemini

import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.model.LanguageMode

/**
 * Immutable configuration for one [OkHttpGeminiLiveSession]. The model name
 * and the WebSocket API path version are explicit so tests and future upgrades
 * never depend on a hard-coded endpoint.
 */
class GeminiSessionConfig(
    /** Full model identifier without the `models/` prefix, e.g. `gemini-2.5-flash-live-preview`. */
    val model: String,
    /** Server audio/text output modalities. WhisperType requests text-only transcription. */
    val responseModalities: List<String> = listOf("TEXT"),
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
