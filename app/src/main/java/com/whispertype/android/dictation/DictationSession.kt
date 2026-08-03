package com.whispertype.android.dictation

import com.whispertype.android.accessibility.TargetToken
import com.whispertype.android.audio.AudioDiagnostics
import com.whispertype.android.diagnostics.TimingMetrics
import com.whispertype.android.gemini.GeminiSessionConfig

/**
 * Mutable state of one dictation session, owned by [DictationCoordinator].
 * [timing] and [config] are volatile because they are updated from the session
 * coroutine while read from the ticker coroutine and the UI/service thread.
 */
class DictationSession(
    val sessionId: String,
    val target: TargetToken,
    @Volatile
    var config: GeminiSessionConfig,
    val createdAtMillis: Long,
) {
    @Volatile
    var resultConsumed: Boolean = false

    @Volatile
    var savedResult: String? = null

    @Volatile
    var timing: TimingMetrics = TimingMetrics(sessionId = sessionId, startedAtMillis = createdAtMillis)

    val diagnostics = AudioDiagnostics()
}
