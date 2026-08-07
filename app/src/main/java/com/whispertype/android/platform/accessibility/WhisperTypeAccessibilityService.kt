package com.whispertype.android.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetSnapshot
import com.whispertype.android.core.settings.PreferencesFileReader
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.runtime.FlowRuntimeService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The dedicated `:accessibility`-process service (locked decision §3). It sees
 * the screen only and owns focus / target / insertion — never the overlay, the
 * microphone, or Gemini. Focus and keyboard state are pushed to the main-process
 * [FlowRuntimeService] over typed IPC; the runtime responds with insert requests.
 * No editor content is ever transported over IPC.
 */
class WhisperTypeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tracker = EditorTracker()

    private val inputMethod = WhisperTypeInputMethod(this)

    private val gateway = AccessibilityTargetGateway(
        tracker = tracker,
        liveTargetProvider = { resolveLiveTarget() },
        inputConnectionProvider = { inputMethod.getCurrentInputConnection() },
    )

    @Volatile
    private var runtimeMessenger: Messenger? = null

    /** 0.5.4: app-enabled snapshot so a system restart of this accessibility
     *  service while the app is disabled does not resurrect the runtime. */
    @Volatile
    private var cachedAppEnabled: Boolean = true

    /** Whether [cachedAppEnabled] has been seeded by the poller yet. */
    private var appEnabledInitialized = false

    private var insertionJob: Job? = null

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                RuntimeIpc.MSG_INSERT -> onInsertRequest(msg)
                else -> Log.w(TAG, "Unhandled runtime message ${msg.what}")
            }
        }
    }

    private val replyMessenger = Messenger(replyHandler)

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = binder?.let { Messenger(it) }
            runtimeMessenger = remote
            if (remote == null) {
                Log.w(TAG, "Runtime service bound with null messenger")
                return
            }
            val reg = Message.obtain(null, RuntimeIpc.MSG_REGISTER_REPLY).apply {
                replyTo = replyMessenger
            }
            try {
                remote.send(reg)
            } catch (_: RemoteException) {
                runtimeMessenger = null
            }
            pushEligibility()
            Log.i(TAG, "Runtime service connected over IPC")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtimeMessenger = null
            Log.w(TAG, "Runtime service disconnected")
        }
    }

    /** Wispr-parity: return our own IME surface so cursor-aware commitText works. */
    override fun onCreateInputMethod(): InputMethod = inputMethod

    override fun onServiceConnected() {
        super.onServiceConnected()
        tracker.serviceConnected()
        // Focus + keyboard are (re)initialized on connect and on every window
        // change (§2.3), so a bubble decision is never made from a stale editor.
        refreshFocusedEditorAndKeyboard()

        // Push eligibility to the runtime on every change so the bubble tracks focus.
        scope.launch {
            tracker.eligibility.collect { pushEligibility() }
        }
        // 0.5.5: the kill-switch setting is re-read from disk every couple of
        // seconds instead of through the in-process DataStore flow — the flow
        // only sees writes made in THIS process, so a main-process toggle (the
        // Settings switch) left cachedAppEnabled permanently stale and the
        // bubble missing until a reboot. The poller observes every toggle
        // within ~APP_ENABLED_POLL_MS.
        scope.launch { runAppEnabledPoller() }
        Log.i(TAG, "Accessibility service connected (:accessibility process)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 0.5.4: while the app is disabled the accessibility process is fully
        // inert — no focus/keyboard tracking, no eligibility, nothing.
        if (!cachedAppEnabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> tracker.onViewFocused(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> tracker.refreshFromRoot(rootInActiveWindow)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> tracker.refreshFromRoot(rootInActiveWindow)
            // TYPE_WINDOWS_CHANGED refreshes BOTH keyboard visibility and the
            // focused-editor state, matching Wispr's observed event pipeline (§4.4).
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshFocusedEditorAndKeyboard()
            else -> Unit
        }
    }

    override fun onInterrupt() {
        // No-op: cancellation of an in-flight session is handled by higher layers.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runtimeMessenger = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        insertionJob?.cancel()
        try {
            unbindService(runtimeConnection)
        } catch (_: Throwable) {
            // never bound
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Runtime binding / eligibility push
    // ------------------------------------------------------------------

    private fun bindToRuntime() {
        val intent = Intent(this, FlowRuntimeService::class.java)
        try {
            bindService(intent, runtimeConnection, BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind runtime service", t)
        }
    }

    /**
     * 0.5.5: polls the on-disk `app_enabled` value so a kill-switch toggle made
     * in the main process is observed here within ~[APP_ENABLED_POLL_MS] — the
     * in-process DataStore flow could never see the main process's writes. The
     * first pass seeds [cachedAppEnabled] and performs the initial runtime bind
     * (replacing the one-shot bind in [onServiceConnected]).
     */
    private suspend fun runAppEnabledPoller() {
        val settings = SettingsRepository(this@WhisperTypeAccessibilityService)
        val appEnabledFile = File(filesDir, PreferencesFileReader.DATASTORE_RELATIVE_PATH)
        while (true) {
            val enabled = PreferencesFileReader.readBoolean(appEnabledFile, SettingsRepository.KEY_APP_ENABLED)
                ?: settings.appEnabled.first() // fallback: DataStore default
            applyAppEnabled(enabled)
            delay(APP_ENABLED_POLL_MS)
        }
    }

    private fun applyAppEnabled(enabled: Boolean) {
        val changed = !appEnabledInitialized || enabled != cachedAppEnabled
        cachedAppEnabled = enabled
        appEnabledInitialized = true
        tracker.setAppEnabled(enabled)
        if (enabled) {
            if (runtimeMessenger == null) bindToRuntime()
        } else if (changed) {
            runtimeMessenger = null
            try {
                unbindService(runtimeConnection)
            } catch (_: Throwable) {
                // never bound
            }
        }
    }

    private fun pushEligibility() {
        val remote = runtimeMessenger ?: return
        val eligibility = tracker.eligibility.value
        if (!eligibility.eligible) {
            // Phase 3 per-condition diagnostic so a hidden bubble is explainable (§2.3).
            Log.i(TAG, "Bubble hidden; reasons=${EligibilityExplanation.blockingReasons(eligibility)}")
        } else {
            Log.i(TAG, "STAGE: bubble shown (eligible target + keyboard)")
        }
        val m = Message.obtain(null, RuntimeIpc.MSG_ELIGIBILITY).apply {
            data = RuntimeIpc.packEligibility(eligibility)
        }
        try {
            remote.send(m)
        } catch (_: RemoteException) {
            runtimeMessenger = null
        }
    }

    // ------------------------------------------------------------------
    // Insertion (Phase 4 cursor-aware, over IPC)
    // ------------------------------------------------------------------

    private fun onInsertRequest(msg: Message) {
        val text = msg.data?.getString(RuntimeIpc.KEY_INSERT_TEXT) ?: return
        val sessionId = SessionId(msg.data?.getString(RuntimeIpc.KEY_SESSION_ID) ?: SessionId.new().value)
        val replyTo = msg.replyTo ?: run {
            Log.w(TAG, "Insert request carried no reply messenger")
            return
        }
        insertionJob?.cancel()
        insertionJob = scope.launch {
            val target = gateway.captureTarget(sessionId)
            val localizedTarget = target ?: TargetSnapshot(
                sessionId = sessionId,
                packageName = "",
                displayId = 0,
                windowId = -1,
                editorIdentity = "",
                generation = 0L,
                inputTypeMask = 0,
                isSecure = true,
                isUncertain = false,
                selectionStart = null,
                selectionEnd = null,
                capturedAtMillis = System.currentTimeMillis(),
            )
            val result = if (target == null) {
                InsertionResult.Failed(com.whispertype.android.core.model.DictationFailure(
                    code = "insert_target_ineligible",
                    message = "No safe text field is focused. Not a password or secure field.",
                    recoverable = true,
                ))
            } else {
                gateway.insert(localizedTarget, text)
            }
            val reply = Message.obtain(null, RuntimeIpc.MSG_INSERT_RESULT).apply {
                data = RuntimeIpc.packInsertionResult(result, sessionId = sessionId.value)
            }
            try {
                replyTo.send(reply)
            } catch (_: RemoteException) {
                Log.w(TAG, "Could not deliver insertion result")
            }
        }
    }

    // ------------------------------------------------------------------
    // Live focus / keyboard
    // ------------------------------------------------------------------

    private fun refreshFocusedEditorAndKeyboard() {
        tracker.refreshFromRoot(rootInActiveWindow)
        refreshKeyboardVisible()
    }

    @Suppress("DEPRECATION")
    private fun refreshKeyboardVisible() {
        val hasIme = try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "Keyboard window probe: hasIme=$hasIme windows=${windows?.size}")
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

    private companion object {
        const val TAG = "WhisperTypeAccessibility"

        /** Poll cadence for re-reading the on-disk app-enabled kill switch. */
        const val APP_ENABLED_POLL_MS = 2000L
    }
}

/**
 * The accessibility IME surface returned by [onCreateInputMethod] (Phase 3,
 * §2.6). It exposes the current [InputMethod.AccessibilityInputConnection] used
 * by the gateway for cursor-aware `commitText()` insertion.
 */
class WhisperTypeInputMethod(service: AccessibilityService) : InputMethod(service)
