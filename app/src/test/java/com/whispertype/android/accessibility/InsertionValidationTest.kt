package com.whispertype.android.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function tests for [AccessibilityInsertionController] validation. */
class InsertionValidationTest {

    @Test
    fun validateReturnsNullWhenAllChecksPass() {
        assertNull(AccessibilityInsertionController.validate(emptyList()))
        assertNull(
            AccessibilityInsertionController.validate(
                listOf("SERVICE_GONE" to true, "SCREEN_LOCKED" to true, "SECURE_FIELD" to true),
            ),
        )
    }

    @Test
    fun validateReturnsFirstFailingCode() {
        assertEquals(
            "FOCUS_CHANGED",
            AccessibilityInsertionController.validate(
                listOf(
                    "SERVICE_GONE" to true,
                    "FOCUS_CHANGED" to false,
                    "SECURE_FIELD" to false,
                ),
            ),
        )
        assertEquals("SERVICE_GONE", AccessibilityInsertionController.validate(listOf("SERVICE_GONE" to false)))
    }

    @Test
    fun validateTextRejectsBlankText() {
        assertEquals("EMPTY_RESULT", AccessibilityInsertionController.validateText(""))
        assertEquals("EMPTY_RESULT", AccessibilityInsertionController.validateText("   "))
        assertEquals("EMPTY_RESULT", AccessibilityInsertionController.validateText("\n\t "))
    }

    @Test
    fun validateTextAcceptsNonBlankText() {
        assertNull(AccessibilityInsertionController.validateText("hello"))
        assertNull(AccessibilityInsertionController.validateText("  hello world  "))
    }

    @Test
    fun consumedTrackerConsumesEachIdOnce() {
        val tracker = AccessibilityInsertionController.ConsumedTracker()
        assertTrue(tracker.consume("session-1"))
        assertFalse(tracker.consume("session-1"))
        assertTrue(tracker.isConsumed("session-1"))
        assertFalse(tracker.isConsumed("session-2"))
        assertTrue(tracker.consume("session-2"))
        assertTrue(tracker.isConsumed("session-2"))
    }
}
