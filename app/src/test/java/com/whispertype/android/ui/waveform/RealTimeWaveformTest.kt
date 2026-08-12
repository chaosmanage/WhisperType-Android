package com.whispertype.android.ui.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeWaveformTest {

    @Test
    fun `phase animation stays stopped at effective silence`() {
        assertFalse(shouldAnimateWaveform(0f, isListening = true))
        assertFalse(shouldAnimateWaveform(WAVEFORM_SILENCE_THRESHOLD, isListening = true))
        assertEquals(0f, effectiveWaveformAmplitude(WAVEFORM_SILENCE_THRESHOLD), 0f)
    }

    @Test
    fun `phase animation starts above silence while listening`() {
        assertTrue(shouldAnimateWaveform(WAVEFORM_SILENCE_THRESHOLD + 0.001f, isListening = true))
    }

    @Test
    fun `phase animation stays stopped when not listening`() {
        assertFalse(shouldAnimateWaveform(1f, isListening = false))
    }

    @Test
    fun `invalid and out of range amplitudes are normalized`() {
        assertEquals(0f, normalizedWaveformAmplitude(Float.NaN), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(-1f), 0f)
        assertEquals(0f, normalizedWaveformAmplitude(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, normalizedWaveformAmplitude(2f), 0f)
    }
}
