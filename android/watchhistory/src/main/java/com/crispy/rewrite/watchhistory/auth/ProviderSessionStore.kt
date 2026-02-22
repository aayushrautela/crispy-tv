package com.crispy.rewrite.watchhistory.auth

import android.content.Context
import android.content.SharedPreferences
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchProviderAuthState
import com.crispy.rewrite.player.WatchProviderSession
import com.crispy.rewrite.security.KeystoreSecretStore
import com.crispy.rewrite.watchhistory.KEY_SIMKL_HANDLE
import com.crispy.rewrite.watchhistory.KEY_SIMKL_OAUTH_STATE
import com.crispy.rewrite.watchhistory.KEY_SIMKL_TOKEN
import com.crispy.rewrite.watchhistory.KEY_TRAKT_EXPIRES_AT
import com.crispy.rewrite.watchhistory.KEY_TRAKT_HANDLE
import com.crispy.rewrite.watchhistory.KEY_TRAKT_OAUTH_CODE_VERIFIER
import com.crispy.rewrite.watchhistory.KEY_TRAKT_OAUTH_STATE
import com.crispy.rewrite.watchhistory.KEY_TRAKT_REFRESH_TOKEN
import com.crispy.rewrite.watchhistory.KEY_TRAKT_TOKEN
import com.crispy.rewrite.watchhistory.WATCH_HISTORY_PREFS_NAME
import com.crispy.rewrite.watchhistory.migrateLegacyWatchHistoryPrefsIfNeeded

internal class ProviderSessionStore(
    context: Context,
    private val clearProviderCaches: (WatchProvider) -> Unit,
) {
    private val appContext = context.applicationContext

    val prefs: SharedPreferences =
        run {
            migrateLegacyWatchHistoryPrefsIfNeeded(appContext)
            appContext.getSharedPreferences(WATCH_HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
        }

    fun connectProvider(
        provider: WatchProvider,
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMs: Long?,
        userHandle: String?,
    ) {
        val normalizedAccess = accessToken.trim()
        if (normalizedAccess.isBlank()) {
            disconnectProvider(provider)
            return
        }

        when (provider) {
            WatchProvider.TRAKT -> clearProviderCaches(WatchProvider.SIMKL)
            WatchProvider.SIMKL -> clearProviderCaches(WatchProvider.TRAKT)
            WatchProvider.LOCAL -> Unit
        }

        prefs.edit().apply {
            when (provider) {
                WatchProvider.TRAKT -> {
                    remove(KEY_SIMKL_TOKEN)
                    remove(KEY_SIMKL_HANDLE)
                    remove(KEY_SIMKL_OAUTH_STATE)

                    putString(KEY_TRAKT_TOKEN, KeystoreSecretStore.encryptForPrefs(normalizedAccess))

                    val normalizedRefresh = refreshToken?.trim()?.ifBlank { null }
                    if (normalizedRefresh == null) {
                        remove(KEY_TRAKT_REFRESH_TOKEN)
                    } else {
                        putString(KEY_TRAKT_REFRESH_TOKEN, KeystoreSecretStore.encryptForPrefs(normalizedRefresh))
                    }

                    if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
                        putLong(KEY_TRAKT_EXPIRES_AT, expiresAtEpochMs)
                    } else {
                        remove(KEY_TRAKT_EXPIRES_AT)
                    }

                    putString(KEY_TRAKT_HANDLE, userHandle?.trim()?.ifBlank { null })
                }

                WatchProvider.SIMKL -> {
                    remove(KEY_TRAKT_TOKEN)
                    remove(KEY_TRAKT_REFRESH_TOKEN)
                    remove(KEY_TRAKT_EXPIRES_AT)
                    remove(KEY_TRAKT_HANDLE)
                    remove(KEY_TRAKT_OAUTH_STATE)
                    remove(KEY_TRAKT_OAUTH_CODE_VERIFIER)

                    putString(KEY_SIMKL_TOKEN, KeystoreSecretStore.encryptForPrefs(normalizedAccess))
                    putString(KEY_SIMKL_HANDLE, userHandle?.trim()?.ifBlank { null })
                }

                WatchProvider.LOCAL -> Unit
            }
        }.apply()
    }

    fun disconnectProvider(provider: WatchProvider) {
        prefs.edit().apply {
            when (provider) {
                WatchProvider.TRAKT -> {
                    remove(KEY_TRAKT_TOKEN)
                    remove(KEY_TRAKT_REFRESH_TOKEN)
                    remove(KEY_TRAKT_EXPIRES_AT)
                    remove(KEY_TRAKT_HANDLE)
                    remove(KEY_TRAKT_OAUTH_STATE)
                    remove(KEY_TRAKT_OAUTH_CODE_VERIFIER)
                }

                WatchProvider.SIMKL -> {
                    remove(KEY_SIMKL_TOKEN)
                    remove(KEY_SIMKL_HANDLE)
                    remove(KEY_SIMKL_OAUTH_STATE)
                }

                WatchProvider.LOCAL -> Unit
            }
        }.apply()

        clearProviderCaches(provider)
    }

    fun authState(): WatchProviderAuthState {
        val traktSession = traktSessionOrNull()
        val simklSession = simklSessionOrNull()
        return WatchProviderAuthState(
            traktAuthenticated = traktSession != null,
            simklAuthenticated = simklSession != null,
            traktSession = traktSession,
            simklSession = simklSession,
        )
    }

    fun clearPendingTraktOAuth() {
        prefs.edit().apply {
            remove(KEY_TRAKT_OAUTH_STATE)
            remove(KEY_TRAKT_OAUTH_CODE_VERIFIER)
        }.apply()
    }

    fun clearPendingSimklOAuth() {
        prefs.edit().apply {
            remove(KEY_SIMKL_OAUTH_STATE)
        }.apply()
    }

    fun readSecret(key: String): String {
        val stored = prefs.getString(key, null)?.trim().orEmpty()
        if (stored.isBlank()) return ""

        if (KeystoreSecretStore.isEncryptedPrefsValue(stored)) {
            val decrypted = KeystoreSecretStore.decryptFromPrefs(stored)
            if (decrypted == null) {
                runCatching { prefs.edit().remove(key).apply() }
                return ""
            }
            return decrypted
        }

        runCatching {
            prefs.edit().putString(key, KeystoreSecretStore.encryptForPrefs(stored)).apply()
        }
        return stored
    }

    fun writeEncryptedSecret(key: String, plaintext: String?) {
        val normalized = plaintext?.trim()?.ifBlank { null }
        prefs.edit().apply {
            if (normalized == null) {
                remove(key)
            } else {
                putString(key, KeystoreSecretStore.encryptForPrefs(normalized))
            }
        }.apply()
    }

    fun traktAccessToken(): String {
        return readSecret(KEY_TRAKT_TOKEN).trim()
    }

    fun simklAccessToken(): String {
        return readSecret(KEY_SIMKL_TOKEN).trim()
    }

    private fun traktSessionOrNull(): WatchProviderSession? {
        val token = traktAccessToken()
        if (token.isBlank()) return null

        val refresh = prefs.getString(KEY_TRAKT_REFRESH_TOKEN, null)?.trim()?.ifBlank { null }
        val expiresAt = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
        val handle = prefs.getString(KEY_TRAKT_HANDLE, null)?.trim()?.ifBlank { null }
        return WatchProviderSession(
            accessToken = token,
            refreshToken = refresh,
            expiresAtEpochMs = expiresAt,
            userHandle = handle,
        )
    }

    private fun simklSessionOrNull(): WatchProviderSession? {
        val token = simklAccessToken()
        if (token.isBlank()) return null

        val handle = prefs.getString(KEY_SIMKL_HANDLE, null)?.trim()?.ifBlank { null }
        return WatchProviderSession(accessToken = token, userHandle = handle)
    }
}
