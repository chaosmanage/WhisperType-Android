package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.SendResult
import kotlinx.coroutines.flow.Flow

/**
 * One Gemini Live session. Readiness is distinct from transport connection:
 * [awaitReady] returns only after the server setup acknowledgement. Exactly one
 * [endActivity] boundary is sent per session and [close] is idempotent.
 */
interface GeminiLiveSession {
    /** Blocks until the server acknowledges setup; throws on failure/timeout. */
    suspend fun awaitReady()

    /** Sends one audio chunk. Must not be called before [awaitReady] returns. */
    suspend fun sendAudio(chunk: AudioChunk): SendResult

    /** Sends the single activity-end boundary. */
    suspend fun endActivity()

    /** Server events (transcripts, amplitude, turn complete, failures). */
    fun events(): Flow<GeminiEvent>

    /** Idempotent close of the WebSocket and all session resources. */
    suspend fun close()
}