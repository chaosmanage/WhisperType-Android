package com.whispertype.android.core.audio

/**
 * 0.7.0: pure, tiny speech/silence scorer for the polishing audio path.
 *
 * Scores one 20 ms PCM16 frame as a normalized voice-activity value in
 * [0, 1]: 0 = pure silence, 1 = full-scale speech. The score is derived from
 * RMS energy with a floor below the WebRTC-style noise floor, so silent
 * stretches yield ~0 and speech rises toward 1 without any state. No audio is
 * retained, so the scorer is privacy-safe and JVM-testable.
 *
 * This is deliberately NOT a full WebRTC VAD port: it provides the frame
 * density signal the polish transport needs, and nothing more.
 */
object NetworkVadScorer {

    private const val LOUDNESS_MULTIPLIER = 10.0
    private const val NORMALIZATION = 32768.0

    /** Scores [pcm16Bytes] (interpreted as little-endian PCM16) in [0, 1]. */
    fun score(pcm16Bytes: ByteArray): Float {
        if (pcm16Bytes.isEmpty()) return 0f
        var energy = 0.0
        var index = 0
        while (index + 1 < pcm16Bytes.size) {
            val sample = (pcm16Bytes[index].toInt() and 0xFF) or (pcm16Bytes[index + 1].toInt() shl 8)
            energy += (sample * sample)
            index += 2
        }
        val rms = kotlin.math.sqrt(energy / (pcm16Bytes.size / 2)) / NORMALIZATION
        val loudness = LOUDNESS_MULTIPLIER * rms
        val clamped = loudness.coerceIn(0.0, 1.0)
        return (clamped * clamped).toFloat()
    }
}