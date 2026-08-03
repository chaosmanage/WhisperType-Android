package com.whispertype.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * KeyStore-backed AES-GCM key provider.
 *
 * Keys live inside the Android Keystore and never leave the secure hardware
 * (or the software-backed keystore when hardware is unavailable), so key
 * material is never exposed as a plain byte array in the app process.
 *
 * No user-authentication binding is configured: keys stay usable while the
 * device is locked, because dictation may legitimately start before the user
 * unlocks. Callers that need an unlocked-device guarantee must layer an
 * explicit user-facing gate on top of this manager.
 */
class KeystoreManager(
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore"),
) {
    init {
        keyStore.load(null)
    }

    fun getOrCreateKey(alias: String): SecretKey {
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as SecretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun keyExists(alias: String): Boolean = keyStore.containsAlias(alias)

    fun deleteKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }
}
