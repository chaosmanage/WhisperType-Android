package com.whispertype.android.data.secrets

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core secret storage logic: Keystore key + AES-GCM cipher + private blob
 * storage. Never returns, stores, or logs plaintext other than on explicit
 * [provideKey] / [storeKey] calls. All failures degrade to a deterministic
 * false/null, and corrupt ciphertext is deleted on sight so the store recovers.
 * The suspend operations run Keystore/cipher/file work on [Dispatchers.IO] so
 * callers (including the UI) never block Main.
 */
class SecretStore(
    private val keystore: KeystoreKeyStore,
    private val cipher: AesGcmCipher,
    private val blobStore: BlobStore,
) {

    /** True when a stored blob is present. Does not decrypt. */
    fun hasKey(): Boolean = blobStore.read() != null

    /** Decrypts and returns the stored key, or null when absent/corrupt. */
    suspend fun provideKey(): String? =
        withContext(Dispatchers.IO) {
            val blob = blobStore.read() ?: return@withContext null
            val secretKey = try {
                keystore.getOrCreateKey()
            } catch (_: Throwable) {
                recoverCorrupt()
                return@withContext null
            }
            val plaintext = cipher.decrypt(secretKey, blob)
            if (plaintext == null) {
                recoverCorrupt()
                return@withContext null
            }
            String(plaintext, StandardCharsets.UTF_8)
        }

    /** Encrypts and persists [key]. Returns false for empty input or write failure. */
    suspend fun storeKey(key: String): Boolean {
        if (key.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            val plaintext = key.toByteArray(StandardCharsets.UTF_8)
            try {
                val secretKey = keystore.getOrCreateKey()
                val blob = cipher.encrypt(secretKey, plaintext)
                blobStore.write(blob)
            } catch (_: Throwable) {
                false
            }
        }
    }

    suspend fun deleteKey(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                keystore.deleteKey()
                blobStore.delete()
            } catch (_: Throwable) {
                false
            }
        }

    private fun recoverCorrupt() {
        blobStore.delete()
    }
}