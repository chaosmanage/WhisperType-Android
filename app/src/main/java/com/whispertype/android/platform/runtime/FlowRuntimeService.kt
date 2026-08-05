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
import com.whispertype.android.audio.AudioStartResult
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.OverlayIntent
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.TargetEligibility
import com.whispertype.android.core.transcript.TranscriptSelection
import com.whispertype.android.core.transcript.TranscriptSelector
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.gemini.GeminiLiveException
import com.whispertype.android.platform.gemini.GeminiSessionConfig
import com.whispertype.android.platform.gemini.GeminiSessionFactory
import com.whispertype.android.platform.ipc.RuntimeIpc
import com.whispertype.android.platform.overlay.OverlayOwners
import com.whispertype.android.platform.overlay.PersistentOverlayHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // Context-dependent; lazy so they initialize on first use (in onCreate),
    // never during the Service constructor when the base Context is unattached.
    private val keyProvider: KeyProvider by lazy { KeystoreKeyProvider(this) }
    private val settings: SettingsProvider by lazy { SettingsRepository(this) }
    private val transcriptSelector = TranscriptSelector()

    /** Live Gemini session, capture, and their jobs while a dictation session is active. */
    private var liveSession: GeminiLiveSession? = null
    private var liveCapture: AudioCapture? = null
    private var liveAudioJob: Job? = null
    private var liveSessionJob: Job? = null
    private var amplitudeJob: Job? = null
    private val liveCandidates = mutableListOf<ResultCandidate>()

    /** Session-local monotonic diagnostics for the active dictation (Release A). */
    private var liveMetrics: MutableSessionMetrics? = null

    /** Peak input amplitude (0..1) of the current capture, for mic diagnostics. */
    @Volatile
    private var peakAmplitude = 0f

    /** Number of 20 ms audio chunks accepted by the session this turn. */
    @Volatile
    private var chunksSent = 0L

    /** Peak RMS of the actual PCM bytes handed to the session this turn (0..32768). */
    @Volatile
    private var peakChunkRms = 0.0

    /**
     * Pending delayed selection after TurnComplete. inputTranscription is a
     * separate serverContent message with no guaranteed ordering and may trail
     * turnComplete, so an empty-candidate turnComplete waits a short grace
     * window before selecting/failing.
     */
    private var pendingSelection: Job? = null

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
        // Start as a special-use FGS (no runtime permission required) so the
        // overlay service can run before RECORD_AUDIO is granted. The service
        // is promoted to the microphone type only during dictation, once the
        // permission is confirmed (see runLiveSession).
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
            OverlayIntent.STOP -> stopDictation()
            OverlayIntent.CANCEL -> cancelDictation()
            OverlayIntent.COPY, OverlayIntent.DISMISS -> Unit
        }
    }

    /**
     * Phase 6 routing: a configured API key selects the live Gemini loop; without
     * one the service falls back to the static insertion path so the Phase 3/4
     * field-matrix gates stay exercisable on a keyless install.
     */
    private fun startDictation() {
        if (keyProvider.hasKey()) startLiveDictation() else startStaticInsertion()
    }

    /**
     * Static-insertion fallback (Phases 1-4): ask the accessibility process to
     * capture the focused target and commit the static test text exactly once.
     */
    private fun startStaticInsertion() {
        val reply = a11yReply ?: run {
            failWith(SessionId.new(), runtimeNoAccessibility())
            return
        }
        val sessionId = SessionId.new()
        _sessionState.value = DictationState.Starting(sessionId, EMPTY_TARGET(sessionId))
        sendInsert(sessionId, STATIC_TEST_TEXT, reply)
    }

    // ------------------------------------------------------------------
    // Phase 6: live Gemini dictation loop
    // ------------------------------------------------------------------

    /**
     * Live loop (PRD §6): bubble tap creates a Gemini Live session, awaits its
     * setup acknowledgement, promotes the service to microphone foreground mode
     * before [AudioRecord], then streams exact 16 kHz PCM16 frames. STOP drains
     * accepted audio and sends one activity-end boundary; the resulting raw /
     * cleaned candidates are validated and the selection goes through the proven
     * insertion transaction.
     */
    private fun startLiveDictation() {
        val sessionId = SessionId.new()
        Log.i(TAG, "STAGE: dictation requested session=$sessionId")
        liveCandidates.clear()
        peakAmplitude = 0f
        chunksSent = 0L
        peakChunkRms = 0.0
        pendingSelection?.cancel()
        pendingSelection = null
        val metrics = MutableSessionMetrics(sessionId)
        liveMetrics = metrics
        metrics.mark(MutableSessionMetrics.Event.Tap)
        _sessionState.value = DictationState.Starting(sessionId, EMPTY_TARGET(sessionId))
        liveSessionJob = scope.launch {
            metrics.mark(MutableSessionMetrics.Event.KeyLoadStarted)
            val key = keyProvider.provideKey()
            metrics.mark(MutableSessionMetrics.Event.KeyLoaded)
            if (key.isNullOrEmpty()) {
                failWith(
                    sessionId,
                    DictationFailure(
                        code = "runtime_no_api_key",
                        message = "Add your Gemini API key in Settings first.",
                        recoverable = true,
                    ),
                )
                return@launch
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
                    // No systemInstruction: a systemInstruction suppresses the
                    // server's outputTranscription delivery (verified 2026-08-05 on
                    // the live endpoint). outputAudioTranscription transcribes the
                    // model's spoken reply, which is the dictation echo source.
                ),
                metrics = metrics,
            )
            liveSession = session
            runLiveSession(sessionId, session)
        }
    }

    private suspend fun runLiveSession(sessionId: SessionId, session: GeminiLiveSession) {
        var capture: AudioCapture? = null
        var audioJob: Job? = null
        try {
            try {
                session.awaitReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = (e as? GeminiLiveException)?.failure
                    ?: DictationFailure(
                        code = "gemini_setup",
                        message = "Could not reach Gemini. Check your network and API key.",
                        recoverable = true,
                    )
                failWith(sessionId, failure)
                return
            }
            if (_sessionState.value !is DictationState.Starting) return

            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                failWith(
                    sessionId,
                    DictationFailure(
                        code = "runtime_mic_permission",
                        message = "Microphone permission was revoked.",
                        recoverable = true,
                    ),
                )
                return
            }

            // Phase 6: promote to microphone foreground mode before AudioRecord,
            // only now that RECORD_AUDIO is confirmed granted. specialUse stays in
            // the type set so the overlay service keeps running after dictation.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )

            // Prime the model as a dictation echo via a client text turn (kept
            // inside the open turn, before any audio). A systemInstruction would
            // suppress outputTranscription; this client turn does not, so the
            // model's spoken echo is transcribed and becomes the dictation.
            session.sendTextTurn(DICTATION_PRIME_TEXT)
            Log.i(TAG, "STAGE: dictation prime sent")

            liveMetrics?.mark(MutableSessionMetrics.Event.CaptureStartRequested)
            val cap = AudioCapture()
            capture = cap
            liveCapture = cap
            when (val start = cap.start()) {
                is AudioStartResult.Failed -> {
                    failWith(sessionId, start.failure)
                    return
                }
                AudioStartResult.Started -> {
                    liveMetrics?.mark(MutableSessionMetrics.Event.CaptureStarted)
                }
            }

            _sessionState.value = DictationState.Listening(sessionId)
            audioJob = scope.launch {
                for (chunk in cap.chunks) {
                    liveMetrics?.capturedFrames = (liveMetrics?.capturedFrames ?: 0L) + 1
                    if (session.sendAudio(chunk) is SendResult.Accepted) {
                        chunksSent++
                        liveMetrics?.acceptedFrames = (liveMetrics?.acceptedFrames ?: 0L) + 1
                        if (liveMetrics?.firstAudioQueuedAt == null) {
                            liveMetrics?.mark(MutableSessionMetrics.Event.FirstAudioQueued)
                        }
                        val rms = pcm16Rms(chunk.pcm16Bytes)
                        if (rms > peakChunkRms) peakChunkRms = rms
                        if (chunksSent == 1L) {
                            Log.i(TAG, "STAGE: first audio chunk sent (mic producing data)")
                        } else if (chunksSent % 50 == 0L) {
                            Log.i(TAG, "audio progress: chunksSent=$chunksSent peakChunkRms=$peakChunkRms peakAmp=$peakAmplitude")
                        }
                    } else {
                        liveMetrics?.rejectedFrames = (liveMetrics?.rejectedFrames ?: 0L) + 1
                    }
                }
            }
            liveAudioJob = audioJob
            // Mic-level diagnostic + live waveform: AudioCapture exposes its own
            // amplitude flow; drive peakAmplitude and the overlay amplitude from
            // it (GeminiEvent.Amplitude is not emitted by the session).
            amplitudeJob = scope.launch {
                cap.amplitude.collect { level ->
                    if (level > peakAmplitude) peakAmplitude = level
                    val cur = _sessionState.value
                    if (cur is DictationState.Listening && cur.sessionId == sessionId) {
                        _sessionState.value = cur.copy(amplitude = level)
                    }
                }
            }

            session.events().collect { event -> onLiveEvent(sessionId, event) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failWith(
                sessionId,
                DictationFailure(
                    code = "gemini_events",
                    message = "The Gemini session stopped unexpectedly.",
                    recoverable = true,
                ),
            )
        } finally {
            audioJob?.cancel()
            amplitudeJob?.cancel()
            capture?.stop()
            liveAudioJob = null
            liveCapture = null
            session.close()
            liveSession = null
        }
    }

    private fun onLiveEvent(sessionId: SessionId, event: GeminiEvent) {
        when (event) {
            GeminiEvent.Ready -> Unit
            is GeminiEvent.Amplitude -> {
                if (event.level > peakAmplitude) peakAmplitude = event.level
                val current = _sessionState.value
                if (current is DictationState.Listening && current.sessionId == sessionId) {
                    _sessionState.value = current.copy(amplitude = event.level)
                }
            }
            is GeminiEvent.TranscriptCandidates -> {
                liveCandidates.addAll(event.candidates)
                Log.i(TAG, "Candidates received: ${event.candidates.size}; first=${event.candidates.first().raw.take(80)}")
            }
            GeminiEvent.TurnComplete -> if (_sessionState.value is DictationState.Finalizing) {
                if (liveCandidates.isEmpty()) {
                    // inputTranscription is a separate serverContent message with no
                    // guaranteed ordering; it can trail turnComplete. Wait a short
                    // grace window before declaring no-transcript.
                    Log.i(TAG, "TurnComplete with empty candidates; waiting ${TRAILING_TRANSCRIPT_GRACE_MS}ms for trailing inputTranscription")
                    pendingSelection?.cancel()
                    pendingSelection = scope.launch {
                        delay(TRAILING_TRANSCRIPT_GRACE_MS)
                        if (_sessionState.value is DictationState.Finalizing) {
                            Log.i(TAG, "Grace elapsed; candidates now ${liveCandidates.size}")
                            selectAndInsert(sessionId)
                        }
                    }
                } else {
                    Log.i(TAG, "TurnComplete with ${liveCandidates.size} candidates")
                    selectAndInsert(sessionId)
                }
            }
            is GeminiEvent.Failed -> {
                failWith(sessionId, event.failure)
                closeLiveSession()
            }
            GeminiEvent.SessionEnd -> if (_sessionState.value !is DictationState.Inserting) {
                failWith(
                    sessionId,
                    DictationFailure(
                        code = "gemini_transport",
                        message = "The Gemini session closed.",
                        recoverable = true,
                    ),
                )
            }
        }
    }

    private fun selectAndInsert(sessionId: SessionId) {
        pendingSelection?.cancel()
        pendingSelection = null
        val selection = transcriptSelector.select(liveCandidates.toList())
        when (selection) {
            is TranscriptSelection.None -> {
                Log.w(TAG, "Selection: NONE (candidates=${liveCandidates.size}, peakAmp=$peakAmplitude)")
                failWith(
                    sessionId,
                    DictationFailure(
                        code = "gemini_no_transcript",
                        message = "No transcript could be recognized. Try again.",
                        recoverable = true,
                    ),
                )
                closeLiveSession()
            }
            is TranscriptSelection.Cleaned, is TranscriptSelection.Raw -> {
                Log.i(TAG, "Selection: ${selection::class.simpleName} text=${selection.text.take(80)}")
                val reply = a11yReply
                if (reply == null) {
                    failWith(sessionId, runtimeNoAccessibility())
                    closeLiveSession()
                    return
                }
                liveMetrics?.mark(MutableSessionMetrics.Event.TranscriptSettled)
                _sessionState.value = DictationState.Inserting(sessionId)
                liveMetrics?.mark(MutableSessionMetrics.Event.InsertionRequested)
                sendInsert(sessionId, selection.text, reply)
                closeLiveSession()
            }
        }
    }

    /** Closes the live session socket; the collect loop then ends and cleans up. */
    private fun closeLiveSession() {
        pendingSelection?.cancel()
        pendingSelection = null
        liveSession?.let { session -> scope.launch { session.close() } }
    }

    /**
     * STOPS the live turn (Listening -> Finalizing): drain any trailing partial
     * frame, close the capture, wait for all accepted audio to be transmitted,
     * then send the single activity-end boundary. Selection + insertion happen
     * when the server's TurnComplete arrives in [onLiveEvent].
     */
    private fun stopDictation() {
        val current = _sessionState.value as? DictationState.Listening ?: return
        val sessionId = current.sessionId
        _sessionState.value = DictationState.Finalizing(sessionId)
        val capture = liveCapture
        val session = liveSession
        val audioJob = liveAudioJob
        liveMetrics?.mark(MutableSessionMetrics.Event.Stop)
        Log.i(TAG, "stopDictation: peakAmp=$peakAmplitude candidates=${liveCandidates.size} chunksSent=$chunksSent peakChunkRms=$peakChunkRms")
        scope.launch {
            capture?.forceRemainingChunk()
            capture?.stop()
            audioJob?.join()
            liveMetrics?.mark(MutableSessionMetrics.Event.CaptureQuiesced)
            session?.endActivity()
            liveMetrics?.mark(MutableSessionMetrics.Event.ActivityEndQueued)
            Log.i(TAG, "endActivity sent")
        }
        // Safety net: if the server never sends turnComplete (empty turn, dropped
        // socket, server quirk), force selection so the panel never sticks on
        // Finalizing. selectAndInsert cancels this job when it runs.
        pendingSelection?.cancel()
        pendingSelection = scope.launch {
            delay(FINALIZE_TIMEOUT_MS)
            if (_sessionState.value is DictationState.Finalizing) {
                Log.w(TAG, "Finalize timeout after ${FINALIZE_TIMEOUT_MS}ms; forcing selection (candidates=${liveCandidates.size})")
                selectAndInsert(sessionId)
            }
        }
    }

    /** CANCELS the live turn (no insertion) and tears down the session. */
    private fun cancelDictation() {
        val current = _sessionState.value
        val sessionId = when (current) {
            is DictationState.Starting -> current.sessionId
            is DictationState.Listening -> current.sessionId
            is DictationState.Finalizing -> current.sessionId
            is DictationState.Inserting -> current.sessionId
            else -> null
        } ?: return
        _sessionState.value = DictationState.Cancelled(sessionId, CancelReason.USER)
        pendingSelection?.cancel()
        pendingSelection = null
        liveSessionJob?.cancel()
        liveCapture?.stop()
        liveAudioJob?.cancel()
        liveSession?.let { scope.launch { it.close() } }
        scope.launch {
            delay(RETURN_TO_IDLE_MS)
            resetToIdle()
        }
    }

    // ------------------------------------------------------------------
    // Shared IPC + failure helpers
    // ------------------------------------------------------------------

    private fun sendInsert(sessionId: SessionId, text: String, reply: Messenger) {
        val m = Message.obtain(null, RuntimeIpc.MSG_INSERT).apply {
            data = Bundle().apply {
                putString(RuntimeIpc.KEY_SESSION_ID, sessionId.value)
                putString(RuntimeIpc.KEY_INSERT_TEXT, text)
            }
            replyTo = incomingMessenger
        }
        try {
            reply.send(m)
        } catch (e: RemoteException) {
            failWith(sessionId, DictationFailure(
                code = "runtime_ipc_failed",
                message = "Could not reach the accessibility service.",
                recoverable = true,
            ))
        }
    }

    private fun failWith(sessionId: SessionId, failure: DictationFailure) {
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

    private fun onInsertionResult(result: InsertionResult) {
        Log.i(TAG, "STAGE: insertion result=$result")
        val metrics = liveMetrics
        metrics?.mark(MutableSessionMetrics.Event.InsertionResult)
        metrics?.let { Log.i(TAG, "SESSION METRICS ${it.summary()}") }
        val current = _sessionState.value
        val sessionId = when (current) {
            is DictationState.Starting -> current.sessionId
            is DictationState.Inserting -> current.sessionId
            else -> SessionId.new()
        }
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

    /** RMS of signed little-endian PCM16 bytes, in [0, 32768]. Aggregate only; never logs audio. */
    private fun pcm16Rms(bytes: ByteArray): Double {
        if (bytes.size < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            i += 2
        }
        return Math.sqrt(sum / (i / 2))
    }

    companion object {
        const val TAG = "FlowRuntimeService"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "whispertype_runtime"
        const val STATIC_TEST_TEXT = "WhisperType static insertion test"
        const val RETURN_TO_IDLE_MS = 1200L
        const val TRAILING_TRANSCRIPT_GRACE_MS = 2000L
        const val FINALIZE_TIMEOUT_MS = 8000L

        /** Client text turn (not a systemInstruction) priming the Live model to
         *  speak the user's dictation verbatim; its outputTranscription becomes
         *  the transcript. */
        const val DICTATION_PRIME_TEXT =
            "You are a verbatim dictation echo. When the user speaks, speak back " +
                "ONLY their exact words, word for word, in the same language. Do not " +
                "greet, do not comment, do not ask questions, do not paraphrase, and " +
                "do not acknowledge this instruction."

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
