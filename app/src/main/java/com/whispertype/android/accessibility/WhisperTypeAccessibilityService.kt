package com.whispertype.android.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.dictation.DictationBridge
import com.whispertype.android.dictation.DictationState
import com.whispertype.android.overlay.IntRectPx
import com.whispertype.android.overlay.OverlayGeometryCalculator
import com.whispertype.android.overlay.OverlayGeometryCalculator.OverlayLayout
import com.whispertype.android.overlay.OverlayPresentation
import com.whispertype.android.overlay.OverlayStateRenderer
import com.whispertype.android.overlay.OverlayWindowController
import com.whispertype.android.security.SensitiveClipboard
import com.whispertype.android.settings.DockSettings
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Outcome of a text insertion attempt. */
sealed interface InsertionOutcome {
    data object Success : InsertionOutcome

    data class Failure(val reason: String, val recoverable: Boolean) : InsertionOutcome
}

/**
 * Accessibility service that tracks the focused editable field and the IME
 * window, routes events to the trackers, drives the docked-mic overlay, and
 * cancels dictation when the target becomes stale (focus change, IME loss,
 * screen off, revoked mic permission).
 */
open class WhisperTypeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var shared: WhisperTypeAccessibilityService? = null

        private const val TAG = "WT-Accessibility"
        private const val IME_HIDE_CANCEL_DELAY_MILLIS = 500L
    }

    private var inputTracker: InputTargetTracker? = null
    private var imeTracker: InputMethodWindowTracker? = null
    private var router: AccessibilityEventRouter? = null
    private var scope: CoroutineScope? = null
    private var overlay: OverlayWindowController? = null

    @Volatile
    private var lastBridgeState: DictationState? = null

    @Volatile
    private var lastKnownGeminiConfigured: Boolean = false

    /** Current IME bounds in screen pixels; drives overlay geometry. */
    private val imeBoundsFlow = MutableStateFlow<IntRectPx?>(null)

    /** The dock is hidden while the currently focused package is disabled. */
    @Volatile
    private var lastDockSettings: DockSettings = DockSettings()

    private val consumedSessions = Collections.synchronizedSet(mutableSetOf<String>())

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && stillActive()) {
                Log.i(TAG, "cancel: SCREEN_OFF")
                cancelActiveSession()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        shared = this
        val input = InputTargetTracker(this)
        val ime = InputMethodWindowTracker(this)
        inputTracker = input
        imeTracker = ime
        router = AccessibilityEventRouter(tracker = input, imeTracker = ime)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlay = OverlayWindowController(
            context = this,
            onDockTap = ::onDockTap,
            onStop = { currentBridge()?.stop() },
            onCancel = { currentBridge()?.cancel() },
            onCopy = { currentBridge()?.copyResult() },
            onDismiss = { currentBridge()?.dismissCopy() },
        )
        collectFlows()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return
        val focusEvent = evt.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            evt.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        val trackedBefore = inputTracker?.current()
        val imeBefore = imeTracker?.currentImeBounds()
        router?.onAccessibilityEvent(evt, getWindows = { getWindows() }, root = { rootInActiveWindow })
        imeBoundsFlow.value = imeTracker?.currentImeBounds()?.toIntRectPx()
        if (!stillActive()) return

        if (focusEvent) {
            val trackedNow = inputTracker?.current()
            val focusMoved = trackedBefore != trackedNow && (
                trackedBefore == null ||
                    trackedNow == null ||
                    trackedBefore.packageName != trackedNow.packageName ||
                    trackedBefore.windowId != trackedNow.windowId
                )
            if (focusMoved) {
                Log.i(TAG, "cancel: FOCUS_CHANGED")
                cancelActiveSession()
            }
        }
        if (stillActive() && imeBefore != null && imeTracker?.currentImeBounds() == null) {
            scheduleImeHideCancel()
        }
        if (stillActive() && micPermissionRevoked()) {
            Log.i(TAG, "cancel: MIC_PERMISSION_REVOKED")
            cancelActiveSession()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        overlay?.removeAll()
        overlay = null
        shared = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    open fun isUsable(): Boolean = runCatching {
        shared == this &&
            lastKnownGeminiConfigured &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED &&
            imeTracker?.currentImeBounds() != null
    }.getOrDefault(false)

    fun currentInputGeneration(): Long? = inputTracker?.current()?.inputGeneration

    fun isSecureFieldActive(): Boolean = inputTracker?.current()?.isSecure == true

    fun insertText(token: TargetToken, text: String): InsertionOutcome =
        AccessibilityInsertionController.insert(this, token, text)

    fun copyFallback(text: String): Boolean = SensitiveClipboard(this).copySensitive(text)

    internal fun markConsumed(sessionId: String) {
        consumedSessions.add(sessionId)
    }

    internal fun isConsumed(sessionId: String): Boolean = consumedSessions.contains(sessionId)

    private fun onDockTap() {
        val tracker = inputTracker ?: return
        val bridge = currentBridge() ?: return
        val ime = imeTracker?.currentImeBounds() ?: return
        val token = tracker.currentToken(UUID.randomUUID().toString(), ime) ?: return
        if (tracker.current()?.let { lastDockSettings.disabledApps.contains(it.packageName) } == true) {
            return
        }
        bridge.begin(token)
    }

    private fun collectFlows() {
        val app = currentApp() ?: return
        val bridge = app.dictationBridge
        if (bridge == null) return
        val settings = app.settingsRepository

        scope?.launch {
            bridge.state.collect { lastBridgeState = it }
        }
        scope?.launch {
            settings.geminiKeyConfigured.collect { lastKnownGeminiConfigured = it }
        }

        val overlayController = overlay ?: return
        val input = inputTracker ?: return
        val density = resources.displayMetrics.density
        scope?.launch {
            combine(
                bridge.state,
                settings.dockSettings,
                imeBoundsFlow,
                input.activeInput,
            ) { state, dockSettings, imeBounds, activeInput ->
                lastDockSettings = dockSettings
                val presentation = OverlayStateRenderer.render(state)
                val targetBlocked = activeInput != null &&
                    dockSettings.disabledApps.contains(activeInput.packageName)
                val showDock = presentation.showDock && activeInput != null && imeBounds != null && !targetBlocked
                val showPanel = presentation.showPanel && imeBounds != null
                val resolved = presentation.copy(showDock = showDock, showPanel = showPanel)
                val layout = OverlayLayout(
                    dock = if (showDock) {
                        OverlayGeometryCalculator.dockRect(imeBounds, displayBounds(), dockSettings, density)
                    } else {
                        null
                    },
                    panel = if (showPanel) {
                        OverlayGeometryCalculator.voicePanelRect(imeBounds)
                    } else {
                        null
                    },
                )
                overlayController.update(layout, resolved, dockSettings)
            }.collect {}
        }
    }

    private fun displayBounds(): IntRectPx {
        val metrics = resources.displayMetrics
        return IntRectPx(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun scheduleImeHideCancel() {
        scope?.launch {
            delay(IME_HIDE_CANCEL_DELAY_MILLIS)
            if (imeTracker?.currentImeBounds() == null && stillActive()) {
                Log.i(TAG, "cancel: IME_HIDDEN")
                cancelActiveSession()
            }
        }
    }

    private fun stillActive(): Boolean =
        lastBridgeState is DictationState.Starting ||
            lastBridgeState is DictationState.Listening ||
            lastBridgeState is DictationState.Finalizing

    private fun micPermissionRevoked(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED

    private fun cancelActiveSession() {
        currentBridge()?.cancel()
    }

    private fun currentBridge(): DictationBridge? = currentApp()?.dictationBridge

    private fun currentApp(): WhisperTypeApplication? = runCatching {
        WhisperTypeApplication.instance
    }.getOrNull()

    private fun Rect.toIntRectPx(): IntRectPx = IntRectPx(left, top, right, bottom)
}
