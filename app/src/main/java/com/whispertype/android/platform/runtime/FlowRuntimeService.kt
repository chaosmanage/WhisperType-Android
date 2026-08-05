package com.whispertype.android.platform.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import com.whispertype.android.R
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.overlay.OverlayOwners
import com.whispertype.android.platform.overlay.PersistentOverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The main-process foreground service that owns the overlay runtime (locked
 * decision §3 / §4.1, Wispr FlowService parity): overlay lifecycle, session
 * state, and the Gemini Live connection later. It implements the stable
 * Compose owners ([OverlayOwners]) and hosts the one persistent application
 * overlay. Focus / target capture / insertion are delegated over typed IPC to
 * the separate `:accessibility` process.
 *
 * Static-insertion stage (Phases 1-4): tapping the bubble routes a static test
 * string through the accessibility process which commits it at the cursor via
 * [android.view.inputmethod.InputConnection] semantics; the result is reflected
 * back here so the overlay can render Success / Ambig / Error.
 */
class FlowRuntimeService : Service(), OverlayOwners {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreHolder = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _eligibility = MutableStateFlow(TargetEligibility.Ineligible)
    val eligibility: StateFlow<TargetEligibility> = _eligibility.asStateFlow()

    private val _sessionState = MutableStateFlow<DictationState>(DictationState.Idle)
    val sessionState: StateFlow<DictationState> = _sessionState.asStateFlow()

    private var overlayHost: PersistentOverlayHost? = null

    /** Reply messenger registered by the accessibility process. */
    private var a11yReply: Messenger? = null

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                RuntimeIpc.MSG_REGISTER_REPLY -> {
                    a11yReply = msg.replyTo
                    Log.i(TAG, "Accessibility process registered its reply messenger")
                }
                RuntimeIpc.MSG_ELIGIBILITY -> {
                    val b = msg.data
                    if (b != null) {
                        _eligibility.value = RuntimeIpc.unpackEligibility(b)
                    }
                }
                RuntimeIpc.MSG_INSERT_RESULT -> {
                    val b = msg.data
                    if (b != null) onInsertionResult(RuntimeIpc.unpackInsertionResult(b))
                }
                else -> Log.w(TAG, "Unhandled IPC message ${msg.what}")
            }
        }
    }

    private val incomingMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startOverlay()
        Log.i(TAG, "FlowRuntimeService started (main process)")
    }

    private fun startOverlay() {
        val host = PersistentOverlayHost(
            serviceContext = this,
            owners = this,
            sessionState = sessionState,
            eligibility = eligibility,
        )
        overlayHost = host
        host.attach()
        scope.launch {
            host.intents.collect { intent -> onOverlayIntent(intent) }
        }
    }

    private fun onOverlayIntent(intent: OverlayIntent) {
        when (intent) {
            OverlayIntent.START_DICTATION -> startStaticInsertion()
            OverlayIntent.STOP, OverlayIntent.CANCEL, OverlayIntent.COPY, OverlayIntent.DISMISS -> Unit
        }
    }

    /**
     * Static-insertion stage: ask the accessibility process to capture the
     * focused target and commit the static test text exactly once at the cursor.
     */
    private fun startStaticInsertion() {
        val reply = a11yReply ?: run {
            _sessionState.value = DictationState.Error(
                SessionId.new(),
                DictationFailure(
                    code = "runtime_no_accessibility",
                    message = "Accessibility is not connected yet.",
                    recoverable = true,
                ),
            )
            scope.launch { delay(RETURN_TO_IDLE_MS); resetToIdle() }
            return
        }
        val sessionId = SessionId.new()
        _sessionState.value = DictationState.Starting(sessionId, EMPTY_TARGET(sessionId))
        val m = Message.obtain(null, RuntimeIpc.MSG_INSERT).apply {
            data = Bundle().apply {
                putString(RuntimeIpc.KEY_SESSION_ID, sessionId.value)
                putString(RuntimeIpc.KEY_INSERT_TEXT, STATIC_TEST_TEXT)
            }
            replyTo = incomingMessenger
        }
        try {
            reply.send(m)
        } catch (e: RemoteException) {
            _sessionState.value = DictationState.Error(sessionId, DictationFailure(
                code = "runtime_ipc_failed",
                message = "Could not reach the accessibility service.",
                recoverable = true,
            ))
            scope.launch { delay(RETURN_TO_IDLE_MS); resetToIdle() }
        }
    }

    private fun onInsertionResult(result: InsertionResult) {
        val current = _sessionState.value
        val sessionId = (current as? DictationState.Starting)?.sessionId ?: SessionId.new()
        _sessionState.value = when (result) {
            InsertionResult.Inserted -> DictationState.Success(sessionId)
            InsertionResult.Ambiguous -> DictationState.Error(sessionId, DictationFailure(
                code = "insert_ambiguous",
                message = "Could not confirm the text was inserted. Use Copy to grab it.",
                recoverable = true,
                retryAllowed = false,
            ))
            is InsertionResult.Failed -> DictationState.Error(sessionId, result.failure)
        }
        scope.launch {
            delay(RETURN_TO_IDLE_MS)
            resetToIdle()
        }
    }

    private suspend fun resetToIdle() {
        if (_sessionState.value is DictationState.Idle) return
        _sessionState.value = DictationState.Idle
    }

    // ------------------------------------------------------------------
    // OverlayOwners (lifecycle / saved-state / view-model store)
    // ------------------------------------------------------------------

    override fun startOwners() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun stopOwners() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onDestroy() {
        isRunning = false
        overlayHost?.detach()
        overlayHost = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Foreground notification (Phase 6 promotes this to microphone mode)
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.mic_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.mic_notification_title))
            .setContentText(getString(R.string.mic_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "FlowRuntimeService"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "whispertype_runtime"
        const val STATIC_TEST_TEXT = "WhisperType static insertion test"
        const val RETURN_TO_IDLE_MS = 1200L

        /** Process-local service-liveness flag for the app UI (set in onCreate/onDestroy). */
        @Volatile
        var isRunning: Boolean = false

        /** Placeholder target used while the accessibility process resolves the real one. */
        fun EMPTY_TARGET(sessionId: SessionId): com.whispertype.android.core.model.TargetSnapshot =
            com.whispertype.android.core.model.TargetSnapshot(
                sessionId = sessionId,
                packageName = "",
                displayId = 0,
                windowId = -1,
                editorIdentity = "",
                generation = 0L,
                inputTypeMask = 0,
                isSecure = false,
                isUncertain = false,
                selectionStart = null,
                selectionEnd = null,
                capturedAtMillis = 0L,
            )
    }
}
