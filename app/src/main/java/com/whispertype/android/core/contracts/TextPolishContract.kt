package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.PolishBackend
import com.whispertype.android.core.model.PolishOutcome
import com.whispertype.android.core.model.TranscriptionStyle
import kotlinx.coroutines.flow.Flow

/**
 * 0.7.0: one-shot text polish. Implemented by [GeminiLiveSession] (a dedicated
 * all-silence session echo, i.e. running transcription) and by a Groq
 * SpeechCompletions client. The GatedSessionResolver/DictationMachine pairs
 * dial the winning backend at settlement; producers read the outcome.
 *
 * Audio is a frame-of-reference / VAD-scored chunk so a provider can run
 * local WebRTC VAD before spending network or emit a privacy-safe density
 * metric with its result.
 *
 * Producers never receive transcript text — committed text crosses back only
 * as the [SettledText] returned from [DictationBridge.onSettledSink]. All
 * failure signals are [PolishOutcome] codes so diagnostics stay privacy-safe.
 *
 * [ConsumeSession] must be invoked once per settled capture; calling it sums
 * one [inputTranscriptionCount] and frees the session. Attempts are
 * first-wins: a second attempt returns CANCELLED.
 */
interface TextPolishContract {
    val backend: PolishBackend
    val languageMode: LanguageMode
    val style: TranscriptionStyle
    val maxAttempts: Int

    /**
     * Host-supplied sink for the polish-committed text (the "[SettledText]
     * returned from [DictationBridge]"). The host sets this before driving an
     * attempt; providers deliver final (or streaming-final) transcript text
     * through it. Producers never read text.
     */
    var onTranscript: ((String) -> Unit)?

    /**
     * Starts (or resumes) an attempt. Reporters fire exactly once per attempt
     * unless the drive loops; [Flow] producers must recollect at CANCELLED.
     *
     * The [onRequestStarted] callback relates streaming frames to a network
     * request on the hint-bearing reporters (notably WebSocket mode) and is
     * invoked by the provider when the first network interaction begins.
     */
    fun driveAttempt(
        audio: Flow<AudioChunk>,
        frames: Long,
        onOutcome: (PolishOutcome, Long) -> Unit,
        onRequestStarted: () -> Unit = {},
    )

    /** Cancels the running attempt (fire-and-forget). */
    fun cancelAttempt()

    fun close()
}