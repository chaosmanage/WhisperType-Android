package com.whispertype.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 0.4.2 brand identity: emerald-teal accent replacing the Material default
 *  purple. The launcher logo is monochrome (black/white), so the brand color is
 *  a deliberate complement rather than an extraction. */
object BrandColors {
    val Teal = Color(0xFF0E9B8A)
    val TealDark = Color(0xFF07695D)
    val TealLight = Color(0xFFA7F0E2)
}

/** Colors used directly by the dark persistent overlay (independent of the
 *  light app-screen scheme). */
object WhisperTypeColors {
    val Surface = Color(0xFF141414)
    val SurfaceRaised = Color(0xFF1E1E1E)
    val OnSurface = Color(0xFFF5F3F0)
    val RecordingAccent = Color(0xFFE8593C)
    val IdleAccent = Color(0xFFF5F3F0)
    val ErrorAccent = Color(0xFFE8593C)
    val SuccessAccent = Color(0xFF4CAF77)
}

/** Light emerald-teal scheme shared by every app screen (Settings / History /
 *  Home). One brand identity instead of the Material purple default. */
private val WhisperTypeColorScheme = lightColorScheme(
    primary = BrandColors.Teal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BrandColors.TealLight,
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF181D1C),
    surface = Color(0xFFF8FAF9),
    onSurface = Color(0xFF181D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7978),
    error = Color(0xFFBA1A1A),
)

@Composable
fun WhisperTypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WhisperTypeColorScheme,
        content = content,
    )
}
