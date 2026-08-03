package com.whispertype.android.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Converts PCM16 audio into a 0..1 amplitude value, throttled to
 * [throttleMillis] so callers only see a change at most twice per throttle
 * period. RMS is normalized by 32000 instead of 32768 so full-scale input
 * peaks at ~1.0 with a little headroom.
 */
class WaveformMeter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttleMillis: Long = 50,
) {

    private var lastAmplitude = 0f
    private var pendingAmplitude = 0f

    /** Far from [Long.MIN_VALUE] so the first push delta cannot overflow. */
    private var lastUpdateMillis = Long.MIN_VALUE / 2

    /**
     * Updates the amplitude, applying the peak of any pushes received while
     * throttled.
     */
    fun push(chunk: AudioChunk) {
        val amplitude = amplitudeOf(chunk.pcm16Bytes)
        val now = clock()
        if (now - lastUpdateMillis >= throttleMillis) {
            lastAmplitude = max(pendingAmplitude, amplitude)
            pendingAmplitude = 0f
            lastUpdateMillis = now
        } else {
            pendingAmplitude = max(pendingAmplitude, amplitude)
        }
    }

    /** The last published amplitude, 0f before the first push. */
    fun lastAmplitude(): Float = lastAmplitude

    /** Clears both the published amplitude and any pending peak. */
    fun reset() {
        lastAmplitude = 0f
        pendingAmplitude = 0f
    }

    private fun amplitudeOf(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        var sumSquares = 0L
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
            sumSquares += sample.toLong() * sample
            index += 2
        }
        val meanSquare = sumSquares.toDouble() / (bytes.size / 2)
        val rms = sqrt(meanSquare).toFloat()
        return (rms / 32000f).coerceIn(0f, 1f)
    }
}
