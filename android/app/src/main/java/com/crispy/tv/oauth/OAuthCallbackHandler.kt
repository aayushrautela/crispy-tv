package com.crispy.tv.oauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.crispy.tv.player.WatchProvider

class OAuthCallbackHandler(
    private val appContext: Context,
) {
    private val pendingOAuthStore = PendingOAuthStore(appContext)

    fun handle(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != OAUTH_SCHEME) {
            return false
        }
        if (data.host != OAUTH_HOST) {
            return false
        }

        val path = data.path.orEmpty()
        val callback = data.toString()
        val provider =
            when {
                callback.startsWith(TRAKT_OAUTH_PREFIX) || path.startsWith(TRAKT_PATH_PREFIX) -> WatchProvider.TRAKT
                callback.startsWith(SIMKL_OAUTH_PREFIX) || path.startsWith(SIMKL_PATH_PREFIX) -> WatchProvider.SIMKL
                else -> null
            } ?: return false

        val callbackId = PendingOAuthStore.callbackIdForUri(callback)
        if (callbackId == pendingOAuthStore.lastCompletedCallbackId()) {
            runCatching { intent.data = null }
            return true
        }

        Log.d(TAG, "Received provider OAuth callback (${oauthUriSummaryForLog(data)})")

        pendingOAuthStore.savePending(provider = provider, callbackUri = callback)
        OAuthCompletionScheduler.enqueue(appContext)
        runCatching { intent.data = null }

        return true
    }

    private fun oauthUriSummaryForLog(uri: Uri): String {
        val statePresent = !uri.getQueryParameter("state").isNullOrBlank()
        val codePresent = !uri.getQueryParameter("code").isNullOrBlank()
        val errorPresent = !uri.getQueryParameter("error").isNullOrBlank()
        return "scheme=${uri.scheme.orEmpty()}, host=${uri.host.orEmpty()}, path=${uri.path.orEmpty()}, statePresent=$statePresent, codePresent=$codePresent, errorPresent=$errorPresent"
    }

    companion object {
        private const val TAG = "ProviderOAuth"
        private const val OAUTH_SCHEME = "crispy"
        private const val OAUTH_HOST = "auth"
        private const val TRAKT_PATH_PREFIX = "/trakt"
        private const val SIMKL_PATH_PREFIX = "/simkl"
        private const val TRAKT_OAUTH_PREFIX = "crispy://auth/trakt"
        private const val SIMKL_OAUTH_PREFIX = "crispy://auth/simkl"
    }
}
