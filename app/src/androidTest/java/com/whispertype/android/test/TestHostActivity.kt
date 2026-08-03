package com.whispertype.android.test

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Plain (non-Compose) host activity with programmatic text fields and controls,
 * used by the instrumented test suite to exercise focus, rotation, and keyboard behavior.
 */
class TestHostActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        status = TextView(this).apply {
            tag = "status_text"
            text = "focused:none"
        }
        root.addView(status)

        val single = editText("field_single", InputType.TYPE_CLASS_TEXT)
        val multiline = editText(
            "field_multiline",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        val password = editText(
            "field_password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val pin = editText(
            "field_pin",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val numeric = editText("field_numeric", InputType.TYPE_CLASS_NUMBER)
        val selection = editText("field_selection", InputType.TYPE_CLASS_TEXT).apply {
            setText("select me")
            setSelection(3, 7)
        }
        listOf(single, multiline, password, pin, numeric, selection).forEach { root.addView(it) }

        webView = WebView(this).apply {
            tag = "field_web"
            settings.javaScriptEnabled = true
            loadDataWithBaseURL(null, WEB_HTML, "text/html", "utf-8", null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, WEB_HEIGHT_PX)
        }
        root.addView(webView)

        root.addView(button("btn_focus_single", "Focus single") { single.requestFocus() })
        root.addView(button("btn_focus_password", "Focus password") { password.requestFocus() })
        root.addView(button("btn_focus_multiline", "Focus multiline") { multiline.requestFocus() })
        root.addView(button("btn_hide_keyboard", "Hide keyboard") { hideKeyboard() })
        root.addView(button("btn_rotate", "Rotate") { toggleOrientation() })
        root.addView(button("btn_open_settings", "Open settings app") { openSettings() })
    }

    /** Focuses the contenteditable element inside the WebView. */
    fun focusWebEditable() {
        webView.evaluateJavascript("document.getElementById('editable').focus();", null)
    }

    private fun editText(tag: String, inputType: Int): EditText {
        val view = EditText(this).apply {
            setTag(tag)
            this.inputType = inputType
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    status.text = "focused:$tag"
                } else if (findFocus() !is EditText && findFocus() !is WebView) {
                    status.text = "focused:none"
                }
                Log.d(LOG_TAG, "focus change: $tag")
            }
        }
        return view
    }

    private fun button(tag: String, text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            setTag(tag)
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun hideKeyboard() {
        val focused = currentFocus ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    private fun toggleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    companion object {
        private const val LOG_TAG = "WhisperTypeTestHost"
        private const val WEB_HEIGHT_PX = 400
        private const val WEB_HTML =
            "<html><body><div id='editable' contenteditable='true'>edit here</div></body></html>"
    }
}
