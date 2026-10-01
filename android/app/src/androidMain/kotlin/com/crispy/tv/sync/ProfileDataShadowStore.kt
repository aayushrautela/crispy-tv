package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.library.optJsonObject
import com.crispy.tv.library.optStringOrEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ProfileDataShadowStore(
    context: Context,
) {
    data class Snapshot(
        val profileId: String,
        val settings: Map<String, String>,
        val catalogPrefs: Map<String, String>,
        val updatedAt: String?,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(profileId: String): Snapshot? {
        val key = keyForProfile(profileId)
        val raw = prefs.getString(key, null) ?: return null
        // `org.json` threw `JSONException` here and `Json` throws
        // `SerializationException`; `runCatching` catches either and nothing in
        // `:app` names that type, so the malformed-value answer is unchanged.
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null

        val settings = obj.optJsonObject("settings")?.toStringMap() ?: emptyMap()
        val catalogPrefs = obj.optJsonObject("catalog_prefs")?.toStringMap() ?: emptyMap()
        val updatedAt = obj.optStringOrEmpty("updated_at").trim().ifBlank { null }

        return Snapshot(
            profileId = profileId,
            settings = settings,
            catalogPrefs = catalogPrefs,
            updatedAt = updatedAt
        )
    }

    fun write(snapshot: Snapshot) {
        val obj =
            buildJsonObject {
                put("settings", snapshot.settings.toJsonObject())
                put("catalog_prefs", snapshot.catalogPrefs.toJsonObject())
                put("updated_at", snapshot.updatedAt ?: "")
            }
        prefs.edit().putString(keyForProfile(snapshot.profileId), obj.toString()).apply()
    }

    fun clear(profileId: String) {
        prefs.edit().remove(keyForProfile(profileId)).apply()
    }

    private fun keyForProfile(profileId: String): String = "profile_data_shadow:$profileId"

    private companion object {
        private const val PREFS_NAME = "profile_data_shadow"
    }
}
