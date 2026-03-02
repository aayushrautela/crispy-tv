package com.crispy.tv.sync

import android.content.Context
import org.json.JSONObject

class ProfileDataShadowStore(
    context: Context,
) {
    data class Snapshot(
        val profileId: String,
        val settings: Map<String, String>,
        val catalogPrefs: Map<String, String>,
        val traktAuth: Map<String, String>,
        val simklAuth: Map<String, String>,
        val updatedAt: String?,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(profileId: String): Snapshot? {
        val key = keyForProfile(profileId)
        val raw = prefs.getString(key, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val settings = obj.optJSONObject("settings")?.toStringMap() ?: emptyMap()
        val catalogPrefs = obj.optJSONObject("catalog_prefs")?.toStringMap() ?: emptyMap()
        val traktAuth = obj.optJSONObject("trakt_auth")?.toStringMap() ?: emptyMap()
        val simklAuth = obj.optJSONObject("simkl_auth")?.toStringMap() ?: emptyMap()
        val updatedAt = obj.optString("updated_at").trim().ifBlank { null }

        return Snapshot(
            profileId = profileId,
            settings = settings,
            catalogPrefs = catalogPrefs,
            traktAuth = traktAuth,
            simklAuth = simklAuth,
            updatedAt = updatedAt
        )
    }

    fun write(snapshot: Snapshot) {
        val obj =
            JSONObject()
                .put("settings", snapshot.settings.toJsonObject())
                .put("catalog_prefs", snapshot.catalogPrefs.toJsonObject())
                .put("trakt_auth", snapshot.traktAuth.toJsonObject())
                .put("simkl_auth", snapshot.simklAuth.toJsonObject())
                .put("updated_at", snapshot.updatedAt ?: "")
        prefs.edit().putString(keyForProfile(snapshot.profileId), obj.toString()).apply()
    }

    fun clear(profileId: String) {
        prefs.edit().remove(keyForProfile(profileId)).apply()
    }

    private fun keyForProfile(profileId: String): String = "profile_data_shadow:$profileId"

    private fun JSONObject.toStringMap(): Map<String, String> {
        val iter = keys()
        val result = mutableMapOf<String, String>()
        while (iter.hasNext()) {
            val key = iter.next()
            val rawValue = opt(key)
            if (rawValue == null || rawValue == JSONObject.NULL) continue
            result[key] = rawValue.toString()
        }
        return result
    }

    private fun Map<String, String>.toJsonObject(): JSONObject {
        val obj = JSONObject()
        for (key in keys.sorted()) {
            obj.put(key, getValue(key))
        }
        return obj
    }

    private companion object {
        private const val PREFS_NAME = "profile_data_shadow"
    }
}
