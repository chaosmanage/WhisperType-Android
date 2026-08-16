package com.whispertype.android.platform.groq

import com.whispertype.android.core.audio.AudioPickleCodec
import com.whispertype.android.core.audio.NetworkVadScorer
import com.whispertype.android.core.contracts.TextPolishContract
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.PolishBackend
import com.whispertype.android.core.model.PolishOutcome
import com.whispertype.android.core.model.TranscriptionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 0.7.0: [TextPolishContract] backed by the Groq audio stream.
 *
 * One attempt collects the 20 ms audio flow, scores each frame with the
 * energy VAD ([NetworkVadScorer]), packs the frame-of-reference matrix into
 * the pickle payload ([AudioPickleCodec]), and streams the payloads to the
 * socket. [onRequestStarted] fires on the first successful socket write, and
 * [onOutcome] fires exactly once when the attempt ends:
 *
 *  - flow completes: providers then commit the streamed text themselves; the
 *    attempt reports SUCCESS.
 *  - [deadlineMillis] elapses from the first frame: TIMEOUT.
 *  - the socket fails mid-attempt: NETWORK_ERROR.
 *  - [cancelAttempt]/[close]: CANCELLED.
 *
 * Transcript text never leaves this class and is never logged. Settled text is
 * committed by the host via [DictationBridge]; this provider surfaces only
 * [PolishOutcome] codes.
 *
 * Attempts are first-wins: a drive while another is active reports CANCELLED.
 */
class GroqSpeechProvider(
    private val client: GroqWebSocketClient,
    private val scope: CoroutineScope,
    private val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
    private val nowNanos: () -> Long = { System.nanoTime() },
) : TextPolishContract {

    override val backend: PolishBackend = PolishBackend.GROQ
    override val languageMode: LanguageMode = LanguageMode.ENGLISH
    override val style: TranscriptionStyle = TranscriptionStyle.MEDIUM
    override val maxAttempts: Int = 1

    private var attemptJob: Job? = null
    private var onOutcome: ((PolishOutcome, Long) -> Unit)? = null
    private var cancelled = false

    /** Host-supplied sink for streamed transcript text (committed via DictationBridge). */
    override var onTranscript: ((String) -> Unit)? = null

    override fun driveAttempt(
        audio: Flow<AudioChunk>,
        frames: Long,
        onOutcome: (PolishOutcome, Long) -> Unit,
        onRequestStarted: () -> Unit,
    ) {
        if (attemptJob?.isActive == true) {
            onOutcome(PolishOutcome.CANCELLED, 0L)
            return
        }
        cancelled = false
        this.onOutcome = onOutcome
        if (!client.connect(socketListener)) {
            report(PolishOutcome.NETWORK_ERROR, 0L)
            return
        }
        attemptJob = scope.launch {
            val maxRows = frames.coerceIn(1L, MAX_ROWS).toInt()
            val matrix = Array(maxRows) { FloatArray(1) }
            var written = 0
            var requestStarted = false
            val startedAt = nowNanos()

            val outcome = withTimeoutOrNull(deadlineMillis) {
                audio.collect { chunk ->
                    if (written < maxRows) {
                        matrix[written][0] = NetworkVadScorer.score(chunk.pcm16Bytes)
                        written += 1
                    }
                    if (!requestStarted && written > 0) {
                        if (client.sendBinary(AudioPickleCodec.encode(matrix, 1))) {
                            requestStarted = true
                            onRequestStarted()
                        }
                    }
                }
                PolishOutcome.SUCCESS
            } ?: PolishOutcome.TIMEOUT

            report(outcome, (nowNanos() - startedAt) / NANOS_PER_MILLISECOND)
        }
    }

    private fun report(outcome: PolishOutcome, elapsedMillis: Long) {
        val callback = this.onOutcome
        if (callback != null) {
            this.onOutcome = null
            callback(outcome, elapsedMillis)
        }
    }

    private val socketListener = object : GroqSocketListener {
        override fun onOpen() = Unit
        override fun onBinary(bytes: ByteArray) = Unit
        override fun onText(text: String) {
            onTranscript?.invoke(text)
        }

        /** Peer-initiated close: terminal for the attempt. */
        override fun onClosing() {
            if (!cancelled) report(PolishOutcome.NETWORK_ERROR, 0L)
        }

        override fun onFailure(t: Throwable) {
            if (!cancelled) report(PolishOutcome.NETWORK_ERROR, 0L)
        }

        override fun onClosed() {
            if (!cancelled) report(PolishOutcome.NETWORK_ERROR, 0L)
        }
    }

    override fun cancelAttempt() {
        cancelled = true
        attemptJob?.cancel()
        attemptJob = null
    }

    override fun close() {
        cancelAttempt()
        client.close()
    }

    private companion object {
        const val MAX_ROWS = 1500L
        const val DEFAULT_DEADLINE_MILLIS = 60_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}