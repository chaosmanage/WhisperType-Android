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
import kotlin.math.sqrt
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

private const val PHASE_TICK_MS = 50L
private const val PHASE_CYCLE_MS = 550L
private val FULL_TURN = (Math.PI * 2).toFloat()
private val PHASE_STEP = FULL_TURN * PHASE_TICK_MS / PHASE_CYCLE_MS

/**
 * Centered soundwave for the Studio hairline pill: mirrored bars around the
 * vertical midpoint, teal by default, with a longer amplitude tween for fluid
 * motion. Phase ticks stay at ~20 Hz to avoid logcat frame-rate spam.
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
        animationSpec = tween(durationMillis = 160),
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
        if (animated <= WAVEFORM_SILENCE_THRESHOLD && !isListening) return@Canvas

        val w = size.width
        val h = size.height
        val midY = h * 0.5f
        val level = sqrt(animated.coerceIn(0f, 1f))
        val idleFloor = if (isListening) 0.22f else 0f
        val maxHalf = h * 0.48f * level.coerceAtLeast(idleFloor)

        val barCount = 32
        val step = w / barCount
        val barWidth = (step * 0.55f).coerceAtLeast(1.5f)

        for (i in 0 until barCount) {
            val x = step * (i + 0.5f)
            val v1 = sin(phase * 1.0f + i * 0.55f)
            val v2 = sin(phase * 0.65f + i * 1.1f)
            val v3 = sin(phase * 1.4f + i * 0.25f)
            val mix = abs((v1 + v2 + v3) / 3f)
            val half = (0.22f + 0.78f * mix) * maxHalf
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
