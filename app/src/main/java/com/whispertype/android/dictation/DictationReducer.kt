package com.whispertype.android.dictation

import com.whispertype.android.accessibility.TargetToken

/**
 * Pure state machine for dictation (Implementation Plan §10.4).
 *
 * Only one session exists at a time. Commands carrying a [SessionContext] whose
 * [SessionContext.sessionId] no longer matches [DictationState.sessionId] are stale
 * and never mutate the machine; [DictationCommand.Start] is exempt so a fresh
 * session can always be started from an idle state.
 */
object DictationReducer {

    /** Snapshot of the session a command originated from (supplied by the caller). */
    data class SessionContext(
        val sessionId: String,
        val target: TargetToken,
        val resultConsumed: Boolean,
        val resultText: String?,
        val stopped: Boolean,
    )

    /**
     * Reduces [state] against [command]. Returns [state] unchanged for every
     * combination that does not explicitly transition.
     */
    fun reduce(
        state: DictationState,
        command: DictationCommand,
        ctx: SessionContext?,
    ): DictationState {
        if (ctx != null && command !is DictationCommand.Start && ctx.sessionId != state.sessionId) {
            return state
        }
        return when (command) {
            is DictationCommand.Start -> {
                val activeSession = state is DictationState.Starting ||
                    state is DictationState.Listening ||
                    state is DictationState.Finalizing ||
                    state is DictationState.Inserting
                if (activeSession && command.sessionId != state.sessionId) {
                    state
                } else {
                    DictationState.Starting(command.sessionId, command.target)
                }
            }
            DictationCommand.Stop -> when (state) {
                is DictationState.Starting -> DictationState.Finalizing(state.sessionId, 0f)
                is DictationState.Listening -> DictationState.Finalizing(state.sessionId, state.amplitude)
                else -> state
            }
            DictationCommand.Cancel -> when (state) {
                is DictationState.Starting,
                is DictationState.Listening,
                is DictationState.Finalizing,
                is DictationState.Inserting -> DictationState.Cancelled
                else -> state
            }
            DictationCommand.CopyResult -> state
            DictationCommand.DismissCopy -> when (state) {
                is DictationState.CopyAvailable -> DictationState.DockedReady
                else -> state
            }
        }
    }
}
