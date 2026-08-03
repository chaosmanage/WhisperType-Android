package com.whispertype.android.overlay

import com.whispertype.android.dictation.DictationState

/** Everything the overlay windows need to know for a given dictation state. */
data class OverlayPresentation(
    val showDock: Boolean = false,
    val showPanel: Boolean = false,
    val showCopyUi: Boolean = false,
    val showError: Boolean = false,
    val errorMessage: String? = null,
    val amplitude: Float = 0f,
    val elapsedMillis: Long = 0L,
    val isListening: Boolean = false,
    val isFinalizing: Boolean = false,
    val isStarting: Boolean = false,
    val isInserting: Boolean = false,
    val resultLength: Int = 0,
)

/** Maps dictation states to a UI presentation. */
object OverlayStateRenderer {

    /** Returns the presentation for [state]; idle states produce an empty presentation. */
    fun render(state: DictationState): OverlayPresentation = when (state) {
        is DictationState.DockedReady -> OverlayPresentation(showDock = true)
        is DictationState.Starting -> OverlayPresentation(showPanel = true, isStarting = true)
        is DictationState.Listening -> OverlayPresentation(
            showPanel = true,
            isListening = true,
            amplitude = state.amplitude,
            elapsedMillis = state.elapsedMillis,
        )
        is DictationState.Finalizing -> OverlayPresentation(
            showPanel = true,
            isFinalizing = true,
            amplitude = state.amplitude,
        )
        is DictationState.Inserting -> OverlayPresentation(showPanel = true, isInserting = true)
        is DictationState.CopyAvailable -> OverlayPresentation(
            showCopyUi = true,
            resultLength = state.resultText.length,
        )
        is DictationState.Error -> OverlayPresentation(
            showError = true,
            errorMessage = state.failure.message,
        )
        is DictationState.Unavailable,
        is DictationState.NoEditableFocus,
        is DictationState.Success,
        is DictationState.Cancelled,
        -> OverlayPresentation()
    }
}
