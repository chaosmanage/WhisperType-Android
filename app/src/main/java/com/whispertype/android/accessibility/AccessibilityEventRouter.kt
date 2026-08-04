package com.whispertype.android.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Routes accessibility service events to the input target and IME window trackers.
 *
 * [dispatch] handles a single event without window-list access;
 * [onAccessibilityEvent] is the service-facing entry point that additionally
 * refreshes IME bounds on focus events using the supplied window-list lambda.
 * Content-changed events are forwarded to the target tracker only when the event
 * source is editable.
 */
class AccessibilityEventRouter(
    private val tracker: InputTargetTracker,
    private val imeTracker: InputMethodWindowTracker,
) {

    /**
     * Routes [event]: focus/state-changed events update the input target and
     * invalidate the IME candidate; content-changed events pass through only for
     * editable sources.
     */
    fun dispatch(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> {
                tracker.onEvent(event, root)
                imeTracker.onWindowEvent(null, null)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (safeSource(event)?.isEditable == true) {
                    tracker.onEvent(event, root)
                }
            }

            else -> Unit
        }
    }

    /**
     * Service-facing wiring: dispatches the event and refreshes the IME window
     * bounds from [getWindows] on focus/state-changed events.
     */
    fun onAccessibilityEvent(
        event: AccessibilityEvent,
        getWindows: () -> List<AccessibilityWindowInfo>,
        root: () -> AccessibilityNodeInfo?,
    ) {
        val rootNode = try {
            root()
        } catch (_: RuntimeException) {
            null
        }
        dispatch(event, rootNode)
        if (isFocusEvent(event)) {
            imeTracker.refresh(getWindows)
        }
    }

    private fun isFocusEvent(event: AccessibilityEvent): Boolean =
        event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            // Windows-changed fires whenever an IME shows/hides; refresh the IME
            // bounds there too, matching Wispr's windows-changed-driven discovery.
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

    private fun safeSource(event: AccessibilityEvent): AccessibilityNodeInfo? =
        try {
            event.source
        } catch (_: RuntimeException) {
            null
        }
}
