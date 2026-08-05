package com.whispertype.android.platform.runtime

import com.whispertype.android.audio.AudioPipeline
import com.whispertype.android.core.contracts.GeminiLiveSession
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
import com.whispertype.android.core.transcript.TranscriptAccumulator
import com.whispertype.android.core.transcript.TranscriptSelection
import com.whispertype.android.core.transcript.TranscriptSelector
import com.whispertype.android.platform.gemini.GeminiLiveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
}

/** Outcome of [DictationHost.resolveSession]. */
sealed interface SessionResolve {
    data class Ok(val resolution: SessionResolution) : SessionResolve
    data class Failed(val failure: DictationFailure) : SessionResolve
}

/** A created Live session plus the language it should stamp on candidates. */
data class SessionResolution(val session: GeminiLiveSession, val language: LanguageMode)

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
    var audioJob: Job? = null
    var amplitudeJob: Job? = null
    var captureFailureJob: Job? = null
    var finalizationJob: Job? = null
    var settleJob: Job? = null
    var deadlineJob: Job? = null
    var inserted: Boolean = false
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
        val trailingTranscriptGraceMs: Long = 2_000,
        val finalizeTimeoutMs: Long = 8_000,
        val returnToIdleMs: Long = 1_200,
        val captureShutdownTimeoutMs: Long = 1_500,
    )

    @Volatile
    private var active: ActiveLiveSession? = null

    /** True when a live session is running or finalizing (guards duplicate START). */
    val isActive: Boolean get() = active != null

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
            try {
                resolution.session.awaitReady()
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
            when (val start = resolution.session.startActivity()) {
                is SendResult.Rejected -> {
                    fail(holder, transportFailure(start.reason))
                    return
                }
                SendResult.Accepted -> Unit
            }
            if (active !== holder) return
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
            publish(DictationState.Listening(sessionId))
            holder.audioJob = scope.launch { streamAudio(holder) }
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

    private suspend fun streamAudio(holder: ActiveLiveSession) {
        val session = holder.session ?: return
        val capture = holder.capture ?: return
        for (chunk in capture.chunks) {
            holder.metrics.capturedFrames++
            if (session.sendAudio(chunk) is SendResult.Accepted) {
                holder.metrics.acceptedFrames++
                if (holder.metrics.firstAudioQueuedAt == null) {
                    holder.metrics.mark(MutableSessionMetrics.Event.FirstAudioQueued)
                }
            } else {
                holder.metrics.rejectedFrames++
            }
        }
        holder.metrics.mark(MutableSessionMetrics.Event.LastAudioQueued)
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
            }
            GeminiEvent.TurnComplete -> if (isFinalizing(holder)) {
                handleFinalization(holder)
            }
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
                SendResult.Accepted -> holder.metrics.mark(MutableSessionMetrics.Event.ActivityEndQueued)
            }
        }
        // Absolute safety-net deadline (Release E replaces this with the hard 3s
        // deadline plus a settle debounce; here the existing behavior is kept).
        holder.deadlineJob = scope.launch {
            delay(config.finalizeTimeoutMs)
            if (active === holder && isFinalizing(holder)) {
                holder.metrics.usedHardDeadline = true
                settle(holder)
            }
        }
    }

    private fun handleFinalization(holder: ActiveLiveSession) {
        if (holder.accumulator.settledText()?.isNotBlank() == true) {
            settle(holder)
        } else {
            // inputTranscription is a separate serverContent message with no
            // guaranteed ordering; it can trail turnComplete. Wait a short grace
            // window before declaring no-transcript.
            holder.settleJob?.cancel()
            holder.settleJob = scope.launch {
                delay(config.trailingTranscriptGraceMs)
                if (active === holder && isFinalizing(holder)) {
                    settle(holder)
                }
            }
        }
    }

    private fun settle(holder: ActiveLiveSession) {
        if (active !== holder) return
        holder.settleJob?.cancel()
        holder.deadlineJob?.cancel()
        holder.metrics.mark(MutableSessionMetrics.Event.TranscriptSettled)
        val raw = holder.accumulator.settledText()
        val selection = raw?.let {
            selector.select(listOf(ResultCandidate(raw = it, cleaned = null, language = holder.language)))
        } ?: TranscriptSelection.None
        when (selection) {
            is TranscriptSelection.None -> fail(
                holder,
                DictationFailure(
                    code = "gemini_no_transcript",
                    message = "No transcript could be recognized. Try again.",
                    recoverable = true,
                ),
            )
            is TranscriptSelection.Cleaned, is TranscriptSelection.Raw -> {
                if (!host.sendInsertion(holder.sessionId, selection.text)) {
                    fail(holder, accessibilityUnavailable())
                    return
                }
                holder.inserted = true
                holder.metrics.mark(MutableSessionMetrics.Event.InsertionRequested)
                host.publish(DictationState.Inserting(holder.sessionId))
                teardown(holder)
            }
        }
    }

    // ------------------------------------------------------------------
    // Failure / teardown / reset
    // ------------------------------------------------------------------

    private fun fail(holder: ActiveLiveSession, failure: DictationFailure) {
        if (active !== holder) return
        publish(DictationState.Error(holder.sessionId, failure))
        teardown(holder)
        scope.launch {
            delay(config.returnToIdleMs)
            resetToIdle(holder)
        }
    }

    /** Cancels session resources; does NOT clear [active] (that happens on reset). */
    private fun teardown(holder: ActiveLiveSession) {
        holder.sessionJob?.cancel()
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
    )

    private fun accessibilityUnavailable(): DictationFailure = DictationFailure(
        code = "runtime_no_accessibility",
        message = "Could not reach the accessibility service.",
        recoverable = true,
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
