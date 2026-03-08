package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.security.KeystoreSecretStore

class OmdbSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadOmdbKey(): String {
        val stored = prefs.getString(KEY_OMDB_KEY, null)?.trim().orEmpty()
        if (stored.isEmpty()) return ""

        if (!KeystoreSecretStore.isEncryptedPrefsValue(stored)) {
            runCatching {
                prefs.edit().putString(KEY_OMDB_KEY, KeystoreSecretStore.encryptForPrefs(stored)).apply()
            }
            return stored
        }

        return KeystoreSecretStore.decryptFromPrefs(stored) ?: ""
    }

    fun saveOmdbKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_OMDB_KEY).apply()
            return
        }

        prefs.edit().putString(KEY_OMDB_KEY, KeystoreSecretStore.encryptForPrefs(trimmed)).apply()
    }

    private companion object {
        private const val PREFS_NAME = "metadata_settings"
        private const val KEY_OMDB_KEY = "omdb_key"
    }
}
