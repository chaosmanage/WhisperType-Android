package com.whispertype.android.ui.waveform

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.4.2 real-time waveform for the recording pill: a flat baseline on silence
 * that livens into a dancing skyline of peaks and crests as the user speaks.
 * [amplitude] is the smoothed mic level in [0, 1]. The drawing fills the
 * modifier's size.
 *
 * Sensitivity: the amplitude is boosted through a sqrt curve (low sounds still
 * jump — 0.1 -> ~0.32 of full scale), and bars reach nearly the full canvas
 * height, so speech is unmistakable even at a quiet voice.
 *
 * Stateless and side-effect free: each bar's height is a deterministic function
 * of the rolling [phase] (an infinite transition) and its position, scaled by
 * the boosted amplitude.
 */
@Composable
fun RealTimeWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.White.copy(alpha = 0.9f),
) {
    val animated by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "waveformAmplitude",
    )
    val transition = rememberInfiniteTransition(label = "wavePhase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    Canvas(modifier = modifier) {
        val w = this.size.width
        val h = this.size.height
        val baselineY = h * 0.94f
        val fade = Color(0xFF4A5A57)

        // Faint baseline so the bar skyline has an origin.
        drawLine(
            color = fade,
            start = Offset(0f, baselineY),
            end = Offset(w, baselineY),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )

        if (animated <= 0.005f) return@Canvas

        // Sqrt boost: quiet speech still produces a lively, full-height skyline.
        val level = sqrt(animated.coerceIn(0f, 1f))
        val peak = (baselineY - h * 0.06f) * level

        // Bar skyline: three overlapping sine components per bar for organic,
        // non-uniform peaks and crests; height scales with the boosted level.
        val barCount = 16
        val barWidth = (w / barCount) * 0.7f
        val step = w / barCount
        for (i in 0 until barCount) {
            val x = step * (i + 0.5f)
            val v1 = sin(phase * 1.0f + i * 0.9f)
            val v2 = sin(phase * 0.6f + i * 1.9f)
            val v3 = sin(phase * 1.7f + i * 0.4f)
            // Normalize the three-component mix to ~[0, 1] so bars reach near the
            // peak but keep individual variety.
            val mix = (v1 + v2 + v3 + 3f) / 6f
            val barH = (0.12f + 0.88f * mix.coerceIn(0f, 1f)) * peak
            drawLine(
                color = lineColor,
                start = Offset(x, baselineY),
                end = Offset(x, baselineY - barH),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
