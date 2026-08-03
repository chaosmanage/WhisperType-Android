package com.whispertype.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Primary = Color(0xFF7C6CFF)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE4E0FF)
val OnPrimaryContainer = Color(0xFF1D1177)
val Secondary = Color(0xFF00696E)
val SecondaryContainer = Color(0xFF9CF1F6)
val BackgroundLight = Color(0xFFFDF8FF)
val BackgroundDark = Color(0xFF121318)
val SurfaceLight = Color(0xFFFDF8FF)
val SurfaceDark = Color(0xFF121318)
val ErrorLight = Color(0xFFBA1A1A)
val ErrorDark = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    background = BackgroundLight,
    surface = SurfaceLight,
    error = ErrorLight,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF34298F),
    primaryContainer = Color(0xFF4B41A6),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFF80D5DA),
    secondaryContainer = Color(0xFF005053),
    background = BackgroundDark,
    surface = SurfaceDark,
    error = ErrorDark,
)

@Composable
fun WhisperTypeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
