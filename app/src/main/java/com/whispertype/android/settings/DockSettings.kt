package com.whispertype.android.settings

/** User-configurable dock appearance and behavior (Implementation Plan §7.3, §10.2). */
data class DockSettings(
    val position: DockPosition = DockPosition.CENTER,
    val size: DockSize = DockSize.STANDARD,
    val overlapMode: DockOverlap = DockOverlap.MOSTLY_OVER_KEYBOARD,
    val opacity: Float = 1f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColorArgb: Long = 0xFF7C6CFF,
    val hapticsEnabled: Boolean = true,
    val soundsEnabled: Boolean = false,
    val showElapsedTime: Boolean = false,
    val cancelSide: CancelSide = CancelSide.RIGHT,
    val disabledApps: Set<String> = emptySet(),
)

enum class DockPosition { LEFT, CENTER, RIGHT }

enum class DockSize { COMPACT, STANDARD, LARGE }

enum class DockOverlap { MOSTLY_OVER_KEYBOARD, MOSTLY_ABOVE_KEYBOARD }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CancelSide { LEFT, RIGHT }
