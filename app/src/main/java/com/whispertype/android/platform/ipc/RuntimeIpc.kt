package com.whispertype.android.platform.ipc

import android.os.Bundle
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TargetSnapshot

/**
 * Typed cross-process message contract between the main-process
 * [com.whispertype.android.platform.runtime.FlowRuntimeService] (overlay / session
 * owner) and the `:accessibility`-process
 * [com.whispertype.android.platform.accessibility.WhisperTypeAccessibilityService]
 * (focus / target / insertion). Locked rebuild decision §3: "Use typed IPC between
 * the runtime service and accessibility service. Use accessibility IPC for focus
 * state, target capture, insertion, and recovery only."
 *
 * Messages flow over a [android.os.Messenger] bound between the two processes;
 * every payload is packed / unpacked through the helpers below so the wire format
 * is a single source of truth.
 */
object RuntimeIpc {

    /** FlowRuntimeService binding action (same-app cross-process bind). */
    const val SERVICE_ACTION = "com.whispertype.android.action.BIND_RUNTIME"

    // Message.what codes
    const val MSG_REGISTER_REPLY = 1
    const val MSG_ELIGIBILITY = 2
    const val MSG_INSERT = 3
    const val MSG_INSERT_RESULT = 4

    /** 0.6.0: physical-keyboard hotkey press (start or complete dictation). */
    const val MSG_HOTKEY_TOGGLE = 5

    // Bundle keys (typed, non-sensitive). No editor content is ever transported.
    const val KEY_REPLY_MESSENGER = "reply_messenger"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_INSERT_TEXT = "insert_text"

    const val KEY_SERVICE_CONNECTED = "service_connected"
    const val KEY_EDITOR_FOCUSED = "editor_focused"
    const val KEY_EDITOR_SECURE = "editor_secure"
    const val KEY_EDITOR_UNCERTAIN = "editor_uncertain"
    const val KEY_KEYBOARD_VISIBLE = "keyboard_visible"
    const val KEY_MIC_GRANTED = "mic_granted"
    const val KEY_API_CONFIGURED = "api_configured"
    const val KEY_APP_ENABLED = "app_enabled"
    const val KEY_SESSION_ACTIVE = "session_active"

    const val KEY_CONNECTION_PRESENT = "connection_present"
    const val KEY_TARGET_CURRENT = "target_current"
    const val KEY_TARGET_SAFE = "target_safe"
    const val KEY_COMMIT_ACCEPTED = "commit_accepted"
    const val KEY_FAILURE_CODE = "failure_code"
    const val KEY_FAILURE_MESSAGE = "failure_message"
    const val KEY_FAILURE_RECOVERABLE = "failure_recoverable"
    const val KEY_FAILURE_RETRY_ALLOWED = "failure_retry_allowed"

    /** Packs an eligibility snapshot into a Bundle (see [unpackEligibility]). */
    fun packEligibility(e: TargetEligibility): Bundle = Bundle().apply {
        putBoolean(KEY_SERVICE_CONNECTED, e.serviceConnected)
        putBoolean(KEY_EDITOR_FOCUSED, e.editorFocused)
        putBoolean(KEY_EDITOR_SECURE, e.editorSecure)
        putBoolean(KEY_EDITOR_UNCERTAIN, e.editorUncertain)
        putBoolean(KEY_KEYBOARD_VISIBLE, e.keyboardVisible)
        putBoolean(KEY_MIC_GRANTED, e.microphoneGranted)
        putBoolean(KEY_API_CONFIGURED, e.apiKeyConfigured)
        putBoolean(KEY_APP_ENABLED, e.appEnabled)
        putBoolean(KEY_SESSION_ACTIVE, e.sessionActive)
    }

    fun unpackEligibility(b: Bundle): TargetEligibility = TargetEligibility(
        serviceConnected = b.getBoolean(KEY_SERVICE_CONNECTED),
        editorFocused = b.getBoolean(KEY_EDITOR_FOCUSED),
        editorSecure = b.getBoolean(KEY_EDITOR_SECURE),
        editorUncertain = b.getBoolean(KEY_EDITOR_UNCERTAIN),
        keyboardVisible = b.getBoolean(KEY_KEYBOARD_VISIBLE),
        microphoneGranted = b.getBoolean(KEY_MIC_GRANTED),
        apiKeyConfigured = b.getBoolean(KEY_API_CONFIGURED),
        appEnabled = b.getBoolean(KEY_APP_ENABLED),
        sessionActive = b.getBoolean(KEY_SESSION_ACTIVE),
    )

    /** Packs a typed insertion result into a Bundle (see [unpackInsertionResult]). */
    fun packInsertionResult(result: InsertionResult, sessionId: String? = null, b: Bundle = Bundle()): Bundle {
        if (sessionId != null) b.putString(KEY_SESSION_ID, sessionId)
        when (result) {
            InsertionResult.Inserted -> b.putBoolean(KEY_CONNECTION_PRESENT, true)
            InsertionResult.Ambiguous -> b.putBoolean(KEY_CONNECTION_PRESENT, false)
            is InsertionResult.Failed -> {
                b.putString(KEY_FAILURE_CODE, result.failure.code)
                b.putString(KEY_FAILURE_MESSAGE, result.failure.message)
                b.putBoolean(KEY_FAILURE_RECOVERABLE, result.failure.recoverable)
                b.putBoolean(KEY_FAILURE_RETRY_ALLOWED, result.failure.retryAllowed)
            }
        }
        return b
    }

    fun unpackInsertionResult(b: Bundle): InsertionResult {
        val code = b.getString(KEY_FAILURE_CODE)
        if (code != null) {
            return InsertionResult.Failed(
                com.whispertype.android.core.model.DictationFailure(
                    code = code,
                    message = b.getString(KEY_FAILURE_MESSAGE).orEmpty(),
                    recoverable = b.getBoolean(KEY_FAILURE_RECOVERABLE),
                    retryAllowed = b.getBoolean(KEY_FAILURE_RETRY_ALLOWED, true),
                ),
            )
        }
        return if (b.getBoolean(KEY_CONNECTION_PRESENT)) {
            InsertionResult.Inserted
        } else {
            InsertionResult.Ambiguous
        }
    }

    /** Identity fields used by the shared TargetSnapshot routing (non-content). */
    fun packTargetRouting(t: TargetSnapshot, b: Bundle = Bundle()): Bundle {
        b.putString(KEY_SESSION_ID, t.sessionId.value)
        b.putInt(KEY_TARGET_CURRENT, t.windowId)
        return b
    }
}
