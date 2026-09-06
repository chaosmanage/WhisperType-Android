package com.whispertype.android.ui.waveform

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

internal const val WAVEFORM_SILENCE_THRESHOLD = 0.005f

/** Sanitizes microphone levels before they reach Compose animation/drawing. */
internal fun normalizedWaveformAmplitude(amplitude: Float): Float =
    if (amplitude.isFinite()) amplitude.coerceIn(0f, 1f) else 0f

/** Collapses microphone-floor noise to one stable zero target. */
internal fun effectiveWaveformAmplitude(amplitude: Float): Float =
    normalizedWaveformAmplitude(amplitude).takeIf { it > WAVEFORM_SILENCE_THRESHOLD } ?: 0f

/** The rolling phase is useful only while live audio is visibly above silence. */
internal fun shouldAnimateWaveform(amplitude: Float, isListening: Boolean): Boolean =
    isListening && effectiveWaveformAmplitude(amplitude) > 0f

/** True when the pill should show the resting flat line instead of bars. */
internal fun shouldDrawFlatWaveform(animatedAmplitude: Float): Boolean =
    animatedAmplitude <= WAVEFORM_SILENCE_THRESHOLD

private const val PHASE_TICK_MS = 50L
private const val PHASE_CYCLE_MS = 550L
private val FULL_TURN = (Math.PI * 2).toFloat()
private val PHASE_STEP = FULL_TURN * PHASE_TICK_MS / PHASE_CYCLE_MS

/**
 * Centered soundwave for the Studio hairline pill: a flat resting line while
 * silent, animated mirrored bars while speaking. Phase ticks stay at ~20 Hz.
 */
@Composable
fun RealTimeWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF5EE0C4),
    isListening: Boolean = true,
) {
    val targetAmplitude = effectiveWaveformAmplitude(amplitude)
    val animated by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 140),
        label = "waveformAmplitude",
    )

    val animate = shouldAnimateWaveform(targetAmplitude, isListening)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            while (true) {
                phase = (phase + PHASE_STEP) % FULL_TURN
                delay(PHASE_TICK_MS)
            }
        } else {
            phase = 0f
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h * 0.5f
        val insetX = 4.dp.toPx()

        if (shouldDrawFlatWaveform(animated)) {
            drawLine(
                color = lineColor.copy(alpha = 0.9f),
                start = Offset(insetX, midY),
                end = Offset(w - insetX, midY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val level = animated.coerceIn(0f, 1f)
        // Use most of the lane height at full voice level.
        val maxHalf = h * 0.49f * level

        val barCount = 32
        val step = w / barCount
        val barWidth = (step * 0.55f).coerceAtLeast(1.5f)

        for (i in 0 until barCount) {
            val x = step * (i + 0.5f)
            val v1 = sin(phase * 1.0f + i * 0.55f)
            val v2 = sin(phase * 0.65f + i * 1.1f)
            val v3 = sin(phase * 1.4f + i * 0.25f)
            val mix = abs((v1 + v2 + v3) / 3f)
            val half = mix * maxHalf
            if (half <= 0.5f) continue
            drawLine(
                color = lineColor,
                start = Offset(x, midY - half),
                end = Offset(x, midY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
