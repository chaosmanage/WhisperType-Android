package com.whispertype.android.platform.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.whispertype.android.audio.AudioCapture
import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.AudioStartResult
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.gemini.GeminiSessionConfig
import com.whispertype.android.platform.gemini.GeminiSessionFactory
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The main-process foreground service that owns the overlay runtime: overlay
 * lifecycle, session state, and the Gemini Live connection. It implements the
 * stable Compose owners ([OverlayOwners]) and hosts the one persistent overlay.
 *
 * Live dictation orchestration is delegated to a host-testable
 * [DictationCoordinator]; this service is its Android-facing [DictationHost]
 * (IPC insertion, key/settings resolution, microphone/foreground capture,
 * overlay state publication). The static-insertion fallback (no API key) stays
 * here for the keyless field-matrix gates.
 */
class FlowRuntimeService : Service(), OverlayOwners, DictationHost {

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

    // Context-dependent; lazy so they initialize on first use (in onCreate),
    // never during the Service constructor when the base Context is unattached.
    private val keyProvider: KeyProvider by lazy { KeystoreKeyProvider(this) }
    private val settings: SettingsProvider by lazy { SettingsRepository(this) }

    /** Live-session orchestration (Release C). */
    private val coordinator = DictationCoordinator(scope, this)

    /** Session id of an in-flight static-insertion fallback (keyless installs). */
    private var staticSessionId: SessionId? = null

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
                    if (b != null) onInsertionResult(
                        SessionId(b.getString(RuntimeIpc.KEY_SESSION_ID) ?: return@handleMessage),
                        RuntimeIpc.unpackInsertionResult(b),
                    )
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
        // Start as a special-use FGS (no runtime permission required) so the
        // overlay service can run before RECORD_AUDIO is granted. The service
        // is promoted to the microphone type only during dictation, once the
        // permission is confirmed (see startCapture).
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            OverlayIntent.START_DICTATION -> startDictation()
            OverlayIntent.STOP -> coordinator.stop()
            OverlayIntent.CANCEL -> coordinator.cancel()
            OverlayIntent.COPY, OverlayIntent.DISMISS -> Unit
        }
    }

    /**
     * Phase 6 routing: a configured API key selects the live Gemini loop; without
     * one the service falls back to the static insertion path so the Phase 3/4
     * field-matrix gates stay exercisable on a keyless install.
     */
    private fun startDictation() {
        if (keyProvider.hasKey()) coordinator.start() else startStaticInsertion()
    }

    /**
     * Static-insertion fallback (Phases 1-4): ask the accessibility process to
     * capture the focused target and commit the static test text exactly once.
     */
    private fun startStaticInsertion() {
        val reply = a11yReply ?: run {
            failWithStatic(SessionId.new(), runtimeNoAccessibility())
            return
        }
        val sessionId = SessionId.new()
        staticSessionId = sessionId
        _sessionState.value = DictationState.Starting(sessionId, DictationCoordinator.EMPTY_TARGET(sessionId))
        if (!sendInsert(sessionId, STATIC_TEST_TEXT, reply)) {
            staticSessionId = null
            failWithStatic(sessionId, runtimeNoAccessibility())
        }
    }

    // ------------------------------------------------------------------
    // DictationHost
    // ------------------------------------------------------------------

    override fun publish(state: DictationState) {
        _sessionState.value = state
    }

    override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve {
        metrics.mark(MutableSessionMetrics.Event.KeyLoadStarted)
        val key = keyProvider.provideKey()
        metrics.mark(MutableSessionMetrics.Event.KeyLoaded)
        if (key.isNullOrEmpty()) {
            return SessionResolve.Failed(
                DictationFailure(
                    code = "runtime_no_api_key",
                    message = "Add your Gemini API key in Settings first.",
                    recoverable = true,
                ),
            )
        }
        val language = settings.speechMode.first()
        val model = settings.modelOverride.first()
            ?.takeIf { it.isNotBlank() }
            ?: GeminiSessionFactory.DEFAULT_MODEL
        metrics.mark(MutableSessionMetrics.Event.SettingsReady)
        metrics.mark(MutableSessionMetrics.Event.SocketCreated)
        val session = GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = model,
                language = language,
                // Release B production protocol: manual activity signaling
                // (automaticActivityDetection disabled by default) and no
                // text prime, no systemInstruction, no output transcription.
                // The dictation source is inputTranscription only.
            ),
            metrics = metrics,
        )
        return SessionResolve.Ok(SessionResolution(session = session, language = language))
    }

    override fun startCapture(metrics: MutableSessionMetrics): CaptureStart {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return CaptureStart.Failed(
                DictationFailure(
                    code = "runtime_mic_permission",
                    message = "Microphone permission was revoked.",
                    recoverable = true,
                ),
            )
        }
        // Promote to microphone foreground mode before AudioRecord, only now that
        // RECORD_AUDIO is confirmed granted. specialUse stays in the type set so
        // the overlay service keeps running after dictation.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        metrics.mark(MutableSessionMetrics.Event.CaptureStartRequested)
        val cap = AudioCapture()
        return when (val start = cap.start()) {
            is AudioStartResult.Failed -> CaptureStart.Failed(start.failure)
            AudioStartResult.Started -> {
                metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
                CaptureStart.Started(cap)
            }
        }
    }

    override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
        val reply = a11yReply ?: return false
        return sendInsert(sessionId, text, reply)
    }

    // ------------------------------------------------------------------
    // Shared IPC + failure helpers
    // ------------------------------------------------------------------

    /** Returns false when the insert request could not be handed to accessibility. */
    private fun sendInsert(sessionId: SessionId, text: String, reply: Messenger): Boolean {
        val m = Message.obtain(null, RuntimeIpc.MSG_INSERT).apply {
            data = Bundle().apply {
                putString(RuntimeIpc.KEY_SESSION_ID, sessionId.value)
                putString(RuntimeIpc.KEY_INSERT_TEXT, text)
            }
            replyTo = incomingMessenger
        }
        return try {
            reply.send(m)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not deliver insert request: ${e.message}")
            false
        }
    }

    private fun onInsertionResult(sessionId: SessionId, result: InsertionResult) {
        if (coordinator.isActive) {
            coordinator.onInsertionResult(sessionId, result)
            return
        }
        if (staticSessionId == sessionId) {
            staticSessionId = null
            Log.i(TAG, "Static insertion result=$result")
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
    }

    private fun failWithStatic(sessionId: SessionId, failure: DictationFailure) {
        _sessionState.value = DictationState.Error(sessionId, failure)
        scope.launch {
            delay(RETURN_TO_IDLE_MS)
            resetToIdle()
        }
    }

    private fun runtimeNoAccessibility(): DictationFailure = DictationFailure(
        code = "runtime_no_accessibility",
        message = "Accessibility is not connected yet.",
        recoverable = true,
    )

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
    // Foreground notification
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
    }
}
