package com.whispertype.android.ui.waveform

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whispertype.android.ui.theme.WhisperTypeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A ring of radial bars used in the recording capsule. Bar length is driven by
 * the live [amplitude] (clamped to 0..1) with a gentle sine-based pulse layered
 * on top so adjacent bars breathe while idle. AOT-safe: no preview or reflection.
 */
@Composable
fun CircularWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    size: Dp = 44.dp,
) {
    val safeAmplitude = amplitude.coerceIn(0f, 1f)

    val pulsePhase by rememberInfiniteTransition(label = "CircularWaveformPulse")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "waveformPulsePhase",
        )

    Canvas(modifier = modifier.size(size)) {
        drawRadialWaveform(safeAmplitude, pulsePhase, barCount)
    }
}

private fun DrawScope.drawRadialWaveform(
    amplitude: Float,
    pulsePhase: Float,
    barCount: Int,
) {
    val half = min(size.width, size.height) / 2f
    val center = Offset(half, half)
    val ringRadius = half * 0.42f
    val maxBarLength = half * 0.48f
    val minBarLength = maxBarLength * 0.25f
    val pulseAmplitude = maxBarLength * 0.12f
    val tau = (2f * PI).toFloat()
    val spacing = tau / barCount
    val strokeWidth = max(1.5f, half * 0.09f)

    repeat(barCount) { index ->
        val angle = index * spacing
        val pulse = (sin(pulsePhase * tau + angle) + 1f) / 2f
        val live = minBarLength + amplitude * (maxBarLength - minBarLength)
        val length = (live + pulse * pulseAmplitude).coerceAtMost(maxBarLength)
        val start = Offset(
            x = center.x + ringRadius * cos(angle),
            y = center.y + ringRadius * sin(angle),
        )
        val end = Offset(
            x = start.x + length * cos(angle),
            y = start.y + length * sin(angle),
        )
        drawLine(
            color = WhisperTypeColors.RecordingAccent,
            start = start,
            end = end,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
