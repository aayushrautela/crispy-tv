package com.crispy.tv.watchhistory

import android.content.Context

internal const val WATCH_HISTORY_PREFS_NAME = "watch_history"
internal const val WATCH_HISTORY_LEGACY_PREFS_NAME = "watch_history_lab"

internal const val KEY_LOCAL_WATCHED_ITEMS = "@user:local:watched_items"

internal const val KEY_TRAKT_TOKEN = "trakt_access_token"
internal const val KEY_TRAKT_REFRESH_TOKEN = "trakt_refresh_token"
internal const val KEY_TRAKT_EXPIRES_AT = "trakt_expires_at"
internal const val KEY_TRAKT_HANDLE = "trakt_user_handle"
internal const val KEY_TRAKT_OAUTH_STATE = "trakt_oauth_state"
internal const val KEY_TRAKT_OAUTH_CODE_VERIFIER = "trakt_oauth_code_verifier"

internal const val KEY_SIMKL_TOKEN = "simkl_access_token"
internal const val KEY_SIMKL_HANDLE = "simkl_user_handle"
internal const val KEY_SIMKL_OAUTH_STATE = "simkl_oauth_state"
internal const val KEY_SIMKL_OAUTH_CODE_VERIFIER = "simkl_oauth_code_verifier"

internal const val STALE_PLAYBACK_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
internal const val CONTINUE_WATCHING_MIN_PROGRESS_PERCENT = 2.0
internal const val CONTINUE_WATCHING_COMPLETION_PERCENT = 85.0
internal const val CONTINUE_WATCHING_PLAYBACK_LIMIT = 30
internal const val CONTINUE_WATCHING_UPNEXT_SHOW_LIMIT = 30

internal const val TRAKT_AUTHORIZE_BASE = "https://trakt.tv/oauth/authorize"
internal const val TRAKT_TOKEN_URL = "https://api.trakt.tv/oauth/token"

internal const val SIMKL_AUTHORIZE_BASE = "https://simkl.com/oauth/authorize"
internal const val SIMKL_TOKEN_URL = "https://api.simkl.com/oauth/token"
internal const val SIMKL_API_BASE = "https://api.simkl.com"

internal const val SIMKL_APP_NAME = "crispytv"

internal fun migrateLegacyWatchHistoryPrefsIfNeeded(context: Context) {
    val appContext = context.applicationContext
    val legacy = appContext.getSharedPreferences(WATCH_HISTORY_LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    if (legacy.all.isEmpty()) return

    val current = appContext.getSharedPreferences(WATCH_HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    val keys =
        listOf(
            KEY_LOCAL_WATCHED_ITEMS,
            KEY_TRAKT_TOKEN,
            KEY_TRAKT_REFRESH_TOKEN,
            KEY_TRAKT_EXPIRES_AT,
            KEY_TRAKT_HANDLE,
            KEY_TRAKT_OAUTH_STATE,
            KEY_TRAKT_OAUTH_CODE_VERIFIER,
            KEY_SIMKL_TOKEN,
            KEY_SIMKL_HANDLE,
            KEY_SIMKL_OAUTH_STATE,
            KEY_SIMKL_OAUTH_CODE_VERIFIER,
        )

    var changed = false
    val editor = current.edit()
    val legacyAll = legacy.all
    for (key in keys) {
        if (current.contains(key)) continue
        val value = legacyAll[key] ?: continue
        when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            else -> continue
        }
        changed = true
    }

    if (changed) {
        editor.apply()
    }
}
