package com.whispertype.android.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [TargetEligibility.eligible] vs the hotkey-relaxed
 * [TargetEligibility.eligibleForHotkey] gates.
 */
class TargetEligibilityTest {

    private fun eligibility(keyboardVisible: Boolean) = TargetEligibility(
        serviceConnected = true,
        editorFocused = true,
        editorSecure = false,
        editorUncertain = false,
        keyboardVisible = keyboardVisible,
        microphoneGranted = true,
        apiKeyConfigured = true,
        appEnabled = true,
        sessionActive = false,
    )

    @Test
    fun `soft keyboard required for bubble eligibility but not for hotkey`() {
        val withoutKeyboard = eligibility(keyboardVisible = false)
        assertFalse(withoutKeyboard.eligible)
        assertTrue(withoutKeyboard.eligibleForHotkey)
    }

    @Test
    fun `with soft keyboard both gates pass`() {
        val withKeyboard = eligibility(keyboardVisible = true)
        assertTrue(withKeyboard.eligible)
        assertTrue(withKeyboard.eligibleForHotkey)
    }

    @Test
    fun `secure editor fails closed on both gates`() {
        val secure = eligibility(keyboardVisible = false).copy(editorSecure = true)
        assertFalse(secure.eligible)
        assertFalse(secure.eligibleForHotkey)
    }

    @Test
    fun `uncertain editor fails closed on both gates`() {
        val uncertain = eligibility(keyboardVisible = false).copy(editorUncertain = true)
        assertFalse(uncertain.eligible)
        assertFalse(uncertain.eligibleForHotkey)
    }

    @Test
    fun `no focused editor fails closed on both gates`() {
        val noFocus = eligibility(keyboardVisible = false).copy(editorFocused = false)
        assertFalse(noFocus.eligible)
        assertFalse(noFocus.eligibleForHotkey)
    }
}
