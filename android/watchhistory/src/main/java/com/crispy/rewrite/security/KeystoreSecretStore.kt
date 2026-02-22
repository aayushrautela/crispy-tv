package com.crispy.rewrite.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal keystore-backed secret store for small strings (tokens, etc.).
 *
 * Stored format (suitable for SharedPreferences):
 * `enc_v1:<base64(iv)>:<base64(ciphertext)>`
 */
object KeystoreSecretStore {
    private const val TAG = "KeystoreSecretStore"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "crispytv.secret_store.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX = "enc_v1:"

    fun isEncryptedPrefsValue(value: String): Boolean = value.startsWith(PREFIX)

    fun encryptForPrefs(plaintext: String): String {
        return runCatching {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.getEncoder().encodeToString(iv)
            val ctB64 = Base64.getEncoder().encodeToString(ciphertext)
            "$PREFIX$ivB64:$ctB64"
        }.onFailure { error ->
            Log.w(TAG, "encryptForPrefs failed", error)
        }.getOrElse {
            // Best-effort fallback: keep app functional even if keystore is unavailable.
            plaintext
        }
    }

    fun decryptFromPrefs(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            val payload = stored.removePrefix(PREFIX)
            val parts = payload.split(':', limit = 2)
            if (parts.size != 2) return@runCatching null

            val iv = runCatching { Base64.getDecoder().decode(parts[0]) }.getOrNull() ?: return@runCatching null
            val ciphertext =
                runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return@runCatching null

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val plaintextBytes = cipher.doFinal(ciphertext)
            plaintextBytes.toString(Charsets.UTF_8)
        }.onFailure { error ->
            Log.w(TAG, "decryptFromPrefs failed", error)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing =
            runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }
                .onFailure {
                    // If the entry exists but is not usable, delete it and re-create.
                    runCatching { keyStore.deleteEntry(KEY_ALIAS) }
                }
                .getOrNull()
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
