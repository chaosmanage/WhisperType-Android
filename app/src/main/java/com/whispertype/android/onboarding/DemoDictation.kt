package com.whispertype.android.onboarding

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.accessibility.TargetToken
import java.util.UUID

/**
 * Best-effort dictation trigger used by onboarding and the compatibility test.
 *
 * A real [TargetToken] is captured by the accessibility service from the focused
 * input field, including the IME bounds and window/field identifiers. The demo
 * token built here carries the app's own package name and zeroed geometry, which
 * is enough to start a session against the app's own test field, but the overlay
 * cannot be positioned against the real keyboard from this token alone.
 */
object DemoDictation {

    /**
     * Starts a dictation session targeting a fabricated token.
     * Returns false when no bridge is wired up or the session was rejected.
     */
    fun requestDemo(context: Context): Boolean {
        val bridge = runCatching { WhisperTypeApplication.instance.dictationBridge }.getOrNull() ?: return false
        val token = TargetToken(
            sessionId = UUID.randomUUID().toString(),
            inputGeneration = 0L,
            packageName = context.packageName,
            displayId = 0,
            windowId = null,
            fieldId = null,
            inputType = InputType.TYPE_CLASS_TEXT,
            initialSelectionStart = 0,
            initialSelectionEnd = 0,
            imeBounds = Rect(0, 0, 0, 0),
        )
        return bridge.begin(token)
    }
}
