package com.whispertype.android.history

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted representation of one history entry. Only Base64 strings cross the
 * persistence boundary; plaintext never does.
 */
data class EncryptedBlob(
    val ciphertextBase64: String,
    val ivBase64: String,
)

/**
 * AES/GCM/NoPadding encryption for history entries (Implementation Plan §11).
 * The key lives in the AndroidKeyStore under alias "whispertype_history".
 *
 * ADAPTATION NOTE: the plan referenced `com.whispertype.android.security.KeystoreManager`,
 * which does not exist yet in this source tree. Key management is therefore implemented
 * inline in the private [HistoryKeyStore] helper below, exposing the same API shape
 * (getOrCreateKey / keyExists / deleteKey). Swap it for KeystoreManager when that class lands.
 *
 * Plaintext is never logged or stored outside the encrypted blob.
 */
class EncryptedHistoryStore(context: Context) {

    private val keys: HistoryKeyStore = HistoryKeyStore()

    /** Encrypts [plaintext] with a fresh random 12-byte GCM IV. */
    fun encrypt(plaintext: String): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keys.getOrCreateKey(KEY_ALIAS),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedBlob(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
        )
    }

    /**
     * Decrypts [blob]. Throws [IllegalStateException] on any failure (bad key, tampered
     * or corrupted data); callers are responsible for catching and handling the error.
     */
    fun decrypt(blob: EncryptedBlob): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keys.getOrCreateKey(KEY_ALIAS),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(blob.ivBase64, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(blob.ciphertextBase64, Base64.NO_WRAP))
            return String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("History entry could not be decrypted.", e)
        }
    }

    private companion object {
        const val KEY_ALIAS: String = "whispertype_history"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS: Int = 128
        const val GCM_IV_LENGTH_BYTES: Int = 12
    }
}

/**
 * Minimal AndroidKeyStore-backed AES key helper mirroring the planned
 * com.whispertype.android.security.KeystoreManager API so the swap is trivial.
 */
private class HistoryKeyStore(
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE),
) {
    fun getOrCreateKey(alias: String): SecretKey {
        keyStore.load(null)
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun keyExists(alias: String): Boolean {
        keyStore.load(null)
        return keyStore.containsAlias(alias)
    }

    fun deleteKey(alias: String) {
        keyStore.load(null)
        keyStore.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
    }
}
