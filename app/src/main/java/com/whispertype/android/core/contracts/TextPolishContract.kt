package com.whispertype.android.core.contracts

import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.PolishOutcome
import com.whispertype.android.core.model.TranscriptionStyle

/**
 * 0.7.0: contract between the runtime and a polish backend.
 *
 * One attempt takes the **settled raw ASR text** and returns the polished
 * text through [onTranscript] with a typed [PolishOutcome] (never text). The
 * host adopts the raw text unconditionally first; polish only ever upgrades
 * the insertion, so a failure, timeout, or empty response cannot lose words.
 *
 * Driving again while an attempt is active reports [PolishOutcome.CANCELLED]
 * to the new caller. [onRequestStarted] fires once the attempt has left the
 * device. Cancellation ([cancelAttempt]) aborts the in-flight attempt without
 * reporting; [close] releases backend resources.
 */
interface TextPolishContract {

    val languageMode: LanguageMode
    val style: TranscriptionStyle
    val maxAttempts: Int

    /** Host-supplied sink for the polished text; null until the host dials. */
    var onTranscript: ((String) -> Unit)?

    /** Optional sink for reported token usage (integers only). */
    var onUsage: ((promptTokens: Long, totalTokens: Long) -> Unit)?

    fun driveAttempt(
        text: String,
        onOutcome: (PolishOutcome, Long) -> Unit,
        onRequestStarted: () -> Unit = {},
    )

    /**
     * 0.8.0: optional, best-effort connection warm-up called when dictation
     * starts, so the TLS/HTTP2 handshake is not paid at settlement time. Must
     * never block, never send transcript data, and never fail the dictation.
     */
    fun warmUp() {}

    fun cancelAttempt()

    fun close()
}