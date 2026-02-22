package com.crispy.tv.watchhistory.oauth

import android.content.SharedPreferences
import com.crispy.tv.watchhistory.KEY_SIMKL_OAUTH_STATE
import com.crispy.tv.watchhistory.KEY_TRAKT_OAUTH_CODE_VERIFIER
import com.crispy.tv.watchhistory.KEY_TRAKT_OAUTH_STATE

internal data class TraktOAuthState(
    val state: String,
    val codeVerifier: String,
)

internal class OAuthStateStore(
    private val prefs: SharedPreferences,
) {
    fun saveTrakt(state: String, codeVerifier: String) {
        prefs.edit().apply {
            putString(KEY_TRAKT_OAUTH_STATE, state)
            putString(KEY_TRAKT_OAUTH_CODE_VERIFIER, codeVerifier)
        }.apply()
    }

    fun loadTrakt(): TraktOAuthState {
        val state = prefs.getString(KEY_TRAKT_OAUTH_STATE, null)?.trim().orEmpty()
        val codeVerifier = prefs.getString(KEY_TRAKT_OAUTH_CODE_VERIFIER, null)?.trim().orEmpty()
        return TraktOAuthState(state = state, codeVerifier = codeVerifier)
    }

    fun clearTrakt() {
        prefs.edit().apply {
            remove(KEY_TRAKT_OAUTH_STATE)
            remove(KEY_TRAKT_OAUTH_CODE_VERIFIER)
        }.apply()
    }

    fun saveSimkl(state: String) {
        prefs.edit().putString(KEY_SIMKL_OAUTH_STATE, state).apply()
    }

    fun loadSimklState(): String {
        return prefs.getString(KEY_SIMKL_OAUTH_STATE, null)?.trim().orEmpty()
    }

    fun clearSimkl() {
        prefs.edit().remove(KEY_SIMKL_OAUTH_STATE).apply()
    }
}
