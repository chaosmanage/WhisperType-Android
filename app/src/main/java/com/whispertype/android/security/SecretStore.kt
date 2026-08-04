package com.whispertype.android.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypts the Gemini API key at rest with an Android Keystore AES-GCM key and
 * stores the ciphertext in app-private no-backup storage. Plaintext keys are
 * never written to DataStore, SharedPreferences, logs, resources, or anywhere
 * else; key material only exists transiently inside the calling function.
 */
class SecretStore(context: Context) {

    private val keyStoreManager = KeystoreManager()
    private val secretFile = File(context.getNoBackupFilesDir(), SECRET_FILE_RELATIVE_PATH)

    suspend fun saveApiKey(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalized = key.trim()
            if (normalized.isEmpty()) {
                return@withContext Result.failure(Exception("API key is empty"))
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyStoreManager.getOrCreateKey(KEY_ALIAS))
            val ciphertext = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
            secretFile.parentFile?.mkdirs()
            secretFile.writeBytes(SecretPersistence.pack(cipher.iv, ciphertext))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getApiKey(): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (!secretFile.exists()) {
                return@withContext Result.success(null)
            }
            val data = secretFile.readBytes()
            val packed = SecretPersistence.unpack(data)
                ?: return@withContext Result.failure(Exception("Corrupted secret"))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyStoreManager.getOrCreateKey(KEY_ALIAS),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, packed.iv),
            )
            val plain = cipher.doFinal(packed.ciphertext)
            val key = String(plain, StandardCharsets.UTF_8)
            plain.fill(0)
            Result.success(key)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Result.failure(Exception("KEY_INVALIDATED"))
        } catch (e: UnrecoverableKeyException) {
            Result.failure(Exception("KEY_INVALIDATED"))
        } catch (e: Exception) {
            Result.failure(Exception("Secret unavailable"))
        }
    }

    suspend fun deleteApiKey(): Result<Unit> = withContext(Dispatchers.IO) {
        secretFile.delete()
        Result.success(Unit)
    }

    private companion object {
        const val KEY_ALIAS: String = "whispertype_api_key"
        const val SECRET_FILE_RELATIVE_PATH: String = "secrets/api_key.bin"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS: Int = 128
    }
}

/**
 * Length-prefixed binary envelope for an AES-GCM payload:
 * [4-byte big-endian IV length][IV][ciphertext].
 */
internal object SecretPersistence {
    private const val LENGTH_FIELD_BYTES: Int = 4

    data class PackedSecret(val iv: ByteArray, val ciphertext: ByteArray)

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(LENGTH_FIELD_BYTES + iv.size + ciphertext.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return buffer.array()
    }

    fun unpack(data: ByteArray): PackedSecret? {
        if (data.size < LENGTH_FIELD_BYTES) {
            return null
        }
        val ivLength = ByteBuffer.wrap(data).int
        if (ivLength <= 0 || ivLength > data.size - LENGTH_FIELD_BYTES) {
            return null
        }
        val iv = data.copyOfRange(LENGTH_FIELD_BYTES, LENGTH_FIELD_BYTES + ivLength)
        val ciphertext = data.copyOfRange(LENGTH_FIELD_BYTES + ivLength, data.size)
        return PackedSecret(iv, ciphertext)
    }
}
