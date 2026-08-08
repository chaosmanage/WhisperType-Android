package com.whispertype.android.core.model

/**
 * Immutable eligibility snapshot published by the accessibility service. The
 * bubble is shown only when [eligible] is true; any uncertainty fails closed.
 */
data class TargetEligibility(
    val serviceConnected: Boolean,
    val editorFocused: Boolean,
    val editorSecure: Boolean,
    val editorUncertain: Boolean,
    val keyboardVisible: Boolean,
    val microphoneGranted: Boolean,
    val apiKeyConfigured: Boolean,
    val appEnabled: Boolean,
    val sessionActive: Boolean,
) {
    val eligible: Boolean
        get() = serviceConnected && editorFocused && !editorSecure && !editorUncertain &&
            keyboardVisible && microphoneGranted && apiKeyConfigured && appEnabled &&
            !sessionActive

    /**
     * The hotkey path relaxes only the [keyboardVisible] gate: a physical
     * keyboard user typically has no soft IME window, yet a focused safe editor
     * is a perfectly good dictation target. All security gates
     * ([editorSecure] / [editorUncertain]) and the config gates remain closed.
     */
    val eligibleForHotkey: Boolean
        get() = serviceConnected && editorFocused && !editorSecure && !editorUncertain &&
            microphoneGranted && apiKeyConfigured && appEnabled && !sessionActive

    companion object {
        val Ineligible = TargetEligibility(
            serviceConnected = false,
            editorFocused = false,
            editorSecure = false,
            editorUncertain = false,
            keyboardVisible = false,
            microphoneGranted = false,
            apiKeyConfigured = false,
            appEnabled = true,
            sessionActive = false,
        )
    }
}