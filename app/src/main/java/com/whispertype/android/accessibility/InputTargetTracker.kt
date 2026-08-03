package com.whispertype.android.accessibility

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of the currently focused editable field.
 *
 * [isSecure] is derived from [inputType] via [SecureFieldClassifier]. Windows
 * flagged FLAG_SECURE are never tracked, so the classifier is always evaluated
 * with `isFlagSecure = false`.
 */
data class TrackedInput(
    val packageName: String,
    val displayId: Int,
    val windowId: Int?,
    val fieldId: String?,
    val inputType: Int,
    val inputGeneration: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
    val imeBounds: Rect?,
) {
    val isSecure: Boolean = SecureFieldClassifier.isSecure(inputType, isFlagSecure = false)
}

/**
 * Tracks the focused editable field from accessibility events.
 *
 * Approximations (see docs/ACCESSIBILITY_DESIGN.md): the accessibility API does
 * not expose the real EditorInfo input type, so [TrackedInput.inputType] defaults
 * to TYPE_CLASS_TEXT and is promoted to TYPE_TEXT_VARIATION_PASSWORD when the node
 * class name suggests a password/PIN field. Selection offsets are not exposed
 * either and default to 0; [TrackedInput.imeBounds] is always null here and is
 * supplied by the caller at token creation. Windows flagged FLAG_SECURE are not
 * tracked at all. The input generation increments whenever the focused editor,
 * window, or package changes (including focus loss) and via [invalidate]. No node
 * text or content is ever logged.
 */
class InputTargetTracker(context: Context) {

    private val _activeInput = MutableStateFlow<TrackedInput?>(null)

    /** Latest tracked input target, or null when no editable field is focused. */
    val activeInput: StateFlow<TrackedInput?> = _activeInput.asStateFlow()

    /**
     * Processes VIEW_FOCUSED / WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED
     * events. Focused node resolution: the event source when it is editable on
     * VIEW_FOCUSED, otherwise the input-focus node found from [root].
     */
    fun onEvent(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        val focused = findFocusedEditable(event, root)
        if (focused == null || isSecureWindow(focused)) {
            clearIfTracked()
            return
        }
        update(focused, event)
    }

    /** Current tracked input target, or null. */
    fun current(): TrackedInput? = _activeInput.value

    /**
     * Invalidates the current input generation without clearing the target, so
     * existing tokens become stale and insertion is re-validated.
     */
    fun invalidate() {
        inputGeneration += 1
    }

    /**
     * Builds a [TargetToken] for the current target, or null when nothing is
     * tracked. [imeBounds] is supplied by the caller (typically the IME window
     * tracker); selection offsets default to 0 per the accessibility API.
     */
    fun currentToken(sessionId: String, imeBounds: Rect?): TargetToken? {
        val input = _activeInput.value ?: return null
        return TargetToken(
            sessionId = sessionId,
            inputGeneration = input.inputGeneration,
            packageName = input.packageName,
            displayId = input.displayId,
            windowId = input.windowId,
            fieldId = input.fieldId?.hashCode(),
            inputType = input.inputType,
            initialSelectionStart = input.selectionStart,
            initialSelectionEnd = input.selectionEnd,
            imeBounds = imeBounds ?: Rect(),
        )
    }

    private var inputGeneration = 0L

    private fun findFocusedEditable(
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = safeSource(event)
            if (source != null && isEditable(source)) return source
        }
        val fromRoot = try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: RuntimeException) {
            null
        }
        if (fromRoot != null && isEditable(fromRoot)) return fromRoot
        return null
    }

    private fun safeSource(event: AccessibilityEvent): AccessibilityNodeInfo? =
        try {
            event.source
        } catch (_: RuntimeException) {
            null
        }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean =
        try {
            node.isEditable ||
                node.className?.toString()?.contains(EDIT_TEXT_CLASS_MARKER) == true ||
                node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
                }
        } catch (_: RuntimeException) {
            false
        }

    private fun isSecureWindow(node: AccessibilityNodeInfo): Boolean = try {
        val window = node.window ?: return true
        windowIsSecure(window)
    } catch (_: RuntimeException) {
        true
    }

    private fun windowIsSecure(window: AccessibilityWindowInfo): Boolean = try {
        val method = try {
            AccessibilityWindowInfo::class.java.getMethod("getFlagsEx")
        } catch (_: NoSuchMethodException) {
            AccessibilityWindowInfo::class.java.getMethod("getFlags")
        }
        val flags = method.invoke(window)
        when {
            flags is java.math.BigInteger -> flags.testBit(3)
            flags is Int -> flags and FLAG_SECURE != 0
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    private fun update(node: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val packageName = node.packageName?.toString() ?: event.packageName?.toString().orEmpty()
        val windowId = node.windowId
            .takeIf { it != WINDOW_ID_UNDEFINED }
            ?: event.windowId.takeIf { it != 0 }
        val displayId = node.window?.displayId ?: event.displayId
        val fieldId = node.viewIdResourceName
        val inputType = inferInputType(node)

        val current = _activeInput.value
        val focusChanged = current == null ||
            current.packageName != packageName ||
            current.windowId != windowId ||
            current.fieldId != fieldId
        if (focusChanged) {
            inputGeneration += 1
        }
        _activeInput.value = TrackedInput(
            packageName = packageName,
            displayId = displayId,
            windowId = windowId,
            fieldId = fieldId,
            inputType = inputType,
            inputGeneration = inputGeneration,
            selectionStart = 0,
            selectionEnd = 0,
            imeBounds = null,
        )
    }

    private fun clearIfTracked() {
        if (_activeInput.value != null) {
            inputGeneration += 1
            _activeInput.value = null
        }
    }

    private fun inferInputType(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString()?.lowercase(Locale.ROOT)
            ?: return InputType.TYPE_CLASS_TEXT
        return if (SECURE_CLASS_MARKERS.any { it in className }) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
    }

    private companion object {
        const val EDIT_TEXT_CLASS_MARKER = "EditText"
        const val FLAG_SECURE = 0x8
        const val WINDOW_ID_UNDEFINED = -1
        val SECURE_CLASS_MARKERS = listOf("password", "pin")
    }
}
