package com.crispy.tv.accounts

import com.crispy.tv.platform.KeyValueStore

/**
 * Remembers which profile the user last selected, per user id.
 *
 * Takes a [KeyValueStore] rather than a `Context`, so it is portable and takes no
 * Android type. The store instance is namespaced by its own backing file (see
 * `SharedPreferencesKeyValueStore`), which is what keeps this data separate from
 * the auth tokens in the same `supabase_sync_lab` namespace the Android
 * implementation used to share — those are now in their own store.
 *
 * The keys keep their `active_profile_id:` prefix because the value on disk is
 * user data, and changing a key is a silent data loss for anyone who upgrades.
 */
class ActiveProfileStore(private val store: KeyValueStore) {

    fun getActiveProfileId(userId: String?): String? {
        return store.getString(activeProfileIdKey(userId))
    }

    /**
     * A null [profileId] removes the entry, which is what the previous
     * `SharedPreferences.putString(key, null)` did — that overload deletes the key
     * rather than storing a null, so a sign-out did not leave `"null"` on disk.
     *
     * Written out rather than left implicit, because [KeyValueStore.putString] takes
     * a non-null `String` and a reader would reasonably assume a nullable argument
     * is stored as absent. It is: the key is removed.
     */
    fun setActiveProfileId(userId: String?, profileId: String?) {
        val key = activeProfileIdKey(userId)
        if (profileId == null) store.remove(key) else store.putString(key, profileId)
    }

    fun clear(userId: String?) {
        store.remove(activeProfileIdKey(userId))
    }

    private fun activeProfileIdKey(userId: String?): String {
        val normalized = userId?.trim().orEmpty().ifBlank { "guest" }
        return "active_profile_id:$normalized"
    }
}
