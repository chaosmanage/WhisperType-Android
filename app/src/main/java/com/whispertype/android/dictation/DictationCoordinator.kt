package com.whispertype.android.dictation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.accessibility.InsertionOutcome
import com.whispertype.android.accessibility.TargetToken
import com.whispertype.android.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.audio.AudioCapture
import com.whispertype.android.audio.AudioQueue
import com.whispertype.android.audio.WaveformMeter
import com.whispertype.android.diagnostics.DiagnosticsExporter
import com.whispertype.android.gemini.DefaultGeminiLiveClient
import com.whispertype.android.gemini.GeminiEvent
import com.whispertype.android.gemini.GeminiLiveConnection
import com.whispertype.android.gemini.GeminiSessionConfig
import com.whispertype.android.gemini.LanguageMode
import com.whispertype.android.security.SecretStore
import com.whispertype.android.validation.CandidateSelection
import com.whispertype.android.validation.CandidateSelector
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates one recording session: microphone capture, Gemini Live streaming,
 * finalization, insertion, and the copy fallback (Implementation Plan §12-§14).
 * Exposed to the accessibility service and overlay as the [DictationBridge].
 *
 * Thread-safety: all [state] writes happen on the session coroutine (the injected
 * [scope], Dispatchers.Default in production), except [begin], [stop], [cancel],
 * [copyResult], [dismissCopy] and [refreshReadiness] which are invoked from the
 * UI or service thread. [MutableStateFlow] is thread-safe; races between session
 * bookkeeping and those entry points (e.g. cancel racing the ticker's finalize)
 * are benign for v0.1 and resolved in favor of the last writer.
 */
class DictationCoordinator(
    private val context: Context,
    private val apiKeyProvider: suspend () -> String? = { SecretStore(context).getApiKey().getOrNull() },
    private val inserter: suspend (TargetToken, String) -> InsertionOutcome? = { token, text ->
        insertWithRetry(token, text)
    },
    private val copier: (String) -> Boolean = { WhisperTypeAccessibilityService.shared?.copyFallback(it) == true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clientFactory: suspend (sessionId: String, apiKey: String, config: GeminiSessionConfig) -> GeminiLiveConnection =
        { sid, key, cfg -> DefaultGeminiLiveClient(scope).connect(sid, key, cfg) },
    private val captureFactory: () -> AudioCapture = { AudioCapture() },
    private val foregroundServiceLauncher: (sessionId: String) -> Unit = { sessionId ->
        ContextCompat.startForegroundService(
            context,
            Intent(context, DictationForegroundService::class.java)
                .putExtra(DictationForegroundService.EXTRA_SESSION_ID, sessionId),
        )
    },
    private val modeProvider: suspend () -> LanguageMode = {
        runCatching { WhisperTypeApplication.instance.settingsRepository.speechMode.first() }
            .getOrNull() ?: LanguageMode.ENGLISH
    },
    private val modelIdProvider: suspend () -> String = {
        runCatching { WhisperTypeApplication.instance.settingsRepository.geminiModelId.first() }
            .getOrNull() ?: GeminiSessionConfig.DEFAULT_MODEL_ID
    },
    private val idleStateProvider: () -> DictationState = {
        if (WhisperTypeAccessibilityService.shared?.isUsable() == true) {
            DictationState.DockedReady
        } else {
            DictationState.Unavailable
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) : DictationBridge {

    private val mutableState = MutableStateFlow<DictationState>(DictationState.Unavailable)

    override val state: StateFlow<DictationState>
        get() = mutableState

    @Volatile
    private var session: DictationSession? = null

    @Volatile
    private var currentConn: GeminiLiveConnection? = null

    @Volatile
    private var currentCapture: AudioCapture? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var lastChunkAtMillis = 0L

    private var job: Job? = null
    private var tickerJob: Job? = null
    private var collectorJob: Job? = null
    private var sessionEndChannel = Channel<GeminiEvent.SessionEnd>(Channel.BUFFERED)

    companion object {
        const val MAX_RECORDING_DURATION_MS = 300_000L
        private const val TICK_INTERVAL_MILLIS = 50L
        private const val MIC_LOST_TIMEOUT_MILLIS = 3_000L
        private const val AUTO_DISMISS_DELAY_MILLIS = 900L
    }

    override fun begin(target: TargetToken): Boolean {
        if (session != null) return false
        if (WhisperTypeAccessibilityService.shared?.isUsable() != true) return false
        val sessionId = UUID.randomUUID().toString()
        val s = DictationSession(
            sessionId = sessionId,
            target = target,
            config = GeminiSessionConfig(languageMode = LanguageMode.ENGLISH),
            createdAtMillis = clock(),
        )
        session = s
        mutableState.value = DictationState.Starting(sessionId, target)
        try {
            foregroundServiceLauncher(sessionId)
        } catch (e: Exception) {
            mutableState.value = DictationState.Error(
                sessionId,
                DictationFailure("FGS_START_DENIED", "Cannot start recording service", true),
            )
            session = null
            return false
        }
        job = scope.launch { runSession(s) }
        return true
    }

    override fun stop() {
        val s = session ?: return
        if (mutableState.value is DictationState.Listening || mutableState.value is DictationState.Starting) {
            stopRequested = true
        }
    }

    override fun cancel() {
        val s = session ?: return
        job?.cancel()
        job = null
        tickerJob?.cancel()
        tickerJob = null
        collectorJob?.cancel()
        collectorJob = null
        currentConn?.close()
        currentConn = null
        currentCapture?.stop()
        currentCapture = null
        s.savedResult = null
        s.resultConsumed = false
        mutableState.value = DictationState.Cancelled
        session = null
        DictationForegroundService.instance?.stopSelf()
    }

    override fun copyResult() {
        val s = session ?: return
        if (mutableState.value !is DictationState.CopyAvailable) return
        val text = s.savedResult ?: return
        if (!s.resultConsumed && copier(text)) {
            s.resultConsumed = true
            s.savedResult = null
        }
    }

    override fun dismissCopy() {
        if (mutableState.value is DictationState.CopyAvailable) {
            mutableState.value = idleStateProvider()
            session = null
        }
    }

    /** Re-evaluates the idle state after accessibility readiness changes. */
    override fun refreshReadiness() {
        if (session == null &&
            mutableState.value !is DictationState.Starting &&
            mutableState.value !is DictationState.Listening &&
            mutableState.value !is DictationState.Finalizing &&
            mutableState.value !is DictationState.Inserting
        ) {
            mutableState.value = idleStateProvider()
        }
    }

    private suspend fun runSession(s: DictationSession) {
        stopRequested = false
        sessionEndChannel = Channel(Channel.BUFFERED)
        val apiKey = apiKeyProvider()
        if (apiKey == null) {
            abortWith(s, DictationFailure("API_KEY_MISSING", "Add your Gemini API key in Settings", true))
            return
        }
        val cfg = s.config.copy(
            languageMode = modeProvider(),
            modelId = modelIdProvider(),
        )
        s.config = cfg
        val conn = try {
            clientFactory(s.sessionId, apiKey, cfg)
        } catch (e: Exception) {
            abortWith(s, DictationFailure("CONNECT_FAILED", "Could not reach Gemini", true))
            return
        }
        currentConn = conn
        val capture = captureFactory()
        currentCapture = capture
        val queue = AudioQueue()
        val meter = WaveformMeter()
        val startResult = capture.start { chunk ->
            lastChunkAtMillis = clock()
            s.diagnostics.recordChunk()
            meter.push(chunk)
            scope.launch {
                if (!queue.put(chunk)) {
                    s.diagnostics.recordDrop()
                    s.diagnostics.markHighWater(queue.size())
                }
            }
            conn.sendAudio(chunk)
        }
        when (startResult) {
            is AudioCapture.AudioStartResult.Failed -> {
                abortWith(s, DictationFailure(startResult.code, startResult.message, startResult.recoverable))
                return
            }
            AudioCapture.AudioStartResult.Started -> Unit
        }
        s.diagnostics.markCaptureStart()
        s.timing = s.timing.copy(captureStartedAtMillis = clock())
        mutableState.value = DictationState.Listening(s.sessionId, 0L, 0f)

        coroutineScope {
            tickerJob = launch { runTicker(s, conn, capture, queue, meter) }
            collectorJob = launch { runCollector(s, conn) }
        }
    }

    private suspend fun runTicker(
        s: DictationSession,
        conn: GeminiLiveConnection,
        capture: AudioCapture,
        queue: AudioQueue,
        meter: WaveformMeter,
    ) {
        while (true) {
            delay(TICK_INTERVAL_MILLIS)
            val st = mutableState.value
            if (st !is DictationState.Listening) continue
            if (stopRequested) {
                stopRequested = false
                doFinalize(s, conn, capture, queue, meter)
                return
            }
            val elapsed = clock() - s.createdAtMillis
            if (elapsed >= MAX_RECORDING_DURATION_MS) {
                doFinalize(s, conn, capture, queue, meter)
                return
            }
            if (clock() - lastChunkAtMillis > MIC_LOST_TIMEOUT_MILLIS) {
                abortWith(s, DictationFailure("MIC_LOST", "Microphone stopped responding", true))
                return
            }
            mutableState.value = DictationState.Listening(s.sessionId, elapsed, meter.lastAmplitude())
        }
    }

    private suspend fun runCollector(s: DictationSession, conn: GeminiLiveConnection) {
        conn.events.collect { event ->
            when (event) {
                is GeminiEvent.SessionEnd -> sessionEndChannel.send(event)
                is GeminiEvent.Failed -> {
                    if (mutableState.value is DictationState.Listening ||
                        mutableState.value is DictationState.Finalizing
                    ) {
                        abortWith(
                            s,
                            DictationFailure(event.failure.code, event.failure.message, event.failure.recoverable),
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun doFinalize(
        s: DictationSession,
        conn: GeminiLiveConnection,
        capture: AudioCapture,
        queue: AudioQueue,
        meter: WaveformMeter,
    ) {
        s.timing = s.timing.copy(captureStoppedAtMillis = clock())
        s.diagnostics.markCaptureStop()
        capture.stop()
        s.diagnostics.markDrainStart()
        s.timing = s.timing.copy(drainStartedAtMillis = clock())
        // The bounded queue is a backpressure/overflow meter only. Every chunk
        // is already sent once by the capture callback (runSession); draining
        // now only clears the buffer and records its final metrics. Re-sending
        // the drained chunks here would duplicate the tail audio to Gemini,
        // violating "never resend" (§13/§12: single flush, exactly-once send).
        queue.drain()
        s.diagnostics.markDrainFinish()
        s.timing = s.timing.copy(drainFinishedAtMillis = clock())
        conn.sendActivityEnd()
        s.diagnostics.markActivityEndSent()
        s.diagnostics.markEndBoundary(clock())
        s.timing = s.timing.copy(activityEndSentAtMillis = clock())

        val ended = withTimeoutOrNull(s.config.activityEndTimeoutMillis) { sessionEndChannel.receive() }
            ?: run {
                abortWith(s, DictationFailure("TIMEOUT_WAITING_RESULT", "Timed out waiting for the transcript", false))
                return
            }
        s.timing = s.timing.copy(finalResultAtMillis = clock())

        when (val selection = CandidateSelector.select(ended.finalCleaned, ended.finalRaw, s.config.languageMode)) {
            is CandidateSelection.Selected -> {
                mutableState.value = DictationState.Inserting(s.sessionId)
                s.timing = s.timing.copy(insertedAtMillis = clock())
                when (val outcome = inserter(s.target, selection.text)) {
                    is InsertionOutcome.Success -> {
                        s.resultConsumed = true
                        s.savedResult = null
                        mutableState.value = DictationState.Success(s.sessionId, selection.text.length)
                        DiagnosticsExporter.record("dictation.inserted", s.timing.totalDurationMillis)
                        cleanupSessionResources()
                        session = null
                        scope.launch {
                            delay(AUTO_DISMISS_DELAY_MILLIS)
                            if (mutableState.value is DictationState.Success) {
                                mutableState.value = idleStateProvider()
                            }
                        }
                    }
                    is InsertionOutcome.Failure -> {
                        s.savedResult = selection.text
                        mutableState.value = DictationState.CopyAvailable(s.sessionId, selection.text)
                        cleanupSessionResources()
                    }
                    null -> {
                        s.savedResult = selection.text
                        mutableState.value = DictationState.CopyAvailable(s.sessionId, selection.text)
                        cleanupSessionResources()
                    }
                }
            }
            is CandidateSelection.Rejected -> {
                abortWith(s, selection.failure)
            }
        }
    }

    private fun abortWith(s: DictationSession, failure: DictationFailure) {
        mutableState.value = DictationState.Error(s.sessionId, failure)
        cleanupSessionResources()
        session = null
    }

    private fun cleanupSessionResources() {
        tickerJob?.cancel()
        tickerJob = null
        collectorJob?.cancel()
        collectorJob = null
        currentConn?.close()
        currentConn = null
        currentCapture?.stop()
        currentCapture = null
        DictationForegroundService.instance?.stopSelf()
    }
}

private const val INSERT_MAX_ATTEMPTS = 3
private const val INSERT_RETRY_BASE_DELAY_MILLIS = 75L

/**
 * Default inserter: retries recoverable failures before falling back to the copy
 * path, mirroring Wispr's retry-before-fallback insertion mindset.
 */
private suspend fun insertWithRetry(target: TargetToken, text: String): InsertionOutcome {
    var lastFailure: InsertionOutcome = InsertionOutcome.Failure("NO_ATTEMPT", recoverable = true)
    for (attempt in 1..INSERT_MAX_ATTEMPTS) {
        val outcome = WhisperTypeAccessibilityService.shared?.insertText(target, text)
            ?: return InsertionOutcome.Failure("SERVICE_UNAVAILABLE", recoverable = false)
        if (outcome is InsertionOutcome.Success) return outcome
        lastFailure = outcome
        val failure = outcome as InsertionOutcome.Failure
        if (attempt < INSERT_MAX_ATTEMPTS && failure.recoverable) {
            delay(INSERT_RETRY_BASE_DELAY_MILLIS * attempt)
        }
    }
    return lastFailure
}
