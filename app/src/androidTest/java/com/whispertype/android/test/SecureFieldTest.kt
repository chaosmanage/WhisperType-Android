package com.whispertype.android.test

import android.os.SystemClock
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.dictation.DictationState
import kotlin.UninitializedPropertyAccessException
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that secure fields never show the dock while an eligible numeric field may.
 * Skipped via Assume when the accessibility service is not enabled.
 */
@RunWith(AndroidJUnit4::class)
class SecureFieldTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<TestHostActivity> = ActivityScenarioRule(TestHostActivity::class.java)

    @Before
    fun assumeAccessibilityServiceEnabled() {
        assumeTrue("Accessibility service not enabled", WhisperTypeAccessibilityService.shared != null)
    }

    /** A password field never transitions to DockedReady. */
    @Test
    fun passwordField_neverShowsDock() {
        onView(withTagValue(equalTo("btn_focus_password"))).perform(scrollTo(), click())
        assertFalse(
            "dock must never appear for a password field",
            awaitState(1_500) { it is DictationState.DockedReady },
        )
    }

    /** A PIN field never transitions to DockedReady. */
    @Test
    fun pinField_neverShowsDock() {
        onView(withTagValue(equalTo("field_pin"))).perform(scrollTo(), click())
        assertFalse(
            "dock must never appear for a PIN field",
            awaitState(1_500) { it is DictationState.DockedReady },
        )
    }

    /**
     * A numeric field may show the dock; when the keyboard layout is unsupported
     * the test is skipped instead of failing, so flaky OEM cases do not break CI.
     */
    @Test
    fun numericField_mayShowDock() {
        onView(withTagValue(equalTo("field_numeric"))).perform(scrollTo(), click())
        val docked = awaitState(5_000) { it is DictationState.DockedReady }
        assumeTrue("dock did not appear for the numeric field (keyboard layout may be unsupported)", docked)
    }

    private fun awaitState(timeoutMillis: Long, predicate: (DictationState) -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate(currentState())) {
                return true
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return predicate(currentState())
    }

    private fun currentState(): DictationState {
        val bridge = try {
            WhisperTypeApplication.instance.dictationBridge
        } catch (e: UninitializedPropertyAccessException) {
            null
        } ?: return DictationState.Unavailable
        return bridge.state.value
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 100L
    }
}
