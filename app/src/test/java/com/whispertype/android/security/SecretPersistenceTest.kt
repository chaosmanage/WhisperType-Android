package com.whispertype.android.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretPersistenceTest {

    @Test
    fun packUnpackRoundTripIsByteExact() {
        val iv = ByteArray(12) { (it + 1).toByte() }
        val ciphertext = ByteArray(32) { (it * 3 + 7).toByte() }

        val packed = SecretPersistence.pack(iv, ciphertext)
        assertEquals(4 + 12 + 32, packed.size)

        val unpacked = requireNotNull(SecretPersistence.unpack(packed))
        assertArrayEquals(iv, unpacked.iv)
        assertArrayEquals(ciphertext, unpacked.ciphertext)
    }

    @Test
    fun unpackRejectsDataShorterThanLengthField() {
        assertNull(SecretPersistence.unpack(ByteArray(0)))
        assertNull(SecretPersistence.unpack(ByteArray(3)))
        assertNull(SecretPersistence.unpack(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun unpackRejectsLengthFieldBeyondRemainingBytes() {
        val data = ByteArray(8)
        data[0] = 0x7F.toByte()
        data[1] = 0xFF.toByte()
        assertNull(SecretPersistence.unpack(data))
    }

    @Test
    fun unpackRejectsZeroLengthField() {
        val data = byteArrayOf(0, 0, 0, 0, 1, 2, 3)
        assertNull(SecretPersistence.unpack(data))
    }
}
