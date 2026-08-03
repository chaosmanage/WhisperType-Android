package com.whispertype.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.ThemeMode
import com.whispertype.android.ui.theme.WhisperTypeTheme

/**
 * Owns the dock and voice-panel accessibility overlay windows.
 * Must be called from the main thread.
 */
class OverlayWindowController(
    private val context: Context,
    private val onDockTap: () -> Unit,
    private val onStop: () -> Unit,
    private val onCancel: () -> Unit,
    private val onCopy: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val dockView = ComposeView(context)
    private val panelView = ComposeView(context)
    private val dockSettings = mutableStateOf(DockSettings())
    private val panelUi = mutableStateOf(PanelUiState())

    private var dockParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var lastDockRect: IntRectPx? = null
    private var lastPanelRect: IntRectPx? = null

    init {
        dockView.setContent { DockContent() }
        panelView.setContent { PanelContent() }
    }

    /** Applies the latest geometry, presentation, and settings to both windows. */
    fun update(
        layout: OverlayGeometryCalculator.OverlayLayout,
        presentation: OverlayPresentation,
        settings: DockSettings,
    ) {
        dockSettings.value = settings
        panelUi.value = PanelUiState.fromPresentation(presentation)
        applyDock(if (presentation.showDock) layout.dock else null)
        applyPanel(if (presentation.showPanel || presentation.showCopyUi || presentation.showError) layout.panel else null)
    }

    /** Removes both overlay windows. */
    fun removeAll() {
        removeDock()
        removePanel()
    }

    private fun applyDock(rect: IntRectPx?) = when {
        rect == null -> removeDock()
        dockParams == null -> addDock(rect)
        lastDockRect != rect -> updateDock(rect)
        else -> Unit
    }

    private fun applyPanel(rect: IntRectPx?) = when {
        rect == null -> removePanel()
        panelParams == null -> addPanel(rect)
        lastPanelRect != rect -> updatePanel(rect)
        else -> Unit
    }

    private fun addDock(rect: IntRectPx) {
        val params = windowParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            x = rect.left
            y = rect.top
        }
        safely { windowManager.addView(dockView, params) }
        dockParams = params
        lastDockRect = rect
    }

    private fun updateDock(rect: IntRectPx) {
        val params = dockParams ?: return
        params.x = rect.left
        params.y = rect.top
        safely { windowManager.updateViewLayout(dockView, params) }
        lastDockRect = rect
    }

    private fun removeDock() {
        dockParams ?: return
        safely { windowManager.removeView(dockView) }
        dockParams = null
        lastDockRect = null
    }

    private fun addPanel(rect: IntRectPx) {
        val params = windowParams(rect.width, rect.height).apply {
            x = rect.left
            y = rect.top
        }
        safely { windowManager.addView(panelView, params) }
        panelParams = params
        lastPanelRect = rect
    }

    private fun updatePanel(rect: IntRectPx) {
        val params = panelParams ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width
        params.height = rect.height
        safely { windowManager.updateViewLayout(panelView, params) }
        lastPanelRect = rect
    }

    private fun removePanel() {
        panelParams ?: return
        safely { windowManager.removeView(panelView) }
        panelParams = null
        lastPanelRect = null
    }

    private fun windowParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Overlay window rejected (${e.javaClass.simpleName})")
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Overlay display unavailable (${e.javaClass.simpleName})")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Overlay window update rejected (${e.javaClass.simpleName})")
        }
    }

    @Composable
    private fun DockContent() {
        val settings = dockSettings.value
        WhisperTypeTheme(darkTheme = resolvedDarkTheme(settings)) {
            DockedMicOverlay(settings = settings, onTap = onDockTap)
        }
    }

    @Composable
    private fun PanelContent() {
        val settings = dockSettings.value
        WhisperTypeTheme(darkTheme = resolvedDarkTheme(settings)) {
            VoicePanelOverlay(
                ui = panelUi.value,
                settings = settings,
                onStop = onStop,
                onCancel = onCancel,
                onCopy = onCopy,
                onDismiss = onDismiss,
            )
        }
    }

    @Composable
    private fun resolvedDarkTheme(settings: DockSettings): Boolean = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    private companion object {
        const val TAG = "WhisperTypeOverlay"
    }
}
