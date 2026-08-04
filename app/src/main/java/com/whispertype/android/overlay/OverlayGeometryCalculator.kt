package com.whispertype.android.overlay

import android.graphics.Rect
import com.whispertype.android.settings.DockOverlap
import com.whispertype.android.settings.DockPosition
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.DockSize

/** Axis-aligned integer rectangle in screen pixel coordinates. */
data class IntRectPx(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2

    /** Converts to an Android [Rect] for WindowManager layout parameters. */
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

/** Pure geometry for placing the dock and voice panel over the keyboard. */
object OverlayGeometryCalculator {

    /**
     * Places the dock against the keyboard's top edge, clamped to the display.
     * [density] converts the dp constants to pixels.
     */
    fun dockRect(
        imeBounds: IntRectPx,
        displayBounds: IntRectPx,
        settings: DockSettings,
        density: Float,
    ): IntRectPx {
        val size = sizePx(settings.size, density)
        val margin = (Constants.SAFE_EDGE_MARGIN_DP * density).toInt()
        val gap = (Constants.DOCK_VERTICAL_GAP_DP * density).toInt()

        val rawLeft = when (settings.position) {
            DockPosition.LEFT -> displayBounds.left + margin
            DockPosition.CENTER -> imeBounds.centerX - size / 2
            DockPosition.RIGHT -> displayBounds.right - margin - size
        }
        val maxLeft = (displayBounds.right - size).coerceAtLeast(displayBounds.left)
        val left = rawLeft.coerceIn(displayBounds.left, maxLeft)

        val rawTop = when (settings.overlapMode) {
            DockOverlap.MOSTLY_OVER_KEYBOARD ->
                imeBounds.top + (size * Constants.HALF_OVER_FRACTION).toInt() - size
            DockOverlap.MOSTLY_ABOVE_KEYBOARD -> imeBounds.top - gap - size
        }
        val top = rawTop.coerceAtLeast(displayBounds.top)
        return IntRectPx(left, top, left + size, top + size)
    }

    /** The voice panel covers the keyboard bounds exactly. */
    fun voicePanelRect(imeBounds: IntRectPx): IntRectPx = imeBounds

    /**
     * Dock placement used when no IME window has been detected yet. Mirrors Wispr's
     * persistent-bubble stance: the dock stays visible and tappable so dictation is
     * always available once a field is focused, then snaps to the keyboard edge as
     * soon as IME bounds are known. Vertically centered on the display.
     */
    fun dockRectFallback(
        displayBounds: IntRectPx,
        settings: DockSettings,
        density: Float,
    ): IntRectPx {
        val size = sizePx(settings.size, density)
        val margin = (Constants.SAFE_EDGE_MARGIN_DP * density).toInt()
        val maxLeft = (displayBounds.right - size).coerceAtLeast(displayBounds.left)
        val left = when (settings.position) {
            DockPosition.LEFT -> (displayBounds.left + margin).coerceIn(displayBounds.left, maxLeft)
            DockPosition.CENTER -> (displayBounds.centerX - size / 2).coerceIn(displayBounds.left, maxLeft)
            DockPosition.RIGHT -> (displayBounds.right - margin - size).coerceIn(displayBounds.left, maxLeft)
        }
        val top = ((displayBounds.top + displayBounds.bottom - size) / 2).coerceAtLeast(displayBounds.top)
        return IntRectPx(left, top, left + size, top + size)
    }

    /** Panel placement used when the keyboard window is unknown: bottom ~45% of the display. */
    fun voicePanelRectFallback(displayBounds: IntRectPx): IntRectPx {
        val top = displayBounds.top + (displayBounds.height * FALLBACK_PANEL_FRACTION).toInt()
        return IntRectPx(displayBounds.left, top, displayBounds.right, displayBounds.bottom)
    }

    /** Pixel rectangles for the dock and voice panel windows. */
    data class OverlayLayout(
        val dock: IntRectPx?,
        val panel: IntRectPx?,
    )

    private fun sizePx(size: DockSize, density: Float): Int = when (size) {
        DockSize.COMPACT -> (Constants.DOCK_SIZE_COMPACT_DP * density).toInt()
        DockSize.STANDARD -> (Constants.DOCK_SIZE_STANDARD_DP * density).toInt()
        DockSize.LARGE -> (Constants.DOCK_SIZE_LARGE_DP * density).toInt()
    }

    private object Constants {
        const val DOCK_SIZE_COMPACT_DP = 48
        const val DOCK_SIZE_STANDARD_DP = 56
        const val DOCK_SIZE_LARGE_DP = 64
        const val SAFE_EDGE_MARGIN_DP = 12
        const val DOCK_VERTICAL_GAP_DP = 4
        const val HALF_OVER_FRACTION = 0.5f
    }

    private const val FALLBACK_PANEL_FRACTION = 0.45f
}
