package com.whispertype.android.dictation

import android.graphics.Rect
import com.whispertype.android.accessibility.TargetToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reducer transition tests: active-session locking, stale sessions, terminal lock. */
class DictationReducerTest {

    private fun token(sessionId: String): TargetToken = TargetToken(
        sessionId = sessionId,
        inputGeneration = 1L,
        packageName = "com.example",
        displayId = 0,
        windowId = null,
        fieldId = null,
        inputType = 1,
        initialSelectionStart = 0,
        initialSelectionEnd = 0,
        imeBounds = Rect(),
    )

    private fun ctx(sessionId: String = "s1") = DictationReducer.SessionContext(
        sessionId = sessionId,
        target = token(sessionId),
        resultConsumed = false,
        resultText = null,
        stopped = false,
    )

    private val failure = DictationFailure("CODE", "message", true)

    private val cancelInertStates = listOf(
        DictationState.Success("s1", 5),
        DictationState.Error("s1", failure),
        DictationState.CopyAvailable("s1", "text"),
        DictationState.Cancelled,
        DictationState.Unavailable,
        DictationState.NoEditableFocus,
        DictationState.DockedReady,
    )

    private val stopInertStates = cancelInertStates + listOf(
        DictationState.Finalizing("s1", 0f),
        DictationState.Inserting("s1"),
    )

    @Test
    fun startFromIdleStatesEntersStarting() {
        val idleStates = cancelInertStates + DictationState.Finalizing("s1", 0f) + DictationState.Inserting("s1")
        for (state in idleStates) {
            val result = DictationReducer.reduce(state, DictationCommand.Start(token("s1"), "s1"), ctx("s1"))
            assertTrue("state $state must transition", result is DictationState.Starting)
            assertEquals("s1", result.sessionId)
        }
    }

    @Test
    fun startWhileActiveWithDifferentSessionIsIgnored() {
        val activeStates = listOf(
            DictationState.Starting("s1", token("s1")),
            DictationState.Listening("s1", 0, 0f),
            DictationState.Finalizing("s1", 0f),
            DictationState.Inserting("s1"),
        )
        for (state in activeStates) {
            assertSame(state, DictationReducer.reduce(state, DictationCommand.Start(token("s2"), "s2"), ctx("s1")))
        }
    }

    @Test
    fun startWhileActiveWithSameSessionRestarts() {
        val state = DictationState.Listening("s1", 1200, 0.5f)
        val result = DictationReducer.reduce(state, DictationCommand.Start(token("s1"), "s1"), ctx("s1"))
        assertTrue(result is DictationState.Starting)
        assertEquals("s1", result.sessionId)
    }

    @Test
    fun stopFromListeningCarriesAmplitude() {
        val result = DictationReducer.reduce(
            DictationState.Listening("s1", 500, 0.75f),
            DictationCommand.Stop,
            ctx("s1"),
        )
        assertEquals(DictationState.Finalizing("s1", 0.75f), result)
    }

    @Test
    fun stopFromStartingUsesZeroAmplitude() {
        val result = DictationReducer.reduce(
            DictationState.Starting("s1", token("s1")),
            DictationCommand.Stop,
            ctx("s1"),
        )
        assertEquals(DictationState.Finalizing("s1", 0f), result)
    }

    @Test
    fun stopFromInertStatesKeepsState() {
        for (state in stopInertStates) {
            assertSame(state, DictationReducer.reduce(state, DictationCommand.Stop, ctx("s1")))
        }
    }

    @Test
    fun cancelFromActiveStatesCancels() {
        val activeStates = listOf(
            DictationState.Starting("s1", token("s1")),
            DictationState.Listening("s1", 0, 0f),
            DictationState.Finalizing("s1", 0f),
            DictationState.Inserting("s1"),
        )
        for (state in activeStates) {
            assertEquals(DictationState.Cancelled, DictationReducer.reduce(state, DictationCommand.Cancel, ctx("s1")))
        }
    }

    @Test
    fun cancelFromInertStatesKeepsState() {
        for (state in cancelInertStates) {
            assertSame(state, DictationReducer.reduce(state, DictationCommand.Cancel, ctx("s1")))
        }
    }

    @Test
    fun copyResultKeepsState() {
        val copyState = DictationState.CopyAvailable("s1", "text")
        assertSame(copyState, DictationReducer.reduce(copyState, DictationCommand.CopyResult, ctx("s1")))
        val listening = DictationState.Listening("s1", 1, 1f)
        assertSame(listening, DictationReducer.reduce(listening, DictationCommand.CopyResult, ctx("s1")))
    }

    @Test
    fun dismissCopyFromCopyAvailableReturnsToDockedReady() {
        val result = DictationReducer.reduce(
            DictationState.CopyAvailable("s1", "text"),
            DictationCommand.DismissCopy,
            ctx("s1"),
        )
        assertEquals(DictationState.DockedReady, result)
    }

    @Test
    fun dismissCopyFromOtherStatesKeepsState() {
        val states = listOf(
            DictationState.Listening("s1", 0, 0f),
            DictationState.Success("s1", 1),
            DictationState.Unavailable,
            DictationState.Cancelled,
        )
        for (state in states) {
            assertSame(state, DictationReducer.reduce(state, DictationCommand.DismissCopy, ctx("s1")))
        }
    }

    @Test
    fun staleCommandsFromAnotherSessionAreIgnored() {
        val listening = DictationState.Listening("new", 0, 0f)
        assertSame(listening, DictationReducer.reduce(listening, DictationCommand.Stop, ctx("old")))
        assertSame(listening, DictationReducer.reduce(listening, DictationCommand.Cancel, ctx("old")))
        val copy = DictationState.CopyAvailable("new", "text")
        assertSame(copy, DictationReducer.reduce(copy, DictationCommand.CopyResult, ctx("old")))
        assertSame(copy, DictationReducer.reduce(copy, DictationCommand.DismissCopy, ctx("old")))
    }

    @Test
    fun staleGuardDoesNotBlockStart() {
        val result = DictationReducer.reduce(
            DictationState.Unavailable,
            DictationCommand.Start(token("new"), "new"),
            ctx("old"),
        )
        assertTrue(result is DictationState.Starting)
        assertEquals("new", result.sessionId)
    }
}
