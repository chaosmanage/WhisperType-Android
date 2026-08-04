package com.whispertype.android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AmplitudeMeter] (PRD FR-5): a silent buffer is 0.0, a loud buffer is
 * >0.0 and within [0,1] (normalized RMS), and the function is pure/stateless.
 */
class AmplitudeMeterTest {

    /** Little-endian PCM16 sample for the given signed 16-bit value. */
    private fun sample(value: Int): ByteArray {
        val v = value.toShort()
        return byteArrayOf((v.toInt() and 0xFF).toByte(), ((v.toInt() shr 8) and 0xFF).toByte())
    }

    @Test
    fun `silent all-zero buffer has amplitude zero`() {
        assertEquals(0f, AmplitudeMeter.normalizedRms(ByteArray(960)), 0f)
    }

    @Test
    fun `empty buffer has amplitude zero`() {
        assertEquals(0f, AmplitudeMeter.normalizedRms(ByteArray(0)), 0f)
    }

    @Test
    fun `loud constant full-scale positive buffer is within 0 and 1 and above zero`() {
        val bytes = ByteArray(960)
        val maxSample = sample(32767)
        for (i in bytes.indices step 2) {
            bytes[i] = maxSample[0]
            bytes[i + 1] = maxSample[1]
        }
        val amplitude = AmplitudeMeter.normalizedRms(bytes)
        assertTrue("amplitude should be > 0, was $amplitude", amplitude > 0f)
        assertTrue("amplitude should be <= 1, was $amplitude", amplitude <= 1f)
        // Full-scale constant tone -> normalized RMS ~= 1.0.
        assertEquals(1f, amplitude, 0.001f)
    }

    @Test
    fun `loud alternating max-positive and max-negative buffer is strong and bounded`() {
        val bytes = ByteArray(1920)
        val pos = sample(32767)
        val neg = sample(-32768)
        var k = 0
        while (k + 1 < bytes.size) {
            val s = if ((k / 2) % 2 == 0) pos else neg
            bytes[k] = s[0]
            bytes[k + 1] = s[1]
            k += 2
        }
        val amplitude = AmplitudeMeter.normalizedRms(bytes)
        assertTrue("amplitude should be > 0.9, was $amplitude", amplitude > 0.9f)
        assertTrue("amplitude should be <= 1, was $amplitude", amplitude <= 1f)
    }

    @Test
    fun `result is bounded to the closed 0 1 interval for extreme inputs`() {
        val max = sample(-32768)
        val bytes = ByteArray(2) { max[it] }
        val amplitude = AmplitudeMeter.normalizedRms(bytes)
        assertTrue(amplitude in 0f..1f)
    }

    @Test
    fun `odd trailing byte is ignored without error`() {
        val bytes = ByteArray(3) { 0 }
        assertEquals(0f, AmplitudeMeter.normalizedRms(bytes), 0f)
    }

    @Test
    fun `meter is a pure function returning identical results for the same input`() {
        val loud = ByteArray(960) { if (it % 2 == 0) 0xFF.toByte() else 0x7F }
        assertEquals(AmplitudeMeter.normalizedRms(loud), AmplitudeMeter.normalizedRms(loud.copyOf()), 0f)
    }
}
