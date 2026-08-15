package com.crispy.tv.accounts

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The only place auth tokens live. Access + refresh tokens are persisted in an
 * EncryptedSharedPreferences (AES256-SIV keys / AES256-GCM values) whose master key is held by
 * the Android Keystore, and exposed reactively through [session] so the rest of the app reacts
 * to sign-in / sign-out instead of polling.
 *
 * Reads hit the in-memory [StateFlow] (no crypto per call); crypto runs only on persist/clear.
 * If the secure store cannot be opened (e.g. Keystore unavailable after an OS upgrade) we fail
 * closed: the error is surfaced rather than silently falling back to plaintext storage.
 */
class SecureTokenStore(private val context: Context) {
    private val store = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { throwable ->
        throw IllegalStateException("Failed to initialize secure token store", throwable)
    }

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
        store.edit().putString(KEY_SESSION, json).apply()
        _session.value = session
    }

    suspend fun clear() {
        runCatching { store.edit().clear().apply() }
        _session.value = null
    }

    private fun loadFromDisk(): Session? {
        return runCatching {
            val raw = store.getString(KEY_SESSION, null) ?: return null
            parse(raw)
        }.getOrNull()
    }

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
    }
}
