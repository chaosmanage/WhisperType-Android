package com.whispertype.android.overlay

/** Regions a touch can land on over the keyboard area. */
enum class TouchRegion { DOCK, PANEL, OUTSIDE }

/** Pure hit-testing for the overlay windows. */
object OverlayTouchPolicy {

    /** Returns true when the point ([x], [y]) falls inside [rect]. */
    fun contains(rect: IntRectPx, x: Int, y: Int): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    /** Candidate hit regions; null entries are hidden windows. */
    data class TouchTargets(
        val dock: IntRectPx?,
        val panel: IntRectPx?,
    )

    /** Classifies a point, giving the dock priority over the panel when they overlap. */
    fun classify(x: Int, y: Int, targets: TouchTargets): TouchRegion = when {
        targets.dock != null && contains(targets.dock, x, y) -> TouchRegion.DOCK
        targets.panel != null && contains(targets.panel, x, y) -> TouchRegion.PANEL
        else -> TouchRegion.OUTSIDE
    }
}
