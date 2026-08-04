package com.whispertype.android.core.audio

import kotlin.math.sqrt

/**
 * Host-testable amplitude metering for a PCM16 buffer (PRD FR-5).
 *
 * Computes a single throttled scalar in [0, 1] (normalized RMS) from a mono PCM16 byte
 * buffer, used only to drive the UI waveform — never raw audio. Each call is a pure
 * function of its input: no internal state, no smoothing, fully deterministic.
 *
 * "Throttled" here means the caller invokes this once per meter sample at a modest rate
 * (e.g. ~20 Hz per PRD 16.7) and consumes only the returned scalar, never the raw bytes.
 */
object AmplitudeMeter {

    /**
     * Returns the normalized RMS amplitude of [bytes] (mono little-endian PCM16) in
     * [0, 1]. A silent (all-zero) or empty buffer yields `0.0`; a full-scale buffer yields
     * approximately `1.0`. An odd trailing byte (not a full 16-bit sample) is ignored.
     */
    fun normalizedRms(bytes: ByteArray): Float {
        val sampleCount = bytes.size / 2
        if (sampleCount == 0) return 0f

        var sumSquares = 0.0
        var i = 0
        while (i + 1 < bytes.size) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt() and 0xFF
            val sample = ((high shl 8) or low).toShort().toInt() // sign-extended little-endian
            sumSquares += sample.toDouble() * sample.toDouble()
            i += 2
        }

        val rms = sqrt(sumSquares / sampleCount)
        val normalized = rms / FULL_SCALE
        return normalized.toFloat().coerceIn(0f, 1f)
    }

    private const val FULL_SCALE = 32767.0
}
