package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.MutableSessionMetrics
import com.whispertype.android.core.model.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lifecycle of the prewarmed Live session pool (Release F3). */
sealed interface WarmSessionState {
    /** No warm session; prewarm disabled. */
    data object None : WarmSessionState

    /** A prewarm connection is being established. */
    data object Connecting : WarmSessionState

    /** A ready, idle warm session is available to claim. */
    data class Ready(val session: GeminiLiveSession) : WarmSessionState

    /** A ready session was claimed by an active dictation. */
    data object Claimed : WarmSessionState

    /** The last prewarm failed; retrying after [retryMs]. */
    data class Backoff(val retryMs: Long) : WarmSessionState

    /** The warm session is being closed. */
    data object Closing : WarmSessionState
}

/**
 * Eligibility-driven warm Live session pool (Release F).
 *
 * While the app is eligible for dictation (focused non-secure editor, keyboard
 * visible, mic + API key configured, no active dictation), this manager keeps
 * one ready Gemini Live session preconnected so a tap skips the cold WebSocket
 * setup. [claim] atomically takes a ready session for a dictation and starts a
 * replacement prewarm; [onEligibilityChanged] starts or tears the pool down.
 *
 * The prewarm session is closed after a conservative idle timeout (default 30 s)
 * and reconnects with a bounded backoff (1, 2, 5, then 10 s).
 *
 * NOTE (F1): idle prewarm sessions may incur billable usage; the device gate
 * must verify that before enabling prewarming in production. This manager is
 * purely client-side and does not assume idle sessions are free.
 */
class WarmLiveSessionManager(
    private val scope: CoroutineScope,
    private val createSession: suspend () -> GeminiLiveSession,
    private val config: Config = Config(),
) {

    /** Tunable prewarm timing (all monotonic delays). */
    data class Config(
        val warmIdleTimeoutMs: Long = 30_000,
        val backoffStepsMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L),
    )

    private val _state = MutableStateFlow<WarmSessionState>(WarmSessionState.None)
    val state: StateFlow<WarmSessionState> = _state.asStateFlow()

    private var eligible = false
    private var warmSession: GeminiLiveSession? = null
    private var backoffAttempt = 0
    private var connectJob: Job? = null
    private var idleJob: Job? = null

    /** Test visibility: metrics associated with the current ready warm session. */
    internal fun warmSessionForTest(): GeminiLiveSession? = warmSession

    /**
     * Starts or tears down the pool as eligibility changes. A claimed session
     * (in use by an active dictation) is never closed here.
     */
    fun onEligibilityChanged(isEligible: Boolean) {
        if (isEligible == eligible) return
        eligible = isEligible
        if (isEligible) {
            if (_state.value is WarmSessionState.None || _state.value is WarmSessionState.Claimed) {
                startPrewarm()
            }
        } else {
            closeWarmSession()
        }
    }

    /**
     * Atomically claims a ready warm session, or returns null when none is ready
     * (the caller then performs a cold connect). A replacement prewarm starts
     * immediately so the pool stays warm for the next tap.
     */
    fun claim(): GeminiLiveSession? {
        val current = _state.value
        if (current !is WarmSessionState.Ready) return null
        idleJob?.cancel()
        val session = current.session
        warmSession = null
        backoffAttempt = 0
        _state.value = WarmSessionState.Claimed
        if (eligible) startPrewarm()
        return session
    }

    private fun startPrewarm() {
        connectJob?.cancel()
        idleJob?.cancel()
        _state.value = WarmSessionState.Connecting
        connectJob = scope.launch {
            try {
                val session = createSession()
                session.awaitReady()
                if (!eligible) {
                    scope.launch { session.close() }
                    _state.value = WarmSessionState.None
                    return@launch
                }
                warmSession = session
                _state.value = WarmSessionState.Ready(session)
                backoffAttempt = 0
                idleJob = scope.launch {
                    delay(config.warmIdleTimeoutMs)
                    closeWarmSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!eligible) {
                    _state.value = WarmSessionState.None
                    return@launch
                }
                val backoff = config.backoffStepsMs.getOrElse(backoffAttempt) { config.backoffStepsMs.last() }
                backoffAttempt++
                _state.value = WarmSessionState.Backoff(backoff)
                connectJob = scope.launch {
                    delay(backoff)
                    if (eligible) startPrewarm()
                }
            }
        }
    }

    private fun closeWarmSession() {
        connectJob?.cancel()
        idleJob?.cancel()
        // A claimed session belongs to an active dictation; never close it here.
        if (_state.value is WarmSessionState.Claimed) return
        warmSession?.let { session -> scope.launch { session.close() } }
        warmSession = null
        backoffAttempt = 0
        _state.value = WarmSessionState.None
    }
}
