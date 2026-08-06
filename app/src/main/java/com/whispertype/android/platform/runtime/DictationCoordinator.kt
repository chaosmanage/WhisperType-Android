package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.PreReadyAudioBuffer
import com.whispertype.android.audio.PreReadyOffer
import com.whispertype.android.core.audio.SessionRecording
import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.CancelReason
import com.whispertype.android.core.model.DictationFailure
import com.whispertype.android.core.model.DictationState
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.InsertionResult
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.ResultCandidate
import com.whispertype.android.core.model.SendResult
import com.whispertype.android.core.model.SessionId
import com.whispertype.android.core.model.SettlePath
import com.whispertype.android.core.model.TargetSnapshot
import com.whispertype.android.core.transcript.RejectionDiagnosis
import com.whispertype.android.core.transcript.RejectionRule
import com.whispertype.android.core.transcript.TranscriptAccumulator
import com.whispertype.android.core.transcript.TranscriptCompleteness
import com.whispertype.android.core.transcript.TranscriptSelection
import com.whispertype.android.core.transcript.TranscriptSelector
import com.whispertype.android.platform.gemini.GeminiLiveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/**
 * Host services the [DictationCoordinator] needs. Implemented by
 * [FlowRuntimeService]; kept to pure interfaces so the coordinator is
 * host-testable on the JVM (no Android types).
 */
interface DictationHost {
    /** Publishes the UI-renderable session state. */
    fun publish(state: DictationState)

    /** Resolves the API key, runtime settings, and constructs the Live session. */
    suspend fun resolveSession(metrics: MutableSessionMetrics): SessionResolve

    /** Verifies mic permission, promotes foreground mode, creates and starts the
     *  capture pipeline. Returns the started pipeline or a typed failure. */
    suspend fun startCapture(metrics: MutableSessionMetrics): CaptureStart

    /** Sends the insert request over IPC; false when accessibility is absent. */
    fun sendInsertion(sessionId: SessionId, text: String): Boolean

    /**
     * 0.4.2 audio-recovery failsafe: re-transcribes the recorded session audio
     * via a non-live transcription call and returns the recovered text, or null
     * when nothing usable could be recovered. Implementations must never throw
     * on failure — return null so the best available live text is inserted.
     */
    suspend fun recoverTranscript(sessionId: SessionId, wav: ByteArray): String?

    /**
     * Per-session aggregate diagnostics at terminal state. Must never contain
     * audio, keys, or full server frames. [transcript] is the settled dictation
     * text when one existed (used for opt-in history), or null otherwise.
     */
    fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics, transcript: String?)
}

/** Outcome of [DictationHost.resolveSession]. */
sealed interface SessionResolve {
    data class Ok(val resolution: SessionResolution) : SessionResolve
    data class Failed(val failure: DictationFailure) : SessionResolve
}

/** A created Live session plus the language it should stamp on candidates.
 *  [ready] is true when the session was already connected (warm claim). */
data class SessionResolution(
    val session: GeminiLiveSession,
    val language: LanguageMode,
    val ready: Boolean = false,
)

/** Outcome of [DictationHost.startCapture]. */
sealed interface CaptureStart {
    data class Started(val capture: AudioPipeline) : CaptureStart
    data class Failed(val failure: DictationFailure) : CaptureStart
}

/**
 * Per-session owner of all live resources, jobs, transcript state, and metrics
 * (Release C1). The runtime keeps exactly one `activeSession` reference instead
 * of parallel service-wide mutable fields, so an older session's callbacks can
 * never act on a newer session: every handler first verifies `active === this`.
 */
private class ActiveLiveSession(
    val sessionId: SessionId,
    val metrics: MutableSessionMetrics,
    val accumulator: TranscriptAccumulator,
    val echoAccumulator: TranscriptAccumulator,
    val recording: SessionRecording,
    var language: LanguageMode = LanguageMode.ENGLISH,
) {
    var session: GeminiLiveSession? = null
    var capture: AudioPipeline? = null
    var sessionJob: Job? = null
    var eventJob: Job? = null
    var readyJob: Job? = null
    var audioJob: Job? = null
    var amplitudeJob: Job? = null
    var captureFailureJob: Job? = null
    var finalizationJob: Job? = null
    var settleJob: Job? = null
    var deadlineJob: Job? = null
    var echoFallbackJob: Job? = null
    var recoveryJob: Job? = null
    var inserted: Boolean = false

    /** The settled dictation text when one existed (opt-in history hook). */
    var settledText: String? = null

    /** Auto-stop watcher (silence + hard cap) while listening. */
    var autoStopJob: Job? = null

    /** Retained even when the server sends it before STOP (Release E7). */
    var turnCompleteSeen: Boolean = false
}

/**
 * Pure, host-testable orchestration of one dictation session (Release C).
 *
 * Responsibilities:
 *  - reject duplicate START synchronously (C2),
 *  - validate session identity before every event/callback (C3),
 *  - compare-and-clear the active holder during cleanup (C4),
 *  - correlate insertion results by session id (C5),
 *  - drain audio through the producer before the completion boundary (C6),
 *  - consume capture failures and tear down (C7).
 *
 * No Android types appear here; [DictationHost] supplies them.
 */
class DictationCoordinator(
    private val scope: CoroutineScope,
    private val host: DictationHost,
    private val selector: TranscriptSelector = TranscriptSelector(),
    private val config: Config = Config(),
    private val metricsFactory: (SessionId) -> MutableSessionMetrics = { MutableSessionMetrics(it) },
) {

    /** Tunable timing knobs (all monotonic delays, host-tested via virtual time). */
    data class Config(
        /** Absolute deadline from STOP (0.4.1): the polished echo for long
         *  dictations can take several seconds, so the cap is generous (~20 s).
         *  The echo-fallback watchdog settles on the fast raw transcript when no
         *  echo arrives at all. */
        val hardDeadlineMs: Long = 20_000,
        /** If no echo (outputTranscription) has arrived this many ms after the
         *  activity end boundary, settle on the fast raw inputTranscription
         *  instead of waiting. Measured from activity end so the raw is always
         *  final (never a provisional mid-activity revision). */
        val echoFallbackWaitMs: Long = 2_000,
        /** Short settle debounce after the last transcript revision / turn
         *  complete. Calibrated above the measured ~90-300 ms inter-delta gaps
         *  of a streaming echo so settlement never lands mid-delta-stream
         *  (0.4.2). */
        val settleDebounceMs: Long = 600,
        /** 0.4.2: the polished echo is only accepted as the dictation source
         *  when it plausibly covers the raw ASR (see [TranscriptCompleteness]). */
        val minEchoRatio: Double = TranscriptCompleteness.DEFAULT_MIN_RATIO,
        /** 0.4.2: speaking-rate assumption for the duration-based completeness
         *  check that triggers the audio-recovery failsafe. */
        val expectedWordsPerSecond: Double = TranscriptCompleteness.DEFAULT_WORDS_PER_SECOND,
        /** 0.4.2: when the settled text covers less than this fraction of the
         *  duration-derived expected words, the recording is re-transcribed. */
        val minExpectedFraction: Double = 0.6,
        /** 0.4.2: hard cap on the audio-recovery REST call so the session can
         *  never stall in the Recovering state; on timeout the best available
         *  live text is inserted instead. */
        val recoveryTimeoutMs: Long = 45_000,
        /** 0.4.2: recording cap for the audio-recovery failsafe. */
        val recordingMaxBytes: Int = SessionRecording.DEFAULT_MAX_BYTES,
        val returnToIdleMs: Long = 1_200,
        val captureShutdownTimeoutMs: Long = 1_500,
        /** Bounded pre-ready PCM frames buffered while a cold session connects (F5). */
        val preReadyMaxFrames: Int = 150,
        /** Auto-stop: stop after this many seconds of silence (0 disables). The
         *  runtime supplies the product default (60 s) via the settings-backed
         *  provider. Disabled by default so host tests keep deterministic time. */
        val autoStopSeconds: () -> Long = { 0L },
        /** Auto-stop: hard recording cap in seconds (0 disables). The runtime
         *  mirrors the same user option here, so the cap and the silence timeout
         *  share the configured value ("both combined"). Kept as a separate knob
         *  so each arm is independently testable and can diverge later. */
        val maxRecordingSeconds: () -> Long = { 0L },
        /** Mic amplitude (0..1) above which the user is considered speaking. */
        val speechAmplitudeThreshold: Float = 0.02f,
    )

    @Volatile
    private var active: ActiveLiveSession? = null

    /** True when a live session is running or finalizing (guards duplicate START). */
    val isActive: Boolean get() = active != null

    /** Test visibility: metrics of the currently active session, or null. */
    internal fun activeMetrics(): MutableSessionMetrics? = active?.metrics

    /** Starts a new dictation session. Returns false (and does nothing) when one
     *  is already active — duplicate START is rejected synchronously. */
    fun start(): Boolean {
        val existing = active
        if (existing != null) return false
        val sessionId = SessionId.new()
        val metrics = metricsFactory(sessionId)
        metrics.mark(MutableSessionMetrics.Event.Tap)
        val holder = ActiveLiveSession(
            sessionId = sessionId,
            metrics = metrics,
            accumulator = TranscriptAccumulator(),
            // The server streams outputTranscription as word-level deltas
            // (verified 0.4.2), so the echo accumulator appends deltas.
            echoAccumulator = TranscriptAccumulator(appendDeltas = true),
            recording = SessionRecording(config.recordingMaxBytes),
        )
        active = holder
        publish(DictationState.Starting(sessionId, EMPTY_TARGET(sessionId)))
        holder.sessionJob = scope.launch { runSession(holder) }
        return true
    }

    /** STOPS the active turn (Listening -> Finalizing) with an orderly producer drain. */
    fun stop() {
        val holder = active ?: return
        if (!isListening(holder)) return
        publish(DictationState.Finalizing(holder.sessionId))
        holder.metrics.mark(MutableSessionMetrics.Event.Stop)
        startFinalization(holder)
    }

    /** CANCELS the active turn without insertion and tears the session down. */
    fun cancel() {
        val holder = active ?: return
        publish(DictationState.Cancelled(holder.sessionId, CancelReason.USER))
        holder.metrics.mark(MutableSessionMetrics.Event.Stop)
        teardown(holder)
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    /** Routes an insertion result; a stale session id is ignored (C5). */
    fun onInsertionResult(sessionId: SessionId, result: InsertionResult) {
        val holder = active ?: return
        if (holder.sessionId != sessionId) return
        if (!holder.inserted) return
        holder.metrics.mark(MutableSessionMetrics.Event.InsertionResult)
        publish(
            when (result) {
                InsertionResult.Inserted -> DictationState.Success(sessionId)
                InsertionResult.Ambiguous -> DictationState.Error(
                    sessionId,
                    DictationFailure(
                        code = "insert_ambiguous",
                        message = "Could not confirm the text was inserted. Use Copy to grab it.",
                        recoverable = true,
                        retryAllowed = false,
                    ),
                )
                is InsertionResult.Failed -> DictationState.Error(sessionId, result.failure)
            },
        )
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    // ------------------------------------------------------------------
    // Session setup
    // ------------------------------------------------------------------

    private suspend fun runSession(holder: ActiveLiveSession) {
        val sessionId = holder.sessionId
        try {
            val resolution = when (val resolved = host.resolveSession(holder.metrics)) {
                is SessionResolve.Failed -> {
                    fail(holder, resolved.failure)
                    return
                }
                is SessionResolve.Ok -> resolved.resolution
            }
            holder.session = resolution.session
            holder.language = resolution.language
            // Event collector installed as soon as the session exists, before
            // awaiting readiness, so setup failures/closure are processed promptly.
            holder.eventJob = scope.launch {
                try {
                    resolution.session.events().collect { event -> onLiveEvent(holder, event) }
                } catch (e: CancellationException) {
                    throw e
                }
            }
            // Release F4: capture starts immediately on the accepted tap, before
            // network readiness; cold-session PCM goes into a bounded pre-ready
            // buffer until the session is ready.
            when (val outcome = host.startCapture(holder.metrics)) {
                is CaptureStart.Failed -> {
                    fail(holder, outcome.failure)
                    return
                }
                is CaptureStart.Started -> {
                    holder.capture = outcome.capture
                    holder.captureFailureJob = scope.launch {
                        outcome.capture.failures.collect { failure -> onCaptureFailure(holder, failure) }
                    }
                }
            }
            if (active !== holder) return
            publish(DictationState.Listening(sessionId, connecting = !resolution.ready))
            val ready = CompletableDeferred<Unit>()
            holder.readyJob = scope.launch { awaitReadyAndStart(holder, ready) }
            holder.audioJob = scope.launch { streamAudio(holder, ready) }
            holder.amplitudeJob = scope.launch { publishAmplitude(holder) }
            holder.autoStopJob = scope.launch { runAutoStop(holder) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(
                holder,
                DictationFailure(
                    code = "gemini_events",
                    message = "The Gemini session stopped unexpectedly.",
                    recoverable = true,
                ),
            )
        }
    }

    /** Awaits the session, opens the activity, then signals the audio sender. */
    private suspend fun awaitReadyAndStart(holder: ActiveLiveSession, ready: CompletableDeferred<Unit>) {
        val session = holder.session ?: return
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
            fail(holder, failure)
            return
        }
        if (active !== holder) return
        when (val start = session.startActivity()) {
            is SendResult.Rejected -> {
                fail(holder, transportFailure(start.reason))
                return
            }
            SendResult.Accepted -> holder.metrics.mark(MutableSessionMetrics.Event.ActivityStartQueued)
        }
        val current = lastPublished
        if (current is DictationState.Listening && current.sessionId == holder.sessionId) {
            publish(current.copy(connecting = false))
        }
        ready.complete(Unit)
    }

    /**
     * One ordered sender coroutine (Release F4): while the session is still
     * connecting, capture chunks go into the bounded pre-ready buffer; when the
     * session becomes ready the buffered frames are drained in strict order and
     * then live frames stream directly. Overflow of the bounded buffer fails with
     * an explicit connection-too-slow failure (F5).
     */
    private suspend fun streamAudio(holder: ActiveLiveSession, ready: CompletableDeferred<Unit>) {
        val session = holder.session ?: return
        val capture = holder.capture ?: return
        val preReady = PreReadyAudioBuffer(config.preReadyMaxFrames)
        var connected = false
        while (true) {
            // Select on both the capture channel and the readiness signal so the
            // drain is not deferred to a later microphone frame.
            val chunk = select<AudioChunk?> {
                capture.chunks.onReceiveCatching { result -> result.getOrNull() }
                if (!connected) ready.onAwait { null }
            }
            if (chunk == null) {
                if (!connected) {
                    // The session became ready while we were blocked: drain the
                    // bounded pre-ready buffer in strict order, then stream live.
                    for (buffered in preReady.drain()) {
                        sendChunk(holder, session, buffered)
                    }
                    connected = true
                    continue
                }
                break // capture channel closed
            }
            holder.metrics.capturedFrames++
            // 0.4.2: retain the full captured audio for the audio-recovery
            // failsafe (bounded; overflow drops the recording entirely).
            holder.recording.append(chunk.pcm16Bytes)
            if (connected) {
                sendChunk(holder, session, chunk)
            } else {
                when (preReady.offer(chunk)) {
                    PreReadyOffer.Accepted -> Unit
                    PreReadyOffer.Overflow -> {
                        fail(holder, connectionTooSlow())
                        return
                    }
                    PreReadyOffer.Closed -> return
                }
            }
        }
        holder.metrics.mark(MutableSessionMetrics.Event.LastAudioQueued)
    }

    private suspend fun sendChunk(
        holder: ActiveLiveSession,
        session: GeminiLiveSession,
        chunk: AudioChunk,
    ) {
        if (session.sendAudio(chunk) is SendResult.Accepted) {
            holder.metrics.acceptedFrames++
            if (holder.metrics.firstAudioQueuedAt == null) {
                holder.metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
            }
        } else {
            holder.metrics.rejectedFrames++
        }
    }

    private suspend fun publishAmplitude(holder: ActiveLiveSession) {
        val capture = holder.capture ?: return
        capture.amplitude.collect { level ->
            // UI publication only applies while this holder is still Listening.
            val state = lastPublished
            if (state is DictationState.Listening && state.sessionId == holder.sessionId) {
                publish(state.copy(amplitude = level))
            }
        }
    }

    /**
     * Auto-stop watcher (0.4.0): a hard recording cap ([Config.maxRecordingSeconds])
     * and a silence auto-stop ([Config.autoStopSeconds]) — whichever fires first —
     * both ending through the same [stop] path as a user STOP. Elapsed time is
     * tracked in check-ticks so virtual-time tests drive it.
     */
    private suspend fun runAutoStop(holder: ActiveLiveSession) {
        val silenceTimeoutMs = config.autoStopSeconds() * 1000L
        val maxTimeoutMs = config.maxRecordingSeconds() * 1000L
        if (silenceTimeoutMs <= 0 && maxTimeoutMs <= 0) return
        val capture = holder.capture ?: return
        var elapsedMs = 0L
        var silenceMs = 0L
        while (active === holder && isListening(holder)) {
            delay(AUTO_STOP_CHECK_MS)
            elapsedMs += AUTO_STOP_CHECK_MS
            silenceMs += AUTO_STOP_CHECK_MS
            if (capture.amplitude.value >= config.speechAmplitudeThreshold) silenceMs = 0L
            val capHit = maxTimeoutMs > 0 && elapsedMs >= maxTimeoutMs
            val silenceHit = silenceTimeoutMs > 0 && silenceMs >= silenceTimeoutMs
            if (capHit || silenceHit) {
                stop()
                break
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private fun onLiveEvent(holder: ActiveLiveSession, event: GeminiEvent) {
        if (active !== holder) return
        when (event) {
            GeminiEvent.Ready -> Unit
            is GeminiEvent.Amplitude -> Unit // waveform comes from the capture flow
            is GeminiEvent.TranscriptCandidates -> {
                // ECHO (outputTranscription, instruction-controlled) is the primary
                // source; INPUT (raw ASR) is the fast fallback.
                val isEcho = event.source == GeminiEvent.TranscriptSource.ECHO
                val target = if (isEcho) holder.echoAccumulator else holder.accumulator
                event.candidates.forEach { candidate ->
                    target.accept(candidate.raw)
                }
                onTranscriptUpdate(holder, isEcho)
            }
            GeminiEvent.TurnComplete -> onTurnComplete(holder)
            is GeminiEvent.Failed -> fail(holder, event.failure)
            GeminiEvent.SessionEnd -> if (!isInserting(holder)) {
                fail(
                    holder,
                    DictationFailure(
                        code = "gemini_transport",
                        message = "The Gemini session closed.",
                        recoverable = true,
                    ),
                )
            }
        }
    }

    private fun onCaptureFailure(holder: ActiveLiveSession, failure: DictationFailure) {
        if (active !== holder) return
        fail(holder, failure)
    }

    // ------------------------------------------------------------------
    // Finalization
    // ------------------------------------------------------------------

    private fun startFinalization(holder: ActiveLiveSession) {
        val session = holder.session ?: return
        val capture = holder.capture
        // One absolute monotonic deadline from STOP (Release E4): selection or an
        // explicit failure, never a waiting state. Independent of the settle
        // debounce so a debounce reset can never extend the deadline.
        holder.deadlineJob = scope.launch {
            delay(config.hardDeadlineMs)
            if (active === holder && isFinalizing(holder)) {
                holder.metrics.usedHardDeadline = true
                settle(holder)
            }
        }
        // Orderly producer drain before the completion boundary: request stop,
        // let the producer flush its final partial frame and close the channel,
        // join the ordered sender, then send the boundary.
        holder.finalizationJob = scope.launch {
            capture?.requestStop()
            val quiesced = capture?.awaitQuiescence(config.captureShutdownTimeoutMs) ?: true
            if (!quiesced) capture.stop()
            holder.audioJob?.join()
            holder.metrics.mark(MutableSessionMetrics.Event.CaptureQuiesced)
            when (val result = session.endActivity()) {
                is SendResult.Rejected -> {
                    fail(holder, transportFailure(result.reason))
                    return@launch
                }
                SendResult.Accepted -> {
                    holder.metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued)
                    afterActivityEnd(holder)
                }
            }
        }
    }

    /**
     * A new transcript revision arrived while finalizing. Only the echo
     * (instruction-controlled) resets the settle debounce — the raw input is the
     * fallback and is settled by the echo-fallback watchdog or the hard deadline,
     * so it never cuts short a pending echo (0.4.1).
     */
    private fun onTranscriptUpdate(holder: ActiveLiveSession, isEcho: Boolean) {
        if (active !== holder) return
        if (!isFinalizing(holder)) return
        if (isEcho) restartSettleDebounce(holder)
    }

    /** The server completed the turn; retained even when it precedes STOP (E7). */
    private fun onTurnComplete(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.turnCompleteSeen = true
        if (isFinalizing(holder) && hasValidTranscript(holder)) {
            restartSettleDebounce(holder)
        }
    }

    /** After the completion boundary: settle promptly when turn completion and a
     *  transcript are both present (E5), and start the echo-fallback watchdog
     *  (0.4.1 reworked): if no echo arrives within a grace window AFTER the raw
     *  is final, settle on the fast raw transcript instead of waiting up to the
     *  20 s deadline. Starting the watchdog here (not at STOP) guarantees the
     *  raw is the final post-activity ASR, never a provisional mid-activity
     *  revision. */
    private fun afterActivityEnd(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.echoFallbackJob?.cancel()
        holder.echoFallbackJob = scope.launch {
            delay(config.echoFallbackWaitMs)
            if (active === holder && isFinalizing(holder) &&
                holder.echoAccumulator.settledText().isNullOrBlank() &&
                holder.accumulator.settledText()?.isNotBlank() == true
            ) {
                settle(holder)
            }
        }
        if (holder.turnCompleteSeen && hasValidTranscript(holder)) {
            restartSettleDebounce(holder)
        }
    }

    /** Resets the short settle debounce; never extends the absolute deadline. */
    private fun restartSettleDebounce(holder: ActiveLiveSession) {
        holder.settleJob?.cancel()
        holder.settleJob = scope.launch {
            delay(config.settleDebounceMs)
            if (active === holder && isFinalizing(holder)) {
                settle(holder)
            }
        }
    }

    private fun hasValidTranscript(holder: ActiveLiveSession): Boolean =
        selectSettledText(holder) != null

    /**
     * 0.4.2 settlement policy — the user's words are never lost to a partial
     *  echo (never a retry because the echo was truncated):
     *   - echo complete (covers the raw ASR) -> the polished echo;
     *   - echo absent -> the complete raw ASR;
     *   - echo present but partial -> the complete raw ASR (salvage);
     *   - raw absent but echo present -> the echo (completeness governed by the
     *     duration-sanity gate, which may trigger audio recovery);
     *   - nothing -> null.
     */
    private fun selectSettledText(holder: ActiveLiveSession): Pair<String, SettlePath>? {
        val echo = holder.echoAccumulator.settledText()?.takeIf { it.isNotBlank() }
        val raw = holder.accumulator.settledText()?.takeIf { it.isNotBlank() }
        return when {
            echo != null && raw != null &&
                TranscriptCompleteness.covers(echo, raw, config.minEchoRatio) ->
                echo to SettlePath.ECHO_COMPLETE

            raw != null && echo == null -> raw to SettlePath.RAW_ONLY

            raw != null -> raw to SettlePath.ECHO_PARTIAL_RAW

            echo != null -> echo to SettlePath.ECHO_ONLY

            else -> null
        }
    }

    private fun settle(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.settleJob?.cancel()
        holder.deadlineJob?.cancel()
        holder.echoFallbackJob?.cancel()
        holder.metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        val selected = selectSettledText(holder)
        holder.metrics.settlePath = selected?.second ?: SettlePath.NONE
        val raw = selected?.first
        val candidate = raw?.let {
            ResultCandidate(raw = it, cleaned = null, language = holder.language)
        }
        val selection = candidate?.let { selector.select(listOf(it)) } ?: TranscriptSelection.None
        when (selection) {
            is TranscriptSelection.None -> {
                // Failsafe: the source is the user's own ASR speech, so a strict
                // rejection is not final. Log WHY it was rejected, then accept it
                // unless it is truly unusable (blank, garbled, or a clearly
                // provisional fragment at the hard deadline).
                val diagnosis = candidate?.let { selector.diagnose(listOf(it)) }
                holder.metrics.lastRejection = diagnosis?.rule?.name
                if (candidate != null && lenientAccept(holder, candidate, diagnosis)) {
                    holder.metrics.usedLenientFallback = true
                    if (startRecovery(holder, candidate.raw)) return
                    holder.settledText = candidate.raw
                    insertSettled(holder, candidate.raw)
                } else {
                    failNoTranscript(holder)
                }
            }
            is TranscriptSelection.Cleaned, is TranscriptSelection.Raw -> {
                // E6: a clearly provisional fragment at the hard deadline is
                // rejected rather than silently inserted.
                if (holder.metrics.usedHardDeadline && isClearlyProvisional(selection.text)) {
                    failNoTranscript(holder)
                    return
                }
                if (startRecovery(holder, selection.text)) return
                holder.settledText = selection.text
                insertSettled(holder, selection.text)
            }
        }
    }

    /**
     * 0.4.2 audio-recovery gate. When the chosen text plausibly misses most of
     * the recorded speech (duration-derived expected words), the full recording
     * is re-transcribed via [DictationHost.recoverTranscript] and the recovered
     * text is inserted instead — the user's words are recovered even when BOTH
     * the echo and the raw ASR failed.
     *
     * The recovery runs on its OWN coroutine (never the settle job), so a settle
     * debounce restart or session teardown can never abandon the session in the
     * Recovering state (0.4.2 bug: stuck on "Recovering full text…"). A hard
     * timeout guarantees it can never hang: on timeout or failure the best
     * available live text is inserted, so nothing is ever lost and the UI never
     * stalls.
     *
     * Returns true when recovery took over (the caller must NOT insert); false
     * when the chosen text is used directly.
     */
    private fun startRecovery(holder: ActiveLiveSession, text: String): Boolean {
        val durationMs = holder.metrics.capturedFrames * CHUNK_DURATION_MS
        val expected = TranscriptCompleteness.expectedWords(durationMs, config.expectedWordsPerSecond)
        val settledWords = TranscriptCompleteness.contentWords(text).size
        holder.metrics.expectedWords = expected
        holder.metrics.settledWords = settledWords
        if (expected <= 0 || settledWords >= expected * config.minExpectedFraction) return false
        if (holder.recording.toWav() == null) return false
        publish(DictationState.Recovering(holder.sessionId))
        holder.recoveryJob?.cancel()
        holder.recoveryJob = scope.launch {
            val wav = holder.recording.toWav() ?: return@launch
            val recovered = try {
                withTimeout(config.recoveryTimeoutMs) {
                    host.recoverTranscript(holder.sessionId, wav)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (active !== holder) return@launch
            val finalText = if (recovered.isNullOrBlank()) text else recovered
            if (recovered != null && recovered.isNotBlank()) {
                holder.metrics.usedAudioRecovery = true
                holder.metrics.recoveredWords = TranscriptCompleteness.contentWords(recovered).size
            }
            holder.settledText = finalText
            insertSettled(holder, finalText)
        }
        return true
    }

    /** Lenient failsafe acceptance for user speech rejected by strict validation. */
    private fun lenientAccept(
        holder: ActiveLiveSession,
        candidate: ResultCandidate,
        diagnosis: RejectionDiagnosis?,
    ): Boolean {
        val text = candidate.raw
        if (text.isBlank() || !text.any { it.isLetterOrDigit() }) return false
        if (diagnosis?.rule == RejectionRule.GARBLED) return false
        if (holder.metrics.usedHardDeadline && text.trim().length < 2) return false
        return true
    }

    private fun insertSettled(holder: ActiveLiveSession, text: String) {
        if (!host.sendInsertion(holder.sessionId, text)) {
            fail(holder, accessibilityUnavailable())
            return
        }
        holder.inserted = true
        holder.metrics.mark(MutableSessionMetrics.Event.InsertionRequested)
        publish(DictationState.Inserting(holder.sessionId))
        teardown(holder)
    }

    private fun failNoTranscript(holder: ActiveLiveSession) {
        fail(
            holder,
            DictationFailure(
                code = "gemini_no_transcript",
                message = "No transcript could be recognized. Try again.",
                recoverable = true,
                retryAllowed = true,
            ),
        )
    }

    /** A bare 1-character fragment (e.g. "t" from "the") is a cut-off provisional
     *  transcript, never a completed utterance. */
    private fun isClearlyProvisional(text: String): Boolean = text.trim().length < 2

    // ------------------------------------------------------------------
    // Failure / teardown / reset
    // ------------------------------------------------------------------

    private fun fail(holder: ActiveLiveSession, failure: DictationFailure) {
        if (active !== holder) return
        publish(DictationState.Error(holder.sessionId, failure))
        teardown(holder)
        if (failure.retryAllowed) return // error persists until Retry/Dismiss
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    /**
     * Re-starts dictation after a retryable Error state. Clears the errored
     * session and opens a fresh one; returns false while a session is still
     * running (i.e. not in an Error state).
     */
    fun retry(): Boolean {
        val holder = active
        if (holder != null) {
            val state = lastPublished
            if (state !is DictationState.Error) return false
            resetToIdle(holder)
        }
        return start()
    }

    /** Dismisses a terminal Error panel and returns to Idle. */
    fun dismiss() {
        val holder = active ?: return
        if (lastPublished is DictationState.Error) {
            resetToIdle(holder)
        }
    }

    /** Cancels session resources; does NOT clear [active] (that happens on reset). */
    private fun teardown(holder: ActiveLiveSession) {
        holder.sessionJob?.cancel()
        holder.readyJob?.cancel()
        holder.settleJob?.cancel()
        holder.deadlineJob?.cancel()
        holder.echoFallbackJob?.cancel()
        holder.finalizationJob?.cancel()
        holder.audioJob?.cancel()
        holder.amplitudeJob?.cancel()
        holder.autoStopJob?.cancel()
        holder.captureFailureJob?.cancel()
        holder.eventJob?.cancel()
        holder.recoveryJob?.cancel()
        holder.capture?.let { capture ->
            scope.launch {
                capture.requestStop()
                capture.awaitQuiescence(config.captureShutdownTimeoutMs)
                capture.stop()
            }
        }
        holder.session?.let { session ->
            scope.launch { session.close() }
        }
    }

    /** Compare-and-clear (C4): only clears if the holder is still the active one. */
    private fun resetToIdle(holder: ActiveLiveSession) {
        if (active === holder) {
            active = null
            host.onSessionFinished(lastPublished, holder.metrics, holder.settledText)
            publish(DictationState.Idle)
        }
    }

    // ------------------------------------------------------------------
    // State predicates
    // ------------------------------------------------------------------

    @Volatile
    private var lastPublished: DictationState = DictationState.Idle

    /** Tracks the last published state locally AND forwards it to the host. */
    private fun publish(state: DictationState) {
        lastPublished = state
        host.publish(state)
    }

    private fun isListening(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Listening && state.sessionId == holder.sessionId
    }

    private fun isFinalizing(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Finalizing && state.sessionId == holder.sessionId
    }

    private fun isInserting(holder: ActiveLiveSession): Boolean {
        val state = lastPublished
        return state is DictationState.Inserting && state.sessionId == holder.sessionId
    }

    private fun transportFailure(reason: String): DictationFailure = DictationFailure(
        code = "gemini_transport",
        message = "Gemini rejected the activity boundary ($reason).",
        recoverable = true,
        retryAllowed = true,
    )

    private fun accessibilityUnavailable(): DictationFailure = DictationFailure(
        code = "runtime_no_accessibility",
        message = "Could not reach the accessibility service.",
        recoverable = true,
        retryAllowed = true,
    )

    /** F5: the pre-ready buffer overflowed, so the connection was too slow. */
    private fun connectionTooSlow(): DictationFailure = DictationFailure(
        code = "gemini_connection_too_slow",
        message = "The Gemini connection is too slow. Try again.",
        recoverable = true,
        retryAllowed = true,
    )

    companion object {
        /** Auto-stop watcher sampling interval (pure elapsed-time tick). */
        const val AUTO_STOP_CHECK_MS = 200L

        /** Duration of one captured 20 ms PCM chunk (matches the Chunker). */
        const val CHUNK_DURATION_MS = 20L

        /** Placeholder target used while the accessibility process resolves the real one. */
        fun EMPTY_TARGET(sessionId: SessionId): TargetSnapshot = TargetSnapshot(
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
