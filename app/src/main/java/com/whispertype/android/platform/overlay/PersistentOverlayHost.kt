package com.whispertype.android.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.OverlayUiState
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.overlay.BubblePlacement
import com.whispertype.android.ui.theme.WhisperTypeTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one persistent production overlay window.
 *
 * Positioning is deliberately simple (0.4.1): the window always uses
 * `gravity = TOP|START` with an absolute pixel [currentPixel] as the single
 * source of truth. A drag adds deltas to that pixel position and calls
 * `updateViewLayout`; clamping uses the real measured view size. While
 * dragging, a small "X" drop target appears near the bottom; dropping the
 * bubble on it hides the bubble until the next eligible field is focused.
 *
 * The window is `TYPE_APPLICATION_OVERLAY` (SYSTEM_ALERT_WINDOW), WRAP_CONTENT,
 * translucent, non-focusable, touchable. A lifecycle-backed ComposeView renders
 * the content; a pure [OverlayHostStateMachine] drives attach/detach with a
 * bounded retry.
 */
class PersistentOverlayHost(
    private val serviceContext: Context,
    private val owners: OverlayOwners,
    sessionState: Flow<DictationState>,
    eligibility: Flow<TargetEligibility>,
    private val placement: OverlayPlacement = OverlayPlacement(),
    private val maxRetries: Int = MAX_ATTACH_RETRIES,
    private val onBubblePositionChange: ((x: Float, y: Float) -> Unit)? = null,
) : OverlayController {

    private val _uiState = MutableStateFlow(OverlayUiState.Hidden)
    override val uiState: StateFlow<OverlayUiState> = _uiState

    private val _intents = MutableSharedFlow<OverlayIntent>(extraBufferCapacity = 4)
    override val intents: SharedFlow<OverlayIntent> = _intents

    private val _status = MutableStateFlow<OverlayHostStatus>(OverlayHostStatus.Detached)
    val status: StateFlow<OverlayHostStatus> = _status

    private val _lastFailure = MutableStateFlow<String?>(null)
    val lastFailure: StateFlow<String?> = _lastFailure

    private val machine = OverlayHostStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var view: View? = null

    @Volatile
    private var windowManager: WindowManager? = null

    /** Saved bubble top-left position in dp (null = default right-center). */
    @Volatile
    private var bubblePositionDp: Pair<Float, Float>? = null

    /** Absolute top-left pixel position of the window - the single source of truth. */
    @Volatile
    private var currentPixel: Pair<Int, Int>? = null

    /** While true, the bubble stays hidden until the next eligibility cycle. */
    @Volatile
    private var dismissed = false

    @Volatile
    private var wasEligible = false

    /** startOwners() must run once per owner lifecycle — retries must not re-run it
     *  (SavedStateRegistryController.performAttach throws otherwise). */
    @Volatile
    private var ownersStarted = false

    @Volatile
    private var dropTargetView: View? = null

    @Volatile
    private var dropTargetBounds: Rect? = null

    private var positionChangeDebounce: Runnable? = null

    private var retryCount = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            combine(sessionState, eligibility) { state, target ->
                OverlayUiState(eligibility = target, state = state)
            }
                .stateIn(scope, SharingStarted.Eagerly, OverlayUiState.Hidden)
                .collect { ui ->
                    val eligible = ui.eligibility.eligible
                    // A fresh eligibility cycle (ineligible -> eligible) re-shows a
                    // dismissed bubble and re-attempts a failed overlay attach
                    // (e.g. after the user grants Display-over-other-apps).
                    if (eligible && !wasEligible) {
                        dismissed = false
                        // Re-attempt a failed overlay attach (e.g. after the user
                        // grants Display-over-other-apps). No-op when attached.
                        if (machine.status is OverlayHostStatus.AttachFailed) attach()
                    }
                    wasEligible = eligible
                    val effective =
                        if (dismissed && ui.state is DictationState.Idle) OverlayUiState.Hidden
                        else ui
                    _uiState.value = effective
                }
        }
    }

    /** Remembers the saved bubble top-left position (dp), or clears it when either axis is null. */
    fun setBubblePosition(x: Float?, y: Float?) {
        bubblePositionDp = if (x != null && y != null) x to y else null
    }

    /** Adds the single persistent overlay window; idempotent, main-thread only. */
    override fun attach() {
        handler.post { performAttach() }
    }

    private fun performAttach() {
        if (!machine.attachRequested()) {
            _status.value = machine.status
            return
        }
        try {
            val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val container = OverlayComposeContainer(serviceContext, owners)
            val composeView = ComposeView(serviceContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val current by uiState.collectAsState()
                    WhisperTypeOverlayContent(
                        uiState = current,
                        onIntent = { _intents.tryEmit(it) },
                        onDragStart = { showDropTarget() },
                        onDragBubble = { dx, dy -> moveBy(dx, dy) },
                        onDragEnd = { hideDropTarget(); checkDropDismiss() },
                    )
                }
            }
            container.addView(composeView)
            if (!ownersStarted) {
                owners.startOwners()
                ownersStarted = true
            }
            wm.addView(container, buildLayoutParams(serviceContext, placement))
            view = container
            windowManager = wm
            retryCount = 0
            machine.attachSucceeded()
        } catch (t: Throwable) {
            machine.attachFailed()
            _lastFailure.value = t::class.simpleName ?: "AttachFailure"
            Log.w(TAG, "Overlay attach failed", t)
            view = null
            windowManager = null
            scheduleRetry()
        }
        _status.value = machine.status
    }

    private fun scheduleRetry() {
        if (retryCount >= maxRetries) return
        retryCount += 1
        Log.i(TAG, "Scheduling bounded overlay attach retry $retryCount/$maxRetries")
        handler.postDelayed({ performAttach() }, ATTACH_RETRY_DELAY_MS)
    }

    private fun performDetach() {
        hideDropTarget()
        if (!machine.detachRequested()) {
            _status.value = machine.status
            return
        }
        try {
            view?.let { windowManager?.removeView(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Overlay detach failed", t)
        } finally {
            view = null
            windowManager = null
        }
        owners.stopOwners()
        ownersStarted = false
        _status.value = machine.status
    }

    // ------------------------------------------------------------------
    // Positioning (the simple way): always TOP|START + absolute pixels
    // ------------------------------------------------------------------

    private fun density(): Float = serviceContext.resources.displayMetrics.density

    private fun bubblePx(): Int = (placement.bubbleDp * density()).roundToInt()

    private fun displaySizePx(): Pair<Int, Int> {
        val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    /** Default placement: right edge, vertically centered (clamped on-screen). */
    private fun defaultPosition(sizePx: Int): Pair<Int, Int> {
        val marginPx = (placement.edgeMarginDp * density()).toInt()
        val (dw, dh) = displaySizePx()
        return BubblePlacement.clamp(dw - sizePx - marginPx, (dh - sizePx) / 2, sizePx, sizePx, dw, dh)
    }

    private fun overlayFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun buildLayoutParams(
        context: Context,
        placement: OverlayPlacement,
    ): WindowManager.LayoutParams {
        val sizePx = bubblePx()
        val saved = bubblePositionDp?.let {
            Pair((it.first * density()).roundToInt(), (it.second * density()).roundToInt())
        }
        val base = currentPixel ?: (saved ?: defaultPosition(sizePx))
        currentPixel = base
        return windowParams(base.first, base.second)
    }

    private fun windowParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /** Moves the window by a pixel delta; clamps with the real measured size. */
    fun moveBy(dxPx: Float, dyPx: Float) {
        val currentView = view ?: return
        val wm = windowManager ?: return
        val base = currentPixel ?: return
        val w = currentView.width.takeIf { it > 0 } ?: bubblePx()
        val h = currentView.height.takeIf { it > 0 } ?: bubblePx()
        val (dw, dh) = displaySizePx()
        val target = BubblePlacement.clamp(
            (base.first + dxPx).roundToInt(),
            (base.second + dyPx).roundToInt(),
            w,
            h,
            dw,
            dh,
        )
        wm.updateViewLayout(currentView, windowParams(target.first, target.second))
        currentPixel = target
        persistPosition(target)
    }

    private fun persistPosition(pixel: Pair<Int, Int>) {
        val callback = onBubblePositionChange ?: return
        positionChangeDebounce?.let(handler::removeCallbacks)
        val xDp = pixel.first / density()
        val yDp = pixel.second / density()
        val runnable = Runnable { callback(xDp, yDp) }
        positionChangeDebounce = runnable
        handler.postDelayed(runnable, DRAG_SETTLE_DEBOUNCE_MS)
    }

    // ------------------------------------------------------------------
    // Drag drop-target ("X")
    // ------------------------------------------------------------------

    private fun showDropTarget() {
        val wm = windowManager ?: return
        if (dropTargetView != null) return
        val size = (DROP_TARGET_DP * density()).roundToInt()
        val margin = (DROP_TARGET_MARGIN_DP * density()).roundToInt()
        val (dw, dh) = displaySizePx()
        val x = (dw - size) / 2
        val y = dh - size - margin
        val tv = TextView(serviceContext).apply {
            text = "\u2715"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCE8593C.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(tv, params)
            dropTargetView = tv
            dropTargetBounds = Rect(x, y, x + size, y + size)
        } catch (t: Throwable) {
            Log.w(TAG, "Drop target add failed", t)
            dropTargetView = null
            dropTargetBounds = null
        }
    }

    private fun hideDropTarget() {
        val tv = dropTargetView ?: return
        dropTargetView = null
        dropTargetBounds = null
        try {
            windowManager?.removeView(tv)
        } catch (_: Throwable) {
        }
    }

    /** If the bubble was dropped on the X, hide it until the next eligible field. */
    private fun checkDropDismiss() {
        val bounds = dropTargetBounds ?: return
        val pos = currentPixel ?: return
        val vw = view?.width ?: 0
        val vh = view?.height ?: 0
        val cx = pos.first + vw / 2
        val cy = pos.second + vh / 2
        if (bounds.contains(cx, cy)) {
            dismissed = true
            val current = _uiState.value
            if (current.state is DictationState.Idle) {
                _uiState.value = OverlayUiState.Hidden
            }
        }
    }

    /** Removes the persistent overlay window; idempotent, main-thread only. */
    override fun detach() {
        handler.post { performDetach() }
    }

    private companion object {
        const val TAG = "PersistentOverlayHost"
        const val MAX_ATTACH_RETRIES = 3
        const val ATTACH_RETRY_DELAY_MS = 2000L
        const val DRAG_SETTLE_DEBOUNCE_MS = 150L
        const val DROP_TARGET_DP = 56f
        const val DROP_TARGET_MARGIN_DP = 24f
    }
}
