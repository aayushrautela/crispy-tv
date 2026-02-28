package com.crispy.tv.accounts

import android.content.Context

class ActiveProfileStore(appContext: Context) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveProfileId(userId: String?): String? {
        return prefs.getString(activeProfileIdKey(userId), null)
    }

    fun setActiveProfileId(userId: String?, profileId: String?) {
        prefs.edit()
            .putString(activeProfileIdKey(userId), profileId)
            .apply()
    }

    fun clear(userId: String?) {
        prefs.edit()
            .remove(activeProfileIdKey(userId))
            .apply()
    }

    private fun activeProfileIdKey(userId: String?): String {
        val normalized = userId?.trim().orEmpty().ifBlank { "guest" }
        return "active_profile_id:$normalized"
    }

    private companion object {
        private const val PREFS_NAME = "supabase_sync_lab"
    }
}
