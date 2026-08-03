package com.whispertype.android.dictation

import com.whispertype.android.accessibility.TargetToken

/**
 * User or system commands for the dictation state machine.
 * Every command carries a [sessionId] where one exists.
 */
sealed interface DictationCommand {
    data class Start(val target: TargetToken, val sessionId: String) : DictationCommand

    data object Stop : DictationCommand

    data object Cancel : DictationCommand

    data object CopyResult : DictationCommand

    data object DismissCopy : DictationCommand
}
