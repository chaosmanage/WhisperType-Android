package com.whispertype.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object WhisperTypeColors {
    val Surface = Color(0xFF141414)
    val SurfaceRaised = Color(0xFF1E1E1E)
    val OnSurface = Color(0xFFF5F3F0)
    val RecordingAccent = Color(0xFFE8593C)
    val IdleAccent = Color(0xFFF5F3F0)
    val ErrorAccent = Color(0xFFE8593C)
    val SuccessAccent = Color(0xFF4CAF77)
}

/**
 * Original WhisperType theme: deep neutral surface, a single warm recording
 * accent, soft rounded surfaces, restrained elevation. System font fallback.
 */
@Composable
fun WhisperTypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}