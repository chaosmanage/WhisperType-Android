package com.whispertype.android.platform.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
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
import com.whispertype.android.core.dictionary.DictionaryCorrections
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.model.TranscriptionStyle
import com.whispertype.android.data.history.EncryptedHistoryRepository
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.secrets.AndroidKeystoreKeyStore
import com.whispertype.android.data.secrets.FileBlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.gemini.GeminiLiveException
import com.whispertype.android.platform.gemini.GeminiSessionConfig
import com.whispertype.android.platform.gemini.GeminiSessionFactory
import com.whispertype.android.platform.gemini.WarmLiveSessionManager
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.overlay.OverlayOwners
import com.whispertype.android.platform.overlay.PersistentOverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
    private val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /** One long-lived OkHttpClient shared by every session (Release D3). Its
     *  dispatcher/connection pool must never be shut down per session. */
    private val sharedOkHttpClient: OkHttpClient by lazy { GeminiSessionFactory.defaultClient() }

    /** Opt-in encrypted transcript history (0.4.0). */
    private val historyRepository: HistoryRepository by lazy {
        EncryptedHistoryRepository(
            keystore = AndroidKeystoreKeyStore(EncryptedHistoryRepository.DEFAULT_KEY_ALIAS),
            cipher = JavaxAesGcmCipher(),
            blobStore = FileBlobStore(this, EncryptedHistoryRepository.DEFAULT_FILE_NAME),
            retentionDays = { cachedHistoryRetentionDays },
        )
    }

    /** Live-session orchestration (Release C). Auto-stop knobs read the cached
     *  settings value lazily so the product default (60 s) applies immediately. */
    private val coordinator = DictationCoordinator(
        scope,
        this,
        config = DictationCoordinator.Config(
            autoStopSeconds = { cachedAutoStopSeconds.toLong() },
            maxRecordingSeconds = { cachedAutoStopSeconds.toLong() },
        ),
    )

    /** Eligibility-driven warm Live session pool (Release F). */
    private val warmManager = WarmLiveSessionManager(scope, createSession = { createColdSession() })

    // Eager in-memory runtime-settings snapshot (Release D2): collected once at
    // service scope so the tap path never does sequential DataStore first() reads.
    @Volatile
    private var cachedSpeechMode: LanguageMode = LanguageMode.ENGLISH

    @Volatile
    private var cachedHistoryEnabled: Boolean = false

    @Volatile
    private var cachedHistoryRetentionDays: Int = SettingsRepository.DEFAULT_RETENTION_DAYS

    @Volatile
    private var cachedPolishLevel: TranscriptionStyle = TranscriptionStyle.MEDIUM

    @Volatile
    private var cachedAutoStopSeconds: Int = SettingsRepository.DEFAULT_AUTO_STOP_SECONDS

    @Volatile
    private var cachedDictionary: List<DictionaryEntry> = emptyList()

    @Volatile
    private var cachedBubbleX: Float? = null

    @Volatile
    private var cachedBubbleY: Float? = null

    @Volatile
    private var cachedAppEnabled: Boolean = true

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
                        refreshWarmEligibility()
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
        // overlay service can run before RECORD_AUDIO is granted. On API 33 the
        // specialUse bit is inert but accepted (manifest-declared); the service
        // is promoted to include the microphone type during dictation.
        startForeground(NOTIFICATION_ID, buildNotification(), overlayFgsTypes())
        startOverlay()
        // Collect the runtime settings snapshot eagerly (Release D2) so the tap
        // path reads in-memory values instead of blocking on DataStore.
        scope.launch { settings.speechMode.collect { cachedSpeechMode = it } }
        scope.launch { settings.historyEnabled.collect { cachedHistoryEnabled = it } }
        scope.launch { settings.historyRetentionDays.collect { cachedHistoryRetentionDays = it } }
        scope.launch { settings.polishLevel.collect { cachedPolishLevel = it } }
        scope.launch { settings.autoStopSeconds.collect { cachedAutoStopSeconds = it } }
        scope.launch { settings.dictionary.collect { cachedDictionary = it } }
        scope.launch { settings.bubbleX.collect { cachedBubbleX = it } }
        scope.launch { settings.bubbleY.collect { cachedBubbleY = it } }
        // 0.4.2 kill switch: cache the app-enabled setting in this (main)
        // process so the overlay can hide the bubble immediately and reliably.
        scope.launch { settings.appEnabled.collect { cachedAppEnabled = it } }
        Log.i(TAG, "FlowRuntimeService started (main process)")
    }

    private fun startOverlay() {
        val host = PersistentOverlayHost(
            serviceContext = this,
            owners = this,
            sessionState = sessionState,
            eligibility = eligibility,
            bubbleSizeDp = settings.bubbleSizeDp,
            bubbleOpacityPercent = settings.bubbleOpacityPercent,
            miniDotEnabled = settings.miniDotEnabled,
            miniDotDelaySeconds = settings.miniDotDelaySeconds,
            appEnabled = settings.appEnabled,
            onBubblePositionChange = { x, y ->
                scope.launch { settings.setBubblePosition(x, y) }
            },
        )
        host.setBubblePosition(cachedBubbleX, cachedBubbleY)
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
            OverlayIntent.RETRY -> coordinator.retry()
            OverlayIntent.DISMISS -> coordinator.dismiss()
            OverlayIntent.COPY -> Unit
        }
    }

    /**
     * Phase 6 routing: every accepted tap runs the live Gemini loop. A missing
     * or unreadable API key surfaces the configured no-key failure from
     * [resolveSession]; there is no separate hasKey-then-provideKey tap path
     * (Release D1).
     */
    private fun startDictation() {
        coordinator.start()
    }

    // ------------------------------------------------------------------
    // DictationHost
    // ------------------------------------------------------------------

    override fun publish(state: DictationState) {
        _sessionState.value = state
        // Dictation activity changes warm-prewarm eligibility; re-evaluate.
        refreshWarmEligibility()
    }

    override suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve {
        metrics.mark(MutableSessionMetrics.Event.KeyLoadStarted)
        // Prefer a warm (preconnected) session so the tap skips cold setup.
        val warm = warmManager.claim()
        if (warm != null) {
            metrics.mark(MutableSessionMetrics.Event.KeyLoaded)
            metrics.mark(MutableSessionMetrics.Event.SettingsReady)
            metrics.mark(MutableSessionMetrics.Event.SocketCreated)
            return SessionResolve.Ok(SessionResolution(session = warm, language = cachedSpeechMode, ready = true))
        }
        val key = withContext(Dispatchers.IO) { keyProvider.provideKey() }
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
        val language = cachedSpeechMode
        val model = GeminiSessionFactory.DEFAULT_MODEL
        metrics.mark(MutableSessionMetrics.Event.SettingsReady)
        metrics.mark(MutableSessionMetrics.Event.SocketCreated)
        val session = GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = model,
                language = language,
                systemInstruction = language.liveInstruction(cachedPolishLevel),
                // Release B production protocol: manual activity signaling
                // (automaticActivityDetection disabled by default) and no text
                // prime. The systemInstruction carries the polish level and (for
                // Hinglish) the Latin-script rule.
            ),
            client = sharedOkHttpClient,
            metrics = metrics,
        )
        return SessionResolve.Ok(SessionResolution(session = session, language = language, ready = false))
    }

    /** Cold session construction shared by the live path and the warm pool. */
    private suspend fun createColdSession(): com.whispertype.android.core.contracts.GeminiLiveSession {
        val key = withContext(Dispatchers.IO) { keyProvider.provideKey() }
            ?: throw GeminiLiveException(
                DictationFailure(
                    code = "runtime_no_api_key",
                    message = "Add your Gemini API key in Settings first.",
                    recoverable = true,
                ),
            )
        val language = cachedSpeechMode
        val model = GeminiSessionFactory.DEFAULT_MODEL
        return GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = model,
                language = language,
                systemInstruction = language.liveInstruction(cachedPolishLevel),
            ),
            client = sharedOkHttpClient,
        )
    }

    /** Release F2: prewarm only while every eligibility condition holds. */
    private fun computeWarmEligibility(): Boolean {
        val e = _eligibility.value
        return e.serviceConnected && e.editorFocused && !e.editorSecure && !e.editorUncertain &&
            e.keyboardVisible && e.microphoneGranted && cachedAppEnabled &&
            keyProvider.hasKey() && !coordinator.isActive
    }

    private fun refreshWarmEligibility() {
        warmManager.onEligibilityChanged(computeWarmEligibility())
    }

    override suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart {
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
            dictationFgsTypes(),
        )
        // AudioRecord construction and startRecording run off the main thread
        // (Release D4); failures surface as typed mic-init failures.
        metrics.mark(MutableSessionMetrics.Event.CaptureStartRequested)
        return withContext(Dispatchers.IO) {
            val cap = AudioCapture()
            when (val start = cap.start()) {
                is AudioStartResult.Failed -> CaptureStart.Failed(start.failure)
                AudioStartResult.Started -> {
                    metrics.mark(MutableSessionMetrics.Event.CaptureStarted)
                    CaptureStart.Started(cap)
                }
            }
        }
    }

    override fun sendInsertion(sessionId: SessionId, text: String): Boolean {
        val reply = a11yReply ?: return false
        // Custom-dictionary correction rules (client-side) applied to the final text.
        val corrected = DictionaryCorrections.apply(text, cachedDictionary)
        return sendInsert(sessionId, corrected, reply)
    }

    /**
     * 0.5.0 Hinglish: transliterates Devanagari to Latin by opening a dedicated
     * LIVE session, feeding the text over the realtime text channel, and reading
     * the model's spoken reply (`outputTranscription`) — the same model, never a
     * non-live endpoint. Returns null on any failure.
     */
    override suspend fun transliterateToLatin(sessionId: SessionId, text: String): String? {
        val key = keyProvider.provideKey() ?: return null
        val session = GeminiSessionFactory.create(
            apiKey = key,
            config = GeminiSessionConfig(
                model = GeminiSessionFactory.DEFAULT_MODEL,
                language = cachedSpeechMode,
                systemInstruction = TRANSLITERATION_INSTRUCTION,
            ),
            client = sharedOkHttpClient,
        )
        return try {
            session.awaitReady()
            session.requestEchoFor(text)
        } catch (e: Exception) {
            Log.w(TAG, "Hinglish transliteration failed: ${e.message}")
            null
        } finally {
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?) {
        // Aggregate per-session outcome + stage latencies. Never transcript or audio.
        Log.i(TAG, "SESSION DONE outcome=${state::class.simpleName} ${metrics.summary()}")
        // Opt-in history: record the settled transcript when enabled.
        if (cachedHistoryEnabled && !transcript.isNullOrBlank()) {
            val durationMs = metrics.capturedFrames * 20L // 20 ms per captured chunk
            scope.launch {
                historyRepository.record(
                    HistoryRepository.HistoryEntry(
                        id = "",
                        timestampMillis = System.currentTimeMillis(),
                        text = transcript,
                        language = cachedSpeechMode.name,
                        charCount = transcript.length,
                        outcome = state::class.simpleName ?: "Unknown",
                        durationMs = durationMs,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Foreground-service types (Android 13+)
    // ------------------------------------------------------------------

    /** Persistent overlay FGS type: specialUse (honored API 34+, inert on API 33
     *  where the bit is accepted because it is manifest-declared). */
    @SuppressLint("InlinedApi")
    private fun overlayFgsTypes(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /** Dictation FGS type: specialUse + microphone (RECORD_AUDIO is confirmed
     *  granted before this is used). */
    @SuppressLint("InlinedApi")
    private fun dictationFgsTypes(): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

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
        coordinator.onInsertionResult(sessionId, result)
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

        /** Process-local service-liveness flag for the app UI (set in onCreate/onDestroy). */
        @Volatile
        var isRunning: Boolean = false

        /** 0.5.0 Hinglish: dedicated instruction for the live transliteration turn. */
        const val TRANSLITERATION_INSTRUCTION =
            "You transliterate Hindi text to Roman (Latin) script (Hinglish). " +
                "Always speak the transliteration in Roman/Latin script only, never in " +
                "Devanagari. Output only the transliterated text."
    }
}
