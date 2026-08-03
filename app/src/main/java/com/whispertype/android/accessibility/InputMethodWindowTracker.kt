package com.whispertype.android.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the on-screen IME window so dictation can avoid obscuring the keyboard.
 *
 * Bounds are read only from windows of type
 * [AccessibilityWindowInfo.TYPE_INPUT_METHOD] and validated: non-empty,
 * bottom-attached, keyboard-shaped (see the companion constants). Updates are
 * debounced: a candidate is emitted when observed twice in a row, or once 300 ms
 * have elapsed without a change; movement below the threshold is ignored. An
 * invalid or missing IME window emits null immediately. No window content is ever
 * logged.
 *
 * [onWindowEvent] consumes events whose window is the IME window directly; a null
 * window is ignored — call [refresh] to re-evaluate from a window list snapshot.
 * All window reads are defensive and never throw.
 */
class InputMethodWindowTracker(
    private val context: Context,
    private val displayWidthProvider: () -> Int = defaultDisplayWidth(context),
    private val displayHeightProvider: () -> Int = defaultDisplayHeight(context),
) {

    private val _imeBounds = MutableStateFlow<Rect?>(null)

    /** Latest validated IME bounds, or null when no keyboard is visible. */
    val imeBounds: StateFlow<Rect?> = _imeBounds.asStateFlow()

    /**
     * Consumes an accessibility event for the given window. When the window is the
     * IME window, its bounds are validated and fed into the debounce.
     */
    fun onWindowEvent(window: AccessibilityWindowInfo?, root: AccessibilityNodeInfo?) {
        if (window == null) return
        if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            consider(validatedBounds(window))
        }
    }

    /**
     * Re-evaluates the IME window from [getWindows]; emits null when no valid IME
     * window is found.
     */
    fun refresh(getWindows: () -> List<AccessibilityWindowInfo>) {
        val candidate = try {
            getWindows()
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let(::validatedBounds)
        } catch (_: RuntimeException) {
            null
        }
        consider(candidate)
    }

    /** Current validated IME bounds, or null when none is known. */
    fun currentImeBounds(): Rect? = _imeBounds.value

    private var pendingBounds: Rect? = null
    private var pendingSinceMs: Long = System.currentTimeMillis()
    private var emittedBounds: Rect? = null

    private fun consider(candidate: Rect?) {
        val now = System.currentTimeMillis()
        if (candidate == null) {
            pendingBounds = null
            pendingSinceMs = now
            emit(null)
            return
        }
        if (candidate == pendingBounds) {
            emit(candidate)
            return
        }
        val settled = now - pendingSinceMs >= SETTLE_MS
        val emitted = emittedBounds
        val withinThreshold = emitted != null && !differsByMoreThan(candidate, emitted)
        if (settled && !withinThreshold) {
            emit(pendingBounds)
        }
        pendingBounds = candidate
        pendingSinceMs = now
    }

    private fun emit(bounds: Rect?) {
        emittedBounds = bounds?.let { Rect(it) }
        _imeBounds.value = emittedBounds
    }

    private fun differsByMoreThan(a: Rect, b: Rect): Boolean =
        abs(a.left - b.left) > MOVEMENT_THRESHOLD_PX ||
            abs(a.top - b.top) > MOVEMENT_THRESHOLD_PX ||
            abs(a.right - b.right) > MOVEMENT_THRESHOLD_PX ||
            abs(a.bottom - b.bottom) > MOVEMENT_THRESHOLD_PX

    private fun validatedBounds(window: AccessibilityWindowInfo): Rect? =
        try {
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (isValidImeBounds(bounds)) bounds else null
        } catch (_: RuntimeException) {
            null
        }

    private fun isValidImeBounds(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val width = displayWidthProvider()
        val height = displayHeightProvider()
        if (width <= 0 || height <= 0) return false
        return abs(height - bounds.bottom) <= MAX_VERTICAL_GAP_PX &&
            bounds.width() >= MIN_KEYBOARD_WIDTH_RATIO * width &&
            bounds.height() >= MIN_KEYBOARD_HEIGHT_PX &&
            bounds.height() <= KEYBOARD_MAX_HEIGHT_PX_RATIO * height
    }

    /** Sizing heuristics for a keyboard-shaped IME window. */
    companion object {
        const val MIN_KEYBOARD_HEIGHT_PX = 150
        const val MIN_KEYBOARD_WIDTH_RATIO = 0.66f
        const val MAX_VERTICAL_GAP_PX = 8
        const val KEYBOARD_MAX_HEIGHT_PX_RATIO = 0.6f
        const val SETTLE_MS = 300L
        const val MOVEMENT_THRESHOLD_PX = 8
    }
}

private fun defaultDisplayWidth(context: Context): () -> Int = { displayBounds(context).width() }

private fun defaultDisplayHeight(context: Context): () -> Int = { displayBounds(context).height() }

private fun displayBounds(context: Context): Rect {
    val windowManager = context.getSystemService(WindowManager::class.java)
    return windowManager.currentWindowMetrics.bounds
}
