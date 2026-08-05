package com.whispertype.android.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import kotlin.math.roundToInt

/**
 * The one persistent production overlay, rebuilt to the Wispr Flow parity model
 * (§4.2 / §4.3 / Phase 2):
 *
 *  - window type [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] gated by
 *    `SYSTEM_ALERT_WINDOW` (matching Wispr's FlowService), **not**
 *    `TYPE_ACCESSIBILITY_OVERLAY`;
 *  - `WRAP_CONTENT` dimensions with a right-edge, vertically-centered bubble;
 *  - translucent, non-focusable, touchable window;
 *  - one single persistent attachment; content is revealed / hidden purely via
 *    [OverlayUiState] (never add/remove the window per accessibility event);
 *  - a lifecycle-backed [ComposeView] with stable
 *    [androidx.lifecycle.LifecycleOwner], [androidx.savedstate.SavedStateRegistryOwner]
 *    and [androidx.lifecycle.ViewModelStoreOwner] installed via the view tree
 *    (no bare ComposeView — §2.2 / forbidden-shortcuts);
 *  - stable attach/remove diagnostics plus a bounded, schedule-based retry after
 *    a recoverable attach failure (§2.7 / Phase 2).
 *
 * All WindowManager operations run on the main thread via [Handler]. A pure
 * [OverlayHostStateMachine] drives attach/detach lifecycle and
 * [OverlayHostStatus] plus [lastFailure] expose a typed, non-sensitive diagnostic.
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

    @Volatile
    private var bubblePositionDp: Pair<Float, Float>? = null

    private var currentPixel: Pair<Int, Int>? = null

    private var positionChangeDebounce: Runnable? = null

    private var retryCount = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            combine(sessionState, eligibility) { state, target ->
                OverlayUiState(eligibility = target, state = state)
            }
                .stateIn(scope, SharingStarted.Eagerly, OverlayUiState.Hidden)
                .collect { _uiState.value = it }
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
            // A normal application-overlay window is added through the WindowManager
            // obtained from the owning service context (same-process context that
            // runs FlowRuntimeService), never service.baseContext.
            val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // The ComposeView is wrapped in an OverlayComposeContainer which
            // installs LifecycleOwner + SavedStateRegistryOwner + ViewModelStoreOwner
            // on itself via the setViewTree*Owner() APIs. Compose's WindowRecomposer
            // finds the owners by the tag-based findViewTree*Owner() lookup traversing
            // the view tree upward from the ComposeView, so the tags must be set
            // BEFORE the window is added — fixing the "ViewTreeLifecycleOwner not
            // found" crash (§2.2).
            val container = OverlayComposeContainer(serviceContext, owners)
            val composeView = ComposeView(serviceContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val current by uiState.collectAsState()
                    WhisperTypeOverlayContent(
                        uiState = current,
                        onIntent = { _intents.tryEmit(it) },
                        onDragBubble = { dx, dy -> moveBy(dx, dy) },
                    )
                }
            }
            container.addView(composeView)
            owners.startOwners()

            // addView() success is recorded only after it returns without throwing.
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

    /**
     * Bounded retry after a recoverable (transient) attach failure (§2.7). The
     * retry count is capped so a permanent failure does not spin forever; the
     * runtime service recreates a per-display host when a display appears later.
     */
    private fun scheduleRetry() {
        if (retryCount >= maxRetries) return
        retryCount += 1
        Log.i(TAG, "Scheduling bounded overlay attach retry $retryCount/$maxRetries")
        handler.postDelayed({ performAttach() }, ATTACH_RETRY_DELAY_MS)
    }

    private fun performDetach() {
        // Idempotent and safe during AttachPending (nothing is added yet).
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
        _status.value = machine.status
    }

    /**
     * Maps the pure Wispr [OverlayPlacement] into pixel LayoutParams using the
     * display-context density. With a saved [bubblePositionDp] the window is
     * placed top-left at the saved (clamped) position; otherwise it keeps the
     * default right-edge, vertically-centered anchor. The WRAP_CONTENT window is
     * not yet measured, so the placement's bubble size (scaled by density) stands
     * in for its dimensions when clamping.
     */
    private fun buildLayoutParams(
        context: Context,
        placement: OverlayPlacement,
    ): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val marginPx = (placement.edgeMarginDp * density).toInt()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val bubblePx = (placement.bubbleDp * density).roundToInt()
        val (displayW, displayH) = displaySizePx()
        val savedPosition = bubblePositionDp?.let { saved ->
            BubblePlacement.positionPx(
                savedX = saved.first,
                savedY = saved.second,
                density = density,
                windowW = bubblePx,
                windowH = bubblePx,
                displayW = displayW,
                displayH = displayH,
            )
        }
        val anchorGravity: Int
        val anchorX: Int
        val anchorY: Int
        if (savedPosition != null) {
            anchorGravity = Gravity.TOP or Gravity.START
            anchorX = savedPosition.first
            anchorY = savedPosition.second
        } else {
            // Right edge, around vertical center (Wispr §4.2). Gravity.END keeps the
            // bubble at the locale-correct right edge; for Gravity.END the x offset is
            // measured from that edge and a negative inset moves the window inward by
            // the edge margin.
            anchorGravity = Gravity.END or Gravity.CENTER_VERTICAL
            anchorX = -marginPx
            anchorY = 0
        }
        currentPixel = savedPosition ?: BubblePlacement.clamp(
            displayW - bubblePx - marginPx,
            (displayH - bubblePx) / 2,
            bubblePx,
            bubblePx,
            displayW,
            displayH,
        )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = anchorGravity
            x = anchorX
            y = anchorY
        }
    }

    /** Current display bounds in pixels (API 30+ maximumWindowMetrics; minSdk 33). */
    private fun displaySizePx(): Pair<Int, Int> {
        val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    /**
     * Moves the overlay window by a pixel delta, invoked from Compose drag events
     * on the main thread. The first drag snapshots the default anchor position
     * (dp) so subsequent deltas accumulate; after the drag settles the new dp
     * position is reported via [onBubblePositionChange], debounced.
     */
    fun moveBy(dxPx: Float, dyPx: Float) {
        val currentView = view ?: return
        val wm = windowManager ?: return
        val density = serviceContext.resources.displayMetrics.density
        val bubblePx = (placement.bubbleDp * density).roundToInt()
        val (displayW, displayH) = displaySizePx()
        val startDp = bubblePositionDp ?: currentPixel?.let { it.first / density to it.second / density }
            ?: return
        val target = BubblePlacement.clamp(
            (startDp.first + dxPx / density).roundToInt(),
            (startDp.second + dyPx / density).roundToInt(),
            bubblePx,
            bubblePx,
            displayW,
            displayH,
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = target.first
            y = target.second
        }
        wm.updateViewLayout(currentView, params)
        currentPixel = target
        bubblePositionDp = target.first / density to target.second / density
        val callback = onBubblePositionChange
        if (callback != null) {
            positionChangeDebounce?.let(handler::removeCallbacks)
            val xDp = target.first / density
            val yDp = target.second / density
            val runnable = Runnable { callback(xDp, yDp) }
            positionChangeDebounce = runnable
            handler.postDelayed(runnable, DRAG_SETTLE_DEBOUNCE_MS)
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
    }
}
