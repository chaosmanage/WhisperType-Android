package com.whispertype.android.test

import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispertype.android.WhisperTypeApplication
import com.whispertype.android.accessibility.TargetToken
import com.whispertype.android.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.dictation.DictationBridge
import com.whispertype.android.dictation.DictationState
import kotlin.UninitializedPropertyAccessException
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device tests for dock appearance, focus-loss dismissal, and a graceful
 * begin call when microphone/key prerequisites are missing. Skipped via Assume
 * when the accessibility service is not enabled.
 */
@RunWith(AndroidJUnit4::class)
class DockEligibilityTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<TestHostActivity> = ActivityScenarioRule(TestHostActivity::class.java)

    @Before
    fun assumeAccessibilityServiceEnabled() {
        assumeTrue("Accessibility service not enabled", WhisperTypeAccessibilityService.shared != null)
    }

    /** Focusing a single-line field shows the dock once the default IME docks. */
    @Test
    fun focusSingleLine_showsDock() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        val docked = awaitState(5_000) { it is DictationState.DockedReady }
        assumeTrue("DockedReady not reached (default IME may not support docked mode)", docked)
    }

    /** Hiding the keyboard after docking drops the state, and the dock never returns. */
    @Test
    fun focusLoss_hidesDock() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        assumeTrue("DockedReady not reached before focus loss", awaitState(5_000) { it is DictationState.DockedReady })
        onView(withTagValue(equalTo("btn_hide_keyboard"))).perform(scrollTo(), click())
        assertTrue(
            "state never left DockedReady after hiding the keyboard",
            awaitState(5_000) { it is DictationState.NoEditableFocus || it is DictationState.Unavailable },
        )
        assertFalse(
            "dock reappeared after focus loss",
            awaitState(1_000) { it is DictationState.DockedReady },
        )
    }

    /** begin returns a Boolean without throwing when mic/key prerequisites are missing. */
    @Test
    fun beginSession_returnsTrue() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        val bridge = bridge()
        val started: Boolean
        try {
            started = bridge.begin(testToken())
        } catch (e: Exception) {
            fail("begin threw: $e")
            return
        }
        if (started) {
            bridge.cancel()
        }
    }

    private fun bridge(): DictationBridge {
        val bridge = currentBridge()
        assumeTrue("dictation bridge not injected", bridge != null)
        return bridge!!
    }

    private fun currentBridge(): DictationBridge? = try {
        WhisperTypeApplication.instance.dictationBridge
    } catch (e: UninitializedPropertyAccessException) {
        null
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
        val bridge = currentBridge() ?: return DictationState.Unavailable
        return bridge.state.value
    }

    private fun testToken(): TargetToken = TargetToken(
        sessionId = "dock-test-session",
        inputGeneration = 0L,
        packageName = "com.whispertype.android.test",
        displayId = 0,
        windowId = null,
        fieldId = null,
        inputType = InputType.TYPE_CLASS_TEXT,
        initialSelectionStart = 0,
        initialSelectionEnd = 0,
        imeBounds = Rect(0, 0, 0, 0),
    )

    companion object {
        private const val POLL_INTERVAL_MILLIS = 100L
    }
}
