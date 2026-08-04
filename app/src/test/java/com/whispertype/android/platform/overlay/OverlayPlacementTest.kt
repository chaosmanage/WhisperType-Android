package com.whispertype.android.platform.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry tests for [OverlayPlacement]; no Android runtime required. */
class OverlayPlacementTest {

    @Test
    fun `default min touch target is at least 48dp`() {
        assertTrue(OverlayPlacement().minTouchDp >= 48f)
        assertTrue(OverlayPlacement().isValid)
    }

    @Test
    fun `default margin is non-negative`() {
        assertTrue(OverlayPlacement().marginDp >= 0f)
    }

    @Test
    fun `default anchors to top end edge`() {
        assertTrue(OverlayPlacement().edge == OverlayEdge.TopEnd)
    }

    @Test
    fun `custom min touch equal to 48 is valid`() {
        assertTrue(OverlayPlacement(minTouchDp = 48f).isValid)
    }

    @Test
    fun `min touch below 48 is invalid`() {
        assertFalse(OverlayPlacement(minTouchDp = 40f).isValid)
    }

    @Test
    fun `negative margin is invalid`() {
        assertFalse(OverlayPlacement(marginDp = -1f).isValid)
    }

    @Test
    fun `zero margin is valid`() {
        assertTrue(OverlayPlacement(marginDp = 0f).isValid)
    }

    @Test
    fun `zero min touch is invalid`() {
        assertFalse(OverlayPlacement(minTouchDp = 0f).isValid)
    }
}
