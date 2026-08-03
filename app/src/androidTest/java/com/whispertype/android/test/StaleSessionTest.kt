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
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that a stale dictation session never inserts text after focus moves
 * to another field. Requires configured microphone permission and Gemini key;
 * the test is skipped via Assume when begin returns false.
 */
@RunWith(AndroidJUnit4::class)
class StaleSessionTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<TestHostActivity> = ActivityScenarioRule(TestHostActivity::class.java)

    @Before
    fun assumeAccessibilityServiceEnabled() {
        assumeTrue("Accessibility service not enabled", WhisperTypeAccessibilityService.shared != null)
    }

    /** After begin, switching focus must cancel the session before any Success state. */
    @Test
    fun staleSession_neverSucceeds() {
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
            onView(withTagValue(equalTo("btn_focus_multiline"))).perform(scrollTo(), click())
            assertFalse("stale session must never reach Success", awaitSettling(SETTLE_TIMEOUT_MILLIS))
        } else {
            assumeTrue("stale-session test requires configured mic and Gemini key", false)
        }
    }

    private fun awaitSettling(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val state = currentState()
            if (state is DictationState.Success) {
                return true
            }
            if (state is DictationState.Cancelled || state is DictationState.NoEditableFocus) {
                return false
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
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

    private fun currentState(): DictationState {
        val bridge = currentBridge() ?: return DictationState.Unavailable
        return bridge.state.value
    }

    private fun testToken(): TargetToken = TargetToken(
        sessionId = "stale-test-session",
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
        private const val SETTLE_TIMEOUT_MILLIS = 10_000L
    }
}
