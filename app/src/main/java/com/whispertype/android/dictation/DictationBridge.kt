package com.whispertype.android.dictation

import com.whispertype.android.accessibility.TargetToken
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam between the accessibility service/overlay (who owns the keyboard UI)
 * and the dictation engine (foreground service + Gemini + audio).
 *
 * Implemented by the lead integration in the dictation package and injected
 * via [com.whispertype.android.WhisperTypeApplication].
 */
interface DictationBridge {
    val state: StateFlow<DictationState>

    fun begin(target: TargetToken): Boolean

    fun stop()

    fun cancel()

    fun copyResult()

    fun dismissCopy()
}
