package com.crispy.tv.sync

import com.crispy.tv.library.optJsonObject
import com.crispy.tv.library.optStringOrEmpty
import com.crispy.tv.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ProfileDataShadowStore(
    private val store: KeyValueStore,
) {
    data class Snapshot(
        val profileId: String,
        val settings: Map<String, String>,
        val catalogPrefs: Map<String, String>,
        val updatedAt: String?,
    )

    fun read(profileId: String): Snapshot? {
        val key = keyForProfile(profileId)
        val raw = store.getString(key) ?: return null
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
        store.putString(keyForProfile(snapshot.profileId), obj.toString())
    }

    fun clear(profileId: String) {
        store.remove(keyForProfile(profileId))
    }

    /**
     * One key per profile under a shared prefix.
     *
     * This is *not* a composite key. There is one field, it is appended whole, and the key
     * is never split back apart -- every read knows its own `profileId` and asks for
     * exactly one key -- so `prefix + profileId` is injective over distinct ids and a
     * profile id containing the separator cannot reach another profile's data. The usual
     * warning about concatenating a composite key does not apply, and asserting a collision
     * here would assert a property the format does not have.
     *
     * The consequence worth stating is the one that *is* real: because the format is
     * deliberately unparseable, a profile id containing `:` can never be recovered from a
     * stored key. That is only a problem for a future feature that wants to enumerate
     * profiles and recover their ids from the key set, and it is cheaper to find out then
     * than to migrate every stored profile now. `ProfileDataShadowStoreKeyTest` pins the
     * injectivity that the current design depends on, so a future key format cannot
     * quietly break it.
     */
    private fun keyForProfile(profileId: String): String = "$PROFILE_KEY_PREFIX$profileId"

    internal companion object {
        /**
         * Shared with the wiring that opens the store, which needs the same literal to
         * name the file. A `private const val` beside a factory is a string two systems
         * have to agree on, and the two are on opposite sides of the port now.
         */
        const val PROFILE_KEY_PREFIX = "profile_data_shadow:"
    }
}
