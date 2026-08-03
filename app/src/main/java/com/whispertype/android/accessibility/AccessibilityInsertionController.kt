package com.whispertype.android.accessibility

import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection

/**
 * Inserts a dictation result through the focused field's input connection.
 *
 * Android 16 (API 36) removed [AccessibilityNodeInfo.createInputConnection],
 * so insertion uses the new [InputMethod.getCurrentInputConnection] API there and
 * falls back to the legacy reflective call on API 34/35.
 */
object AccessibilityInsertionController {

    private const val TAG = "WT-Accessibility"

    private val RECOVERABLE_CODES = setOf(
        "NO_ROOT",
        "NO_INPUT_CONNECTION",
        "SCREEN_LOCKED",
        "EMPTY_RESULT",
    )

    /** Abstracts commitText across the API 36 and legacy input connections. */
    private fun interface Committer {
        fun commit(text: String): Boolean
    }

    fun insert(service: WhisperTypeAccessibilityService, token: TargetToken, text: String): InsertionOutcome {
        val blankCode = validateText(text)
        if (blankCode != null) {
            return InsertionOutcome.Failure(blankCode, recoverable = false)
        }
        val root = safeRoot(service) ?: return InsertionOutcome.Failure("NO_ROOT", recoverable = true)
        val committer = acquire(service, root)
            ?: return InsertionOutcome.Failure("NO_INPUT_CONNECTION", recoverable = true)

        val failureCode = validate(
            listOf(
                "SERVICE_GONE" to (WhisperTypeAccessibilityService.shared == service),
                "SCREEN_LOCKED" to !isDeviceLocked(service),
                "PACKAGE_CHANGED" to (safePackageName(root) == token.packageName),
                "DISPLAY_CHANGED" to (safeDisplayId(root) == token.displayId),
                "FOCUS_CHANGED" to (service.currentInputGeneration() == token.inputGeneration),
                "SECURE_FIELD" to !service.isSecureFieldActive(),
                "ALREADY_CONSUMED" to !service.isConsumed(token.sessionId),
            ),
        )
        if (failureCode != null) {
            Log.i(TAG, "insert failed: $failureCode")
            return InsertionOutcome.Failure(failureCode, recoverable = failureCode in RECOVERABLE_CODES)
        }

        if (!committer.commit(text)) {
            Log.i(TAG, "insert failed: COMMIT_FAILED")
            return InsertionOutcome.Failure("COMMIT_FAILED", recoverable = true)
        }
        service.markConsumed(token.sessionId)
        return InsertionOutcome.Success
    }

    /** Returns the first failing check code, or null when every check passes. */
    internal fun validate(checks: List<Pair<String, Boolean>>): String? =
        checks.firstOrNull { !it.second }?.first

    /** Returns "EMPTY_RESULT" for blank text, or null when the text is insertable. */
    internal fun validateText(text: String): String? =
        if (text.isBlank()) "EMPTY_RESULT" else null

    internal class ConsumedTracker {
        private val consumed = mutableSetOf<String>()

        /** True when [id] was consumed by this call, false when already consumed. */
        fun consume(id: String): Boolean = consumed.add(id)

        fun isConsumed(id: String): Boolean = consumed.contains(id)
    }

    private fun acquire(service: WhisperTypeAccessibilityService, root: AccessibilityNodeInfo): Committer? {
        if (Build.VERSION.SDK_INT >= 36) {
            val apiConnection = try {
                service.getInputMethod()?.takeIf { it.currentInputStarted }?.currentInputConnection
            } catch (_: RuntimeException) {
                null
            }
            if (apiConnection != null) {
                return Committer { text ->
                    try {
                        apiConnection.commitText(text, 1, null)
                        true
                    } catch (_: RuntimeException) {
                        false
                    }
                }
            }
            return null
        }
        val focus = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        } catch (_: RuntimeException) {
            root
        }
        return try {
            val method =
                AccessibilityNodeInfo::class.java.getMethod("createInputConnection", Handler::class.java)
            val connection = method.invoke(focus, Handler(Looper.getMainLooper())) as? InputConnection
            if (connection != null) {
                Committer { text ->
                    try {
                        connection.commitText(text, 1)
                        true
                    } catch (_: RuntimeException) {
                        false
                    }
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeRoot(service: WhisperTypeAccessibilityService): AccessibilityNodeInfo? = try {
        service.rootInActiveWindow
    } catch (_: RuntimeException) {
        null
    }

    private fun isDeviceLocked(service: WhisperTypeAccessibilityService): Boolean {
        val keyguard = service.getSystemService(KeyguardManager::class.java) ?: return true
        return try {
            keyguard.isDeviceLocked
        } catch (_: RuntimeException) {
            true
        }
    }

    private fun safePackageName(root: AccessibilityNodeInfo): String? = try {
        root.packageName?.toString()
    } catch (_: RuntimeException) {
        null
    }

    private fun safeDisplayId(root: AccessibilityNodeInfo): Int = try {
        root.window?.displayId ?: Int.MIN_VALUE
    } catch (_: RuntimeException) {
        Int.MIN_VALUE
    }
}