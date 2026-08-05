package com.whispertype.android.platform.gemini

import com.whispertype.android.core.contracts.GeminiLiveSession
import com.whispertype.android.core.model.AudioChunk
import com.whispertype.android.core.model.GeminiEvent
import com.whispertype.android.core.model.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Warm-session pool state machine (Release F3): eligibility-driven prewarm,
 * atomic claim, replacement prewarm, bounded backoff, conservative idle timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarmLiveSessionManagerTest {

    private class FakeWarmSession : GeminiLiveSession {
        var readyError: Throwable? = null
        var closed = false
        override suspend fun awaitReady() {
            readyError?.let { throw it }
        }

        override suspend fun startActivity(): SendResult = SendResult.Accepted
        override suspend fun sendAudio(chunk: AudioChunk): SendResult = SendResult.Accepted
        override suspend fun endActivity(): SendResult = SendResult.Accepted
        override fun events(): Flow<GeminiEvent> = flow {}
        override suspend fun close() {
            closed = true
        }
    }

    private class Harness {
        val sessions = mutableListOf<FakeWarmSession>()
        var created = 0
        /** Sessions created up to and including this call count fail readiness. */
        var failUpToCall = 0

        fun managerFor(scope: TestScope, config: WarmLiveSessionManager.Config = WarmLiveSessionManager.Config()) =
            WarmLiveSessionManager(
                scope = scope,
                createSession = {
                    created++
                    val s = FakeWarmSession()
                    if (created <= failUpToCall) s.readyError = IllegalStateException("intentional prewarm failure")
                    sessions += s
                    s
                },
                config = config,
            )
    }

    @Test
    fun `prewarms a ready session when eligible and claims it`() = runTest {
        val h = Harness()
        val manager = h.managerFor(this)
        assertIs<WarmSessionState.None>(manager.state.value)

        manager.onEligibilityChanged(true)
        assertIs<WarmSessionState.Connecting>(manager.state.value)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Ready>(manager.state.value)
        assertEquals(1, h.created)

        val claimed = manager.claim()
        assertNotNull(claimed)

        // A replacement prewarm starts immediately so the pool stays warm.
        assertIs<WarmSessionState.Connecting>(manager.state.value)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Ready>(manager.state.value)
        assertEquals(2, h.created)
    }

    @Test
    fun `claim returns null while connecting or disabled`() = runTest {
        val h = Harness()
        val manager = h.managerFor(this)
        assertNull(manager.claim())

        manager.onEligibilityChanged(true)
        assertIs<WarmSessionState.Connecting>(manager.state.value)
        assertNull(manager.claim())
    }

    @Test
    fun `idle timeout closes the warm session`() = runTest {
        val h = Harness()
        val manager = h.managerFor(this, WarmLiveSessionManager.Config(warmIdleTimeoutMs = 30_000))
        manager.onEligibilityChanged(true)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Ready>(manager.state.value)

        advanceTimeBy(30_000)
        assertIs<WarmSessionState.None>(manager.state.value)
        assertTrue(h.sessions[0].closed)
    }

    @Test
    fun `losing eligibility closes the warm session`() = runTest {
        val h = Harness()
        val manager = h.managerFor(this)
        manager.onEligibilityChanged(true)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Ready>(manager.state.value)

        manager.onEligibilityChanged(false)
        advanceTimeBy(1)
        assertIs<WarmSessionState.None>(manager.state.value)
        assertTrue(h.sessions[0].closed)
    }

    @Test
    fun `a claimed session is never closed by losing eligibility`() = runTest {
        val h = Harness()
        val manager = h.managerFor(this)
        manager.onEligibilityChanged(true)
        advanceTimeBy(1)
        val claimed = manager.claim()!! as FakeWarmSession

        manager.onEligibilityChanged(false)
        advanceTimeBy(1)
        assertFalse(claimed.closed, "a claimed session belongs to the active dictation")
    }

    @Test
    fun `backoff retries after failure and recovers`() = runTest {
        val h = Harness().apply { failUpToCall = 1 } // first attempt fails
        val manager = h.managerFor(this)
        manager.onEligibilityChanged(true)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Backoff>(manager.state.value)

        advanceTimeBy(1_000)
        advanceTimeBy(1)
        assertIs<WarmSessionState.Ready>(manager.state.value)
        assertEquals(2, h.created)
    }
}
