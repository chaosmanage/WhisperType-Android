package com.whispertype.android.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.7.0: NetworkVadScorer energy behavior on synthetic PCM16 frames. */
class NetworkVadScorerTest {

    private fun silence(bytes: Int = 640): ByteArray = ByteArray(bytes)

    /** PCM16 little-endian frame whose samples all equal [amplitude]. */
    private fun frameOfAmplitude(amplitude: Int, bytes: Int = 640): ByteArray {
        val out = ByteArray(bytes)
        var i = 0
        while (i + 1 < out.size) {
            out[i] = (amplitude and 0xFF).toByte()
            out[i + 1] = ((amplitude shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    @Test
    fun `pure silence scores zero`() {
        assertEquals(0f, NetworkVadScorer.score(silence()))
    }

    @Test
    fun `an empty frame scores zero`() {
        assertEquals(0f, NetworkVadScorer.score(ByteArray(0)))
    }

    @Test
    fun `full-scale speech scores one`() {
        val out = ByteArray(640)
        var i = 0
        while (i < out.size) {
            out[i] = 0xFF.toByte() // 0xFFFF little-endian = -1 sample
            out[i + 1] = 0x7F.toByte()
            i += 2
        }
        assertEquals(1f, NetworkVadScorer.score(out))
    }

    @Test
    fun `mid-level speech scores between silence and full scale`() {
        val score = NetworkVadScorer.score(frameOfAmplitude(2540)) // rms ~0.077 -> loudness ~0.6
        assertTrue(score > 0f && score < 1f, "expected a mid-range score, was $score")
    }

    @Test
    fun `an odd-length frame still scores`() {
        val score = NetworkVadScorer.score(frameOfAmplitude(2540, 641))
        assertTrue(score > 0f)
    }
}