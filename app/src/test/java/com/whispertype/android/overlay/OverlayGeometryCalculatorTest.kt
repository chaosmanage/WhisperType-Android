package com.whispertype.android.overlay

import com.whispertype.android.settings.DockOverlap
import com.whispertype.android.settings.DockPosition
import com.whispertype.android.settings.DockSettings
import com.whispertype.android.settings.DockSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for dock/panel placement at density 2f. */
class OverlayGeometryCalculatorTest {

    private val density = 2f
    private val display = IntRectPx(0, 0, 1080, 2400)
    private val ime = IntRectPx(0, 1500, 1080, 2400)
    private val margin = 24
    private val gap = 8

    private fun sizePx(size: DockSize): Int = when (size) {
        DockSize.COMPACT -> 96
        DockSize.STANDARD -> 112
        DockSize.LARGE -> 128
    }

    @Test
    fun centerDockIsCenteredOnImeBounds() {
        val dock = OverlayGeometryCalculator.dockRect(
            ime, display, DockSettings(position = DockPosition.CENTER), density,
        )
        assertEquals(ime.centerX, dock.centerX)
    }

    @Test
    fun leftDockSitsAtDisplayLeftEdgePlusMargin() {
        val dock = OverlayGeometryCalculator.dockRect(
            ime, display, DockSettings(position = DockPosition.LEFT), density,
        )
        assertEquals(display.left + margin, dock.left)
    }

    @Test
    fun rightDockSitsAtDisplayRightEdgeMinusMargin() {
        val dock = OverlayGeometryCalculator.dockRect(
            ime, display, DockSettings(position = DockPosition.RIGHT), density,
        )
        assertEquals(display.right - margin, dock.right)
    }

    @Test
    fun dockSizesMatchDpScaledByDensity() {
        for (size in DockSize.entries) {
            val dock = OverlayGeometryCalculator.dockRect(
                ime, display, DockSettings(size = size), density,
            )
            assertEquals(sizePx(size), dock.width)
            assertEquals(sizePx(size), dock.height)
        }
    }

    @Test
    fun mostlyOverKeyboardHangsHalfOverImeTop() {
        val size = sizePx(DockSize.STANDARD)
        val dock = OverlayGeometryCalculator.dockRect(
            ime, display, DockSettings(overlapMode = DockOverlap.MOSTLY_OVER_KEYBOARD), density,
        )
        assertEquals(ime.top + size / 2, dock.bottom)
        assertTrue(dock.top < ime.top)
    }

    @Test
    fun mostlyAboveKeyboardSitsJustAboveImeTop() {
        val dock = OverlayGeometryCalculator.dockRect(
            ime, display, DockSettings(overlapMode = DockOverlap.MOSTLY_ABOVE_KEYBOARD), density,
        )
        assertEquals(ime.top - gap, dock.bottom)
    }

    @Test
    fun narrowDisplayClampsDockHorizontally() {
        val narrow = IntRectPx(0, 0, 100, 2400)
        val size = sizePx(DockSize.COMPACT)
        val dock = OverlayGeometryCalculator.dockRect(
            ime,
            narrow,
            DockSettings(position = DockPosition.LEFT, size = DockSize.COMPACT),
            density,
        )
        assertEquals(narrow.right - size, dock.left)
        assertEquals(narrow.right, dock.right)
        assertTrue(dock.left >= narrow.left)
    }

    @Test
    fun voicePanelCoversImeBoundsExactly() {
        assertEquals(ime, OverlayGeometryCalculator.voicePanelRect(ime))
    }

    @Test
    fun dockIsAlwaysFullyWithinDisplayBounds() {
        for (position in DockPosition.entries) {
            for (size in DockSize.entries) {
                for (overlap in DockOverlap.entries) {
                    val dock = OverlayGeometryCalculator.dockRect(
                        ime,
                        display,
                        DockSettings(position = position, size = size, overlapMode = overlap),
                        density,
                    )
                    assertTrue("left", dock.left >= display.left)
                    assertTrue("right", dock.right <= display.right)
                    assertTrue("top", dock.top >= display.top)
                    assertTrue("bottom", dock.bottom <= display.bottom)
                }
            }
        }
    }
}
