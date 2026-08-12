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

/** 0.6.1: low-rate phase tick (was a 60 fps infinite transition). */
private const val PHASE_TICK_MS = 50L

/** Full visual cycle duration preserved from the pre-0.6.1 animation. */
private const val PHASE_CYCLE_MS = 550L

private val FULL_TURN = (Math.PI * 2).toFloat()
private val PHASE_STEP = FULL_TURN * PHASE_TICK_MS / PHASE_CYCLE_MS

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
 * The rolling phase exists only while [isListening] and above the effective
 * silence threshold. At silence (and on the pre-listening Starting surface) the
 * transition leaves composition, so the static baseline consumes no frames.
 */
@Composable
fun RealTimeWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.White.copy(alpha = 0.9f),
    isListening: Boolean = true,
) {
    val targetAmplitude = effectiveWaveformAmplitude(amplitude)
    val animated by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 90),
        label = "waveformAmplitude",
    )

    // 0.6.1: the rolling phase advances at ~20 Hz (50 ms ticks) instead of a
    // 60 fps infinite transition. The mic amplitude only updates at ~16.7 Hz,
    // so a full-rate animation both wastes battery and floods logcat with
    // per-frame View.setRequestedFrameRate spam (which rotated our SESSION DONE
    // diagnostics out of the buffer during recording).
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

        if (animated <= WAVEFORM_SILENCE_THRESHOLD) return@Canvas

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
