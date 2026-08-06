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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * 0.4.2 real-time waveform for the recording pill (Wispr-style): a flat line on
 * silence that livens into a rolling wave as the user speaks. [amplitude] is the
 * smoothed mic level in [0, 1]. The wave height scales with the amplitude and the
 * phase rolls continuously, so silence reads as a flat line and speech as a
 * moving wave. Stateless and side-effect free.
 */
@Composable
fun RealTimeWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    lineColor: Color = Color.White.copy(alpha = 0.9f),
    amplitudeScale: Float = 3f,
) {
    val animated by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "waveformAmplitude",
    )
    val transition = rememberInfiniteTransition(label = "wavePhase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    Canvas(modifier = modifier) {
        val w = size.toPx()
        val h = size.toPx()
        val midY = h / 2f
        if (animated <= 0.005f) {
            // Flat line on silence.
            drawLine(
                color = Color(0xFF4A5A57),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val peak = (h / 2f) * 0.85f * animated.coerceIn(0f, 1f) * (amplitudeScale / 3f)
        val cycles = 3
        val points = (0..WAVE_STEPS).map { i ->
            val x = w * i / WAVE_STEPS.toFloat()
            val y = midY - peak * sin(phase + cycles * 2f * Math.PI.toFloat() * x / w)
            Offset(x, y)
        }
        for (i in 1 until points.size) {
            drawLine(
                color = lineColor,
                start = points[i - 1],
                end = points[i],
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val WAVE_STEPS = 40
