package com.whispertype.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
 *  light/dark app-screen scheme). */
object WhisperTypeColors {
    val Surface = Color(0xFF141414)
    val SurfaceRaised = Color(0xFF1E1E1E)
    val OnSurface = Color(0xFFF5F3F0)
    val RecordingAccent = Color(0xFFE8593C)
    val IdleAccent = Color(0xFFF5F3F0)
    val ErrorAccent = Color(0xFFFF6B5E)
    val SuccessAccent = Color(0xFF5BE39A)
}

/** Light emerald-teal scheme (default). */
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

/** 0.4.2 dark emerald-teal scheme. */
private val WhisperTypeDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5EE0C4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFA7F0E2),
    secondary = Color(0xFFB2CCC5),
    onSecondary = Color(0xFF1D352F),
    background = Color(0xFF0F1413),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0F1413),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390),
    error = Color(0xFFFFB4AB),
)

@Composable
fun WhisperTypeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WhisperTypeDarkColorScheme else WhisperTypeColorScheme,
        content = content,
    )
}
