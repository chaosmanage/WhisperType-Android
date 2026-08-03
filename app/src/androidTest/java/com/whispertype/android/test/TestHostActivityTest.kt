package com.whispertype.android.test

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the host activity's field inventory, focus switching, rotation,
 * and keyboard-hide control. These tests are emulator-safe.
 */
@RunWith(AndroidJUnit4::class)
class TestHostActivityTest {

    @get:Rule
    val scenarioRule: ActivityScenarioRule<TestHostActivity> = ActivityScenarioRule(TestHostActivity::class.java)

    /** All declared fields are present and addressable by tag. */
    @Test
    fun testAllFieldsPresent() {
        scenarioRule.scenario.onActivity { activity ->
            for (tag in FIELD_TAGS) {
                activity.field<View>(tag)
            }
        }
    }

    /** Each focus button focuses its field and updates the status text. */
    @Test
    fun testFocusSwitch() {
        scenarioRule.scenario.onActivity { activity ->
            val status = activity.field<TextView>("status_text")
            assertEquals("focused:none", status.text.toString())
        }
        val expectations = mapOf(
            "btn_focus_single" to "field_single",
            "btn_focus_password" to "field_password",
            "btn_focus_multiline" to "field_multiline",
        )
        for ((buttonTag, fieldTag) in expectations) {
            onView(withTagValue(equalTo(buttonTag))).perform(scrollTo(), click())
            scenarioRule.scenario.onActivity { activity ->
                val field = activity.field<EditText>(fieldTag)
                assertTrue("$fieldTag should hold focus", field.isFocused)
                val status = activity.field<TextView>("status_text")
                assertEquals("focused:$fieldTag", status.text.toString())
            }
        }
    }

    /** Recreating the activity (as rotation does) must not lose the field inventory. */
    @Test
    fun testRotation() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        scenarioRule.scenario.recreate()
        scenarioRule.scenario.onActivity { activity ->
            val field = activity.field<EditText>("field_single")
            assertTrue("field_single should remain focusable after rotation", field.isFocusableInTouchMode)
            activity.field<View>("status_text")
        }
    }

    /** The hide-keyboard control completes without crashing and the activity stays resumed. */
    @Test
    fun testKeyboardHideButton() {
        onView(withTagValue(equalTo("btn_focus_single"))).perform(scrollTo(), click())
        onView(withTagValue(equalTo("btn_hide_keyboard"))).perform(scrollTo(), click())
        assertEquals(Lifecycle.State.RESUMED, scenarioRule.scenario.state)
    }

    private fun <T : View> Activity.field(tag: String): T {
        val content = checkNotNull(findViewById<View>(android.R.id.content)) { "content view missing" }
        return checkNotNull(content.findViewByTag<T>(tag)) { "view with tag '$tag' missing" }
    }

    companion object {
        private val FIELD_TAGS = listOf(
            "field_single",
            "field_multiline",
            "field_password",
            "field_pin",
            "field_numeric",
            "field_selection",
            "field_web",
        )
    }
}
