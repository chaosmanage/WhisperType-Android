package com.whispertype.android.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the bounded 0.4.2 session recording used by the audio-recovery
 * failsafe.
 */
class SessionRecordingTest {

    @Test
    fun `empty recording yields no wav`() {
        assertNull(SessionRecording().toWav())
    }

    @Test
    fun `appended pcm round trips through a wav container`() {
        val recording = SessionRecording()
        recording.append(ByteArray(640) { 1 })
        recording.append(ByteArray(640) { 2 })
        val wav = assertNotNull(recording.toWav())
        assertEquals(44 + 1280, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        // RIFF size field = 36 + data size (LE u32).
        val sizeField = wav[4].toInt() and 0xFF or ((wav[5].toInt() and 0xFF) shl 8) or ((wav[6].toInt() and 0xFF) shl 16) or ((wav[7].toInt() and 0xFF) shl 24)
        assertEquals(36 + 1280, sizeField)
        // PCM payload preserved at offset 44.
        assertEquals(1.toByte(), wav[44])
        assertEquals(2.toByte(), wav[44 + 640])
    }

    @Test
    fun `empty appends are ignored`() {
        val recording = SessionRecording()
        recording.append(ByteArray(0))
        assertNull(recording.toWav())
    }

    @Test
    fun `overflow drops the whole recording so recovery never truncates`() {
        val recording = SessionRecording(maxBytes = 100)
        recording.append(ByteArray(60))
        assertFalse(recording.overflowed)
        recording.append(ByteArray(60)) // 120 > 100 -> overflow, all dropped
        assertTrue(recording.overflowed)
        assertNull(recording.toWav())
    }

    @Test
    fun `byte count tracks appended pcm`() {
        val recording = SessionRecording()
        assertEquals(0, recording.byteCount)
        recording.append(ByteArray(640))
        recording.append(ByteArray(320))
        assertEquals(960, recording.byteCount)
    }
}
