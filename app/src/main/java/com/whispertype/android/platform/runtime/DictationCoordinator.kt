package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.audio.PreReadyAudioBuffer
import com.whispertype.android.audio.PreReadyOffer
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
import com.whispertype.android.core.model.TargetSnapshot
import com.whispertype.android.core.transcript.RejectionDiagnosis
import com.whispertype.android.core.transcript.RejectionRule
import com.whispertype.android.core.transcript.TranscriptAccumulator
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
     * Per-session aggregate diagnostics at terminal state. Must never contain
     * transcript text, audio, keys, or full server frames.
     */
    fun onSessionFinished(state: DictationState, metrics: MutableSessionMetrics)
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
    var inserted: Boolean = false

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
        /** One absolute deadline from STOP: selection or an explicit failure (Release E4). */
        val hardDeadlineMs: Long = 3_000,
        /** Short settle debounce after the last transcript revision / turn complete. */
        val settleDebounceMs: Long = 250,
        val returnToIdleMs: Long = 1_200,
        val captureShutdownTimeoutMs: Long = 1_500,
        /** Bounded pre-ready PCM frames buffered while a cold session connects (F5). */
        val preReadyMaxFrames: Int = 150,
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

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private fun onLiveEvent(holder: ActiveLiveSession, event: GeminiEvent) {
        if (active !== holder) return
        when (event) {
            GeminiEvent.Ready -> Unit
            is GeminiEvent.Amplitude -> Unit // waveform comes from the capture flow
            is GeminiEvent.TranscriptCandidates -> {
                event.candidates.forEach { candidate ->
                    holder.accumulator.accept(candidate.raw)
                }
                onTranscriptUpdate(holder)
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

    /** A new input transcript revision arrived; reset the settle debounce (E5). */
    private fun onTranscriptUpdate(holder: ActiveLiveSession) {
        if (active !== holder) return
        if (!isFinalizing(holder)) return
        restartSettleDebounce(holder)
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
     *  transcript are both present (E5). */
    private fun afterActivityEnd(holder: ActiveLiveSession) {
        if (active !== holder) return
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
        holder.accumulator.settledText()?.isNotBlank() == true

    private fun settle(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.settleJob?.cancel()
        holder.deadlineJob?.cancel()
        holder.metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        val raw = holder.accumulator.settledText()
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
                    insertSettled(holder, candidate.raw)
                } else {
                    failNoTranscript(holder)
                }
            }
            is TranscriptSelection.Cleaned, is TranscriptSelection.Raw -> {
                // E6: a clearly provisional fragment at the hard deadline is
                // rejected rather than silently inserted. Calibrate this
                // threshold from measured trailing-message timing.
                if (holder.metrics.usedHardDeadline && isClearlyProvisional(selection.text)) {
                    failNoTranscript(holder)
                    return
                }
                insertSettled(holder, selection.text)
            }
        }
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
        holder.finalizationJob?.cancel()
        holder.audioJob?.cancel()
        holder.amplitudeJob?.cancel()
        holder.captureFailureJob?.cancel()
        holder.eventJob?.cancel()
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
            host.onSessionFinished(lastPublished, holder.metrics)
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
