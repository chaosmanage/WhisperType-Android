package com.whispertype.android.core.model

/**
 * Events emitted by a [com.whispertype.android.core.contracts.GeminiLiveSession].
 * Distinct from transport-level connection state: connection does not imply
 * readiness.
 */
sealed interface GeminiEvent {
    /** Server setup acknowledgement received; audio transmission may begin. */
    data object Ready : GeminiEvent

    /** Throttled amplitude for the waveform meter (never raw audio). */
    data class Amplitude(val level: Float) : GeminiEvent

    /** Raw and cleaned transcript candidates for one turn. */
    data class TranscriptCandidates(val candidates: List<ResultCandidate>) : GeminiEvent

    /** Activity end acknowledged by the server. */
    data object TurnComplete : GeminiEvent

    /** Server closed the turn/session. */
    data object SessionEnd : GeminiEvent

    /** A typed, non-sensitive failure. */
    data class Failed(val failure: DictationFailure) : GeminiEvent
}