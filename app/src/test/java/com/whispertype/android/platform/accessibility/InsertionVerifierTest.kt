package com.whispertype.android.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure host tests for [InsertionVerifier] (Phase 4 exactly-once verification). */
class InsertionVerifierTest {

    @Test
    fun `changed surrounding text containing the commit is confirmed`() {
        assertEquals(
            true,
            InsertionVerifier.confirmed(
                before = "hello ",
                after = "hello WhisperType test",
                committed = "WhisperType test",
            ),
        )
    }

    @Test
    fun `no change is a definite false`() {
        assertEquals(
            false,
            InsertionVerifier.confirmed(before = "unchanged", after = "unchanged", committed = "x"),
        )
    }

    @Test
    fun `change without the committed text is not confirmed`() {
        // The editor changed for another reason; we must not claim success.
        assertEquals(
            false,
            InsertionVerifier.confirmed(before = "a", after = "b", committed = "x"),
        )
    }

    @Test
    fun `null surrounding read is ambiguous not confirmed`() {
        assertNull(InsertionVerifier.confirmed(before = null, after = "x", committed = "x"))
        assertNull(InsertionVerifier.confirmed(before = "x", after = null, committed = "x"))
    }

    @Test
    fun `empty before and after with no commit is false`() {
        assertEquals(false, InsertionVerifier.confirmed(before = "", after = "", committed = "x"))
    }
}
