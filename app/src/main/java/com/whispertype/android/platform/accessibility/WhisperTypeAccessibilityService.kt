package com.whispertype.android.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.whispertype.android.core.contracts.OverlayController
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.platform.overlay.DefaultOverlayHostFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 2 static-insertion spike (PRD FR-2 Eligibility, FR-8 Insertion,
 * §16.4). On connect it builds the classifier, the editor tracker and the
 * target gateway, then attaches the overlay host via [DefaultOverlayHostFactory]
 * and collects its intents. Tapping the bubble captures a
 * fresh target and commits [STATIC_TEST_TEXT] exactly once into the focused
 * field — keyboard stays, no Gemini / audio / dictation service.
 *
 * No API key, transcript or editor content is ever logged; [TAG] is stable and
 * non-sensitive.
 */
class WhisperTypeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tracker = EditorTracker()
    private val gateway = AccessibilityTargetGateway(tracker) { resolveLiveTarget() }

    private val sessionState = MutableStateFlow<DictationState>(DictationState.Idle)

    private var overlay: OverlayController? = null
    private var overlayJob: Job? = null

    @Volatile
    private var sessionInFlight = false

    companion object {
        private const val TAG = "WhisperTypeAccessibility"

        /** Spike fixture: static test text inserted on bubble tap (Phase 2 only). */
        private const val STATIC_TEST_TEXT = "WhisperType static insertion test"

        private const val RETURN_TO_IDLE_DELAY_MS = 1200L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        tracker.serviceConnected()
        refreshKeyboardVisible()

        val controller = DefaultOverlayHostFactory().create(
            baseContext,
            sessionState,
            gateway.currentEligibility(),
        )
        overlay = controller
        controller.attach()
        overlayJob = scope.launch {
            controller.intents.collect { intent -> handleIntent(intent) }
        }

        Log.i(TAG, "Accessibility service connected (static-insertion spike)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> tracker.onViewFocused(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> tracker.refreshFromRoot(rootInActiveWindow)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> tracker.refreshFromRoot(rootInActiveWindow)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshKeyboardVisible()
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // No-op: cancellation of an in-flight session is handled by higher layers.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardownOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Overlay / intents
    // ------------------------------------------------------------------

    private fun teardownOverlay() {
        overlayJob?.cancel()
        overlayJob = null
        overlay?.detach()
        overlay = null
    }

    private fun handleIntent(intent: OverlayIntent) {
        when (intent) {
            OverlayIntent.START_DICTATION -> startStaticInsertion()
            // No listener/orchestration for the remaining intents in this spike.
            OverlayIntent.STOP,
            OverlayIntent.CANCEL,
            OverlayIntent.COPY,
            OverlayIntent.DISMISS,
            -> Unit
        }
    }

    private fun startStaticInsertion() {
        if (sessionInFlight) return
        sessionInFlight = true

        val sessionId = SessionId.new()
        val target = gateway.captureTarget(sessionId)
        if (target == null) {
            sessionState.value = DictationState.Error(
                sessionId,
                DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ),
            )
            scope.launch { resetToIdle() }
        } else {
            sessionState.value = DictationState.Starting(sessionId, target)
            scope.launch {
                val result = gateway.insert(target, STATIC_TEST_TEXT)
                sessionState.value = when (result) {
                    InsertionResult.Inserted -> DictationState.Success(sessionId)
                    is InsertionResult.Failed -> DictationState.Error(sessionId, result.failure)
                    InsertionResult.Ambiguous -> DictationState.Error(sessionId, InsertionDecision.ambiguous())
                }
                delay(RETURN_TO_IDLE_DELAY_MS)
                resetToIdle()
            }
        }
    }

    private suspend fun resetToIdle() {
        sessionState.value = DictationState.Idle
        sessionInFlight = false
    }

    // ------------------------------------------------------------------
    // Live connection / keyboard
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun refreshKeyboardVisible() {
        val hasIme = try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Throwable) {
            false
        }
        tracker.updateKeyboardVisible(hasIme)
    }

    private fun resolveLiveTarget(): LiveTarget? {
        val root = rootInActiveWindow ?: return null
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
        if (!focus.isEditable) return null
        val windowId = focus.window?.id ?: -1

        val tracked = tracker.currentFocus
        val packageName = tracked?.packageName ?: focus.packageName?.toString() ?: return null
        val generation = tracked?.generation ?: 0L
        return LiveTarget(
            packageName = packageName,
            windowId = windowId,
            generation = generation,
            node = focus,
        )
    }
}

