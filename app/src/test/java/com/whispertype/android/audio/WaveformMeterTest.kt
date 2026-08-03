package com.whispertype.android.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformMeterTest {

    private fun pcm16Bytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    private fun chunk(bytes: ByteArray): AudioChunk =
        AudioChunk(sequenceNumber = 0L, pcm16Bytes = bytes, capturedAtMillis = 0L)

    @Test
    fun silentChunkProducesZeroAmplitude() = runTest {
        val meter = WaveformMeter(clock = { 0L })

        meter.push(chunk(ByteArray(640)))

        assertEquals(0f, meter.lastAmplitude(), 0f)
    }

    @Test
    fun fullScaleChunkApproachesOne() = runTest {
        val meter = WaveformMeter(clock = { 0L })

        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x7FFF.toShort() })))

        val amplitude = meter.lastAmplitude()
        assertTrue("amplitude was $amplitude", amplitude >= 0.95f)
        assertTrue("amplitude was $amplitude", amplitude <= 1f)
    }

    @Test
    fun throttleSuppressesUpdatesWithinWindowAndAppliesPendingPeakAfter() = runTest {
        var now = 0L
        val meter = WaveformMeter(clock = { now }, throttleMillis = 50)

        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x1000.toShort() })))
        assertEquals(0.128f, meter.lastAmplitude(), 1e-6f)

        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x2000.toShort() })))
        assertEquals(0.128f, meter.lastAmplitude(), 1e-6f)

        now = 25L
        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x4000.toShort() })))
        assertEquals(0.128f, meter.lastAmplitude(), 1e-6f)

        now = 51L
        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x2000.toShort() })))
        assertEquals(0.512f, meter.lastAmplitude(), 1e-6f)
    }

    @Test
    fun resetClearsAmplitudeAndPendingPeak() = runTest {
        var now = 0L
        val meter = WaveformMeter(clock = { now })

        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x4000.toShort() })))
        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x7FFF.toShort() })))
        assertTrue(meter.lastAmplitude() > 0f)

        meter.reset()
        assertEquals(0f, meter.lastAmplitude(), 0f)

        now = 100L
        meter.push(chunk(pcm16Bytes(ShortArray(320) { 0x1000.toShort() })))
        assertEquals(0.128f, meter.lastAmplitude(), 1e-6f)
    }
}
