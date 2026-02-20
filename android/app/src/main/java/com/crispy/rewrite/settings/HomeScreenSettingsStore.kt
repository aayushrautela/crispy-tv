package com.crispy.rewrite.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class HomeScreenSettingsPreferences(
    val showRatingBadges: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val traktTopPicksEnabled: Boolean = false,
    val disabledCatalogKeys: Set<String> = emptySet(),
    val heroCatalogKeys: Set<String> = emptySet()
)

internal class HomeScreenSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): HomeScreenSettingsPreferences {
        val raw = prefs.getString(KEY_STATE, null) ?: return HomeScreenSettingsPreferences()
        return runCatching {
            fromJson(JSONObject(raw))
        }.getOrDefault(HomeScreenSettingsPreferences())
    }

    fun save(state: HomeScreenSettingsPreferences) {
        prefs.edit().putString(KEY_STATE, toJson(state).toString()).apply()
    }

    private fun toJson(state: HomeScreenSettingsPreferences): JSONObject {
        return JSONObject()
            .put("show_rating_badges", state.showRatingBadges)
            .put("continue_watching_enabled", state.continueWatchingEnabled)
            .put("trakt_top_picks_enabled", state.traktTopPicksEnabled)
            .put("disabled_catalog_keys", JSONArray(state.disabledCatalogKeys.sorted()))
            .put("hero_catalog_keys", JSONArray(state.heroCatalogKeys.sorted()))
    }

    private fun fromJson(json: JSONObject): HomeScreenSettingsPreferences {
        return HomeScreenSettingsPreferences(
            showRatingBadges = json.optBoolean("show_rating_badges", true),
            continueWatchingEnabled = json.optBoolean("continue_watching_enabled", true),
            traktTopPicksEnabled = json.optBoolean("trakt_top_picks_enabled", false),
            disabledCatalogKeys = readStringSet(json.optJSONArray("disabled_catalog_keys")),
            heroCatalogKeys = readStringSet(json.optJSONArray("hero_catalog_keys"))
        )
    }

    private fun readStringSet(array: JSONArray?): Set<String> {
        if (array == null) {
            return emptySet()
        }

        val output = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                output += value
            }
        }
        return output
    }

    private companion object {
        private const val PREFS_NAME = "home_screen_settings"
        private const val KEY_STATE = "state_json"
    }
}
