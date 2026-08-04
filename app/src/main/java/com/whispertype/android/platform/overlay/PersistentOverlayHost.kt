package com.whispertype.android.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.OverlayUiState
import com.whispertype.android.core.model.TargetEligibility
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

/**
 * One persistent overlay host per accessibility-service lifetime (PRD §16.5).
 *
 * The host renders [uiState] and emits [OverlayIntent]s; it is strictly
 * presentational and never orchestrates dictation. All WindowManager
 * operations run on the main thread via [Handler]. A pure
 * [OverlayHostStateMachine] drives attach/detach lifecycle, and
 * [OverlayHostStatus] plus [lastFailure] expose a typed, non-sensitive diagnostic.
 */
class PersistentOverlayHost(
    private val baseContext: Context,
    sessionState: Flow<DictationState>,
    eligibility: Flow<TargetEligibility>,
    private val placement: OverlayPlacement = OverlayPlacement(),
) : OverlayController {

    private val _uiState = MutableStateFlow(OverlayUiState.Hidden)
    override val uiState: StateFlow<OverlayUiState> = _uiState

    private val _intents = MutableSharedFlow<OverlayIntent>(extraBufferCapacity = 4)
    override val intents: SharedFlow<OverlayIntent> = _intents

    private val _status = MutableStateFlow<OverlayHostStatus>(OverlayHostStatus.Detached)
    val status: StateFlow<OverlayHostStatus> = _status

    private val _lastFailure = MutableStateFlow<String?>(null)

    /** Non-sensitive typed diagnostic for the most recent attach failure. */
    val lastFailure: StateFlow<String?> = _lastFailure

    private val machine = OverlayHostStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var view: ComposeView? = null

    @Volatile
    private var windowManager: WindowManager? = null

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

    /** Adds the persistent overlay window; idempotent, main-thread only. */
    override fun attach() {
        handler.post { performAttach() }
    }

    private fun performAttach() {
        // Idempotent: no-op when already attached/attaching/recovering.
        if (!machine.attachRequested()) return
        // A detach() queued before this ran may already have cancelled the pending attach.
        if (machine.status != OverlayHostStatus.AttachPending) return
        try {
            val overlayContext = createWindowContext()
            val wm = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val composeView = ComposeView(overlayContext).apply {
                setContent {
                    val current by uiState.collectAsState()
                    WhisperTypeOverlayContent(
                        uiState = current,
                        onIntent = { _intents.tryEmit(it) },
                    )
                }
            }
            // addView() success is recorded only after it returns without throwing.
            wm.addView(composeView, buildLayoutParams(overlayContext, placement))
            view = composeView
            windowManager = wm
            machine.attachSucceeded()
        } catch (t: Throwable) {
            machine.attachFailed()
            _lastFailure.value = t::class.simpleName ?: "AttachFailure"
            Log.w(TAG, "Overlay attach failed", t)
        }
        _status.value = machine.status
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
        _status.value = machine.status
    }

    /** Binds to the accessibility-overlay window type on a display context. */
    private fun createWindowContext(): Context = baseContext.createWindowContext(
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        null,
    )

    /**
     * Maps the pure [OverlayPlacement] into pixel LayoutParams using the
     * display-context density. Gravity anchors the bubble to a screen edge with
     * an explicit density-derived margin; no guessed display/IME metrics.
     */
    private fun buildLayoutParams(
        context: Context,
        placement: OverlayPlacement,
    ): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val marginPx = (placement.marginDp * density).toInt()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            when (placement.edge) {
                OverlayEdge.TopEnd -> gravity = Gravity.TOP or Gravity.END
            }
            x = marginPx
            y = marginPx
        }
    }

    private companion object {
        const val TAG = "PersistentOverlayHost"
    }

    /** Removes the persistent overlay window; idempotent, main-thread only. */
    override fun detach() {
        handler.post { performDetach() }
    }
}
