package com.whispertype.android.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.7.0: AudioPickleCodec wire-format round trips and header rejection. */
class AudioPickleCodecTest {

    private val codec = AudioPickleCodec()

    @Test
    fun `a matrix round-trips with its header`() {
        val matrix = arrayOf(floatArrayOf(0.1f, 0.2f), floatArrayOf(0.9f, 0.05f))
        val payload = codec.encode(matrix, 2)

        assertEquals(AudioPickleCodec.HEADER_SIZE + 2 * 2 * 4, payload.size)
        val decoded = assertNotNull(codec.decode(payload))
        assertEquals(2, decoded.frames.size)
        assertEquals(2, decoded.frameFeatureCount)
        assertTrue(matrix[0].contentEquals(decoded.frames[0]))
        assertTrue(matrix[1].contentEquals(decoded.frames[1]))
    }

    @Test
    fun `rows wider than the declared feature count are truncated`() {
        val matrix = arrayOf(floatArrayOf(0.5f, 0.25f, 0.125f))
        val payload = codec.encode(matrix, 1)

        val decoded = assertNotNull(codec.decode(payload))
        assertEquals(1, decoded.frames.size)
        assertEquals(1, decoded.frameFeatureCount)
        assertEquals(0.5f, decoded.frames[0][0])
    }

    @Test
    fun `a truncated frame count limits the encoded rows`() {
        val matrix = arrayOf(floatArrayOf(1f), floatArrayOf(2f))
        val payload = codec.encode(matrix, 1)

        val decoded = assertNotNull(codec.decode(payload))
        assertEquals(2, decoded.frames.size)
    }

    @Test
    fun `bad magic is rejected`() {
        val payload = codec.encode(arrayOf(floatArrayOf(1f)), 1)
        payload[0] = 0x58
        assertNull(codec.decode(payload))
    }

    @Test
    fun `unsupported version is rejected`() {
        val payload = codec.encode(arrayOf(floatArrayOf(1f)), 1)
        payload[4] = 9
        assertNull(codec.decode(payload))
    }

    @Test
    fun `a truncated payload is rejected`() {
        val payload = codec.encode(arrayOf(floatArrayOf(1f)), 1)
        assertNull(codec.decode(payload.copyOf(payload.size - 1)))
    }

    @Test
    fun `a payload shorter than the header is rejected`() {
        assertNull(codec.decode(ByteArray(3)))
    }

    @Test
    fun `mismatched length is rejected even with a valid header`() {
        val payload = codec.encode(arrayOf(floatArrayOf(1f)), 1)
        val padded = payload + ByteArray(4)
        assertNull(codec.decode(padded))
    }
}