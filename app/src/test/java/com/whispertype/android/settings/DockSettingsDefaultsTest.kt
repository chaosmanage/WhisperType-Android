package com.whispertype.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockSettingsDefaultsTest {

    @Test
    fun dockAppearanceDefaultsMatchContract() {
        val defaults = DockSettings()
        assertEquals(DockPosition.CENTER, defaults.position)
        assertEquals(DockSize.STANDARD, defaults.size)
        assertEquals(DockOverlap.MOSTLY_OVER_KEYBOARD, defaults.overlapMode)
        assertEquals(1f, defaults.opacity, 0f)
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(0xFF7C6CFF, defaults.accentColorArgb)
    }

    @Test
    fun dockFeedbackDefaultsMatchContract() {
        val defaults = DockSettings()
        assertEquals(true, defaults.hapticsEnabled)
        assertEquals(false, defaults.soundsEnabled)
        assertEquals(false, defaults.showElapsedTime)
        assertEquals(CancelSide.RIGHT, defaults.cancelSide)
    }

    @Test
    fun disabledAppsDefaultsToEmpty() {
        assertTrue(DockSettings().disabledApps.isEmpty())
    }
}
