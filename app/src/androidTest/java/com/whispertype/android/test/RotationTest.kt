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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that rotation leaves the dictation state in an acceptable state without
 * crashing. Rotation may briefly drop focus, so both DockedReady and NoEditableFocus
 * are accepted. Skipped via Assume when the accessibility service is not enabled.
 */
@RunWith(AndroidJUnit4::class)
class RotationTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<TestHostActivity> = ActivityScenarioRule(TestHostActivity::class.java)

    @Before
    fun assumeAccessibilityServiceEnabled() {
        assumeTrue("Accessibility service not enabled", WhisperTypeAccessibilityService.shared != null)
    }

    /** After rotation the state must settle on DockedReady or NoEditableFocus. */
    @Test
    fun rotation_keepsDockOrDropsToNoFocus() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        assumeTrue("dock did not appear before rotation", awaitState(5_000) { it is DictationState.DockedReady })
        scenarioRule.scenario.recreate()
        val settled = awaitState(3_000) { state ->
            state is DictationState.DockedReady || state is DictationState.NoEditableFocus
        }
        assertTrue("unexpected state after rotation: ${currentState()}", settled)
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
