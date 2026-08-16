package com.whispertype.android.data.secrets

import android.content.Context

/**
 * 0.7.0: [KeyProvider] for the Groq API key, stored exactly like the Gemini
 * key (Keystore-backed, app-private file, plaintext materialized only inside
 * [provideKey] and never logged).
 */
class GroqKeyProvider(context: Context) : KeyProvider {

    private val store: SecretStore = SecretStore(
        keystore = AndroidKeystoreKeyStore(KEY_ALIAS),
        cipher = JavaxAesGcmCipher(),
        blobStore = FileBlobStore(context, FILE_NAME),
    )

    override fun hasKey(): Boolean = store.hasKey()

    override suspend fun provideKey(): String? = store.provideKey()

    override suspend fun storeKey(key: String): Boolean = store.storeKey(key)

    override suspend fun deleteKey(): Boolean = store.deleteKey()

    private companion object {
        const val KEY_ALIAS = "whispertype_groq_key"
        const val FILE_NAME = "groq_api_key.bin"
    }
}