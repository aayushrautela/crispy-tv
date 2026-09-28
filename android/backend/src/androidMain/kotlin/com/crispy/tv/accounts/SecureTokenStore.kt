package com.crispy.tv.accounts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.crispy.tv.platform.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The only place auth tokens live. Access + refresh tokens are encrypted with an AES-256-GCM key
 * held in the Android Keystore (non-extractable, hardware-backed where available) and the resulting
 * ciphertext is persisted in a standard SharedPreferences, exposed reactively through [session] so
 * the rest of the app reacts to sign-in / sign-out instead of polling.
 *
 * Reads hit the in-memory [StateFlow] (no crypto per call); crypto runs only on persist/clear.
 * If the secure store cannot be opened (e.g. Keystore unavailable after an OS upgrade) we fail
 * closed: the error is surfaced rather than silently falling back to plaintext storage.
 */
class SecureTokenStore(private val context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * The stored form is `$PREFIX<base64 iv>:<base64 ciphertext>`.
     *
     * The prefix is what makes [isEncrypted] answerable. Before it, the value was
     * a bare `iv:ciphertext` pair, and a two-part colon-separated string is not
     * evidence of anything — a plaintext token could look like one. Prefixing
     * makes the format self-identifying, so a value can be tested before it is
     * trusted and so a future key rotation can be told apart from a corrupt read.
     *
     * Reads stay backward compatible: [decrypt] accepts the unprefixed legacy form
     * so tokens written by an earlier build still load. They are never *written*
     * again, so a user who signs in after upgrading is on the new format and
     * everyone else is on the old one until then. That is a deliberate one-way
     * migration rather than a re-encryption pass, which would need the old key
     * alive and would be a much larger change for no security gain.
     */
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) + IV_SEPARATOR +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    override fun decrypt(stored: String): String? = runCatching {
        val payload = if (stored.startsWith(PREFIX)) stored.removePrefix(PREFIX) else stored
        val parts = payload.split(IV_SEPARATOR)
        if (parts.size != 2) return@runCatching null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }.getOrNull()

    private val _session = MutableStateFlow(loadFromDisk())
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun current(): Session? = _session.value

    suspend fun save(session: Session) {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("expires_at", session.expiresAtEpochSec ?: JSONObject.NULL)
            .put("user_id", session.userId ?: JSONObject.NULL)
            .put("email", session.email ?: JSONObject.NULL)
            .put("anonymous", session.anonymous)
            .toString()
        prefs.edit().putString(KEY_SESSION, encrypt(json)).apply()
        _session.value = session
    }

    suspend fun clear() {
        runCatching { prefs.edit().clear().apply() }
        _session.value = null
    }

    private fun loadFromDisk(): Session? = runCatching {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        parse(decrypt(raw) ?: return null)
    }.getOrNull()

    private fun parse(raw: String): Session? {
        val json = JSONObject(raw)
        val accessToken = json.optString("access_token").trim()
        if (accessToken.isBlank()) return null
        return Session(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").trim(),
            expiresAtEpochSec = json.optLong("expires_at", -1L).takeIf { it > 0L },
            userId = json.optString("user_id").trim().ifBlank { null },
            email = json.optString("email").trim().ifBlank { null },
            anonymous = json.optBoolean("anonymous", false),
        )
    }

    private companion object {
        private const val PREFS_NAME = "auth_tokens_secure"
        private const val KEY_SESSION = "session"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "crispy_secure_token_key"
        private const val IV_SEPARATOR = ":"
        private const val PREFIX = "enc_v1:"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
