package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.security.KeystoreSecretStore
import org.json.JSONObject

class AiInsightsSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AiInsightsSettings {
        val raw = prefs.getString(KEY_STATE_JSON, null)
        val json = runCatching { JSONObject(raw ?: "{}") }.getOrNull() ?: JSONObject()
        return AiInsightsSettings(
            mode = AiInsightsMode.fromRaw(json.optString("mode").takeIf { it.isNotBlank() }),
            modelType = AiInsightsModelType.fromRaw(json.optString("model_type").takeIf { it.isNotBlank() }),
            customModelName = json.optString("custom_model_name", "").trim(),
        )
    }

    fun saveSettings(settings: AiInsightsSettings) {
        val json =
            JSONObject()
                .put("mode", settings.mode.raw)
                .put("model_type", settings.modelType.raw)
                .put("custom_model_name", settings.customModelName)

        prefs.edit().putString(KEY_STATE_JSON, json.toString()).apply()
    }

    fun loadOpenRouterKey(): String {
        val stored = prefs.getString(KEY_OPENROUTER_KEY, null)?.trim().orEmpty()
        if (stored.isEmpty()) return ""

        if (!KeystoreSecretStore.isEncryptedPrefsValue(stored)) {
            // Best-effort upgrade.
            runCatching {
                prefs.edit().putString(KEY_OPENROUTER_KEY, KeystoreSecretStore.encryptForPrefs(stored)).apply()
            }
            return stored
        }

        return KeystoreSecretStore.decryptFromPrefs(stored) ?: ""
    }

    fun saveOpenRouterKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(KEY_OPENROUTER_KEY).apply()
            return
        }

        prefs.edit().putString(KEY_OPENROUTER_KEY, KeystoreSecretStore.encryptForPrefs(trimmed)).apply()
    }

    fun loadSnapshot(): AiInsightsSettingsSnapshot {
        return AiInsightsSettingsSnapshot(
            settings = loadSettings(),
            openRouterKey = loadOpenRouterKey(),
        )
    }

    fun saveSnapshot(snapshot: AiInsightsSettingsSnapshot) {
        saveSettings(snapshot.settings)
        saveOpenRouterKey(snapshot.openRouterKey)
    }

    companion object {
        private const val PREFS_NAME = "ai_insights_settings"
        private const val KEY_STATE_JSON = "state_json"
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
    }
}
