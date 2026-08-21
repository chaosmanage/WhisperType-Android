package com.whispertype.android.data.secrets

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [FileBlobStore]'s crash-safe write path: bytes go to a temp
 * file in the same directory and are moved onto the target with an atomic
 * rename, so the target is never observed truncated and failed writes leave no
 * temp behind.
 */
class FileBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<FileBlobStore, File> {
        val target = File(tmp.root, "secret.bin")
        return FileBlobStore(target) to target
    }

    @Test
    fun `write then read round trips the bytes`() {
        val (store, _) = newStore()
        val data = byteArrayOf(1, 2, 3, 4, 5)

        assertTrue(store.write(data))
        assertArrayEquals(data, store.read())
    }

    @Test
    fun `overwrite replaces the previous bytes entirely`() {
        val (store, _) = newStore()

        assertTrue(store.write(ByteArray(512) { 7 }))
        assertTrue(store.write(byteArrayOf(9, 8)))

        assertArrayEquals(byteArrayOf(9, 8), store.read())
    }

    @Test
    fun `successful write leaves no temp files behind`() {
        val (store, target) = newStore()

        assertTrue(store.write(byteArrayOf(1)))

        assertTrue(tmp.root.listFiles()!!.none { it.name != target.name })
    }

    @Test
    fun `read on missing file returns null`() {
        assertNull(newStore().first.read())
    }

    @Test
    fun `delete removes the blob and is idempotent`() {
        val (store, target) = newStore()
        assertTrue(store.write(byteArrayOf(1)))
        assertTrue(target.exists())

        assertTrue(store.delete())
        assertFalse(target.exists())

        assertTrue(store.delete())
        assertNull(store.read())
    }
}
