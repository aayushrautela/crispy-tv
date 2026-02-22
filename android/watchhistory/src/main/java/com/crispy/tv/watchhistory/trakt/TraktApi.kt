package com.crispy.tv.watchhistory.trakt

import android.content.SharedPreferences
import android.util.Log
import com.crispy.tv.watchhistory.KEY_TRAKT_EXPIRES_AT
import com.crispy.tv.watchhistory.KEY_TRAKT_REFRESH_TOKEN
import com.crispy.tv.watchhistory.KEY_TRAKT_TOKEN
import com.crispy.tv.watchhistory.TRAKT_TOKEN_URL
import com.crispy.tv.watchhistory.WatchHistoryHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class TraktApi(
    private val http: WatchHistoryHttp,
    private val prefs: SharedPreferences,
    private val traktClientId: String,
    private val traktClientSecret: String,
    private val traktRedirectUri: String,
    private val readSecret: (String) -> String,
    private val writeEncrypted: (String, String?) -> Unit,
    private val logTag: String = "TraktApi",
) {
    private val refreshMutex = Mutex()

    suspend fun ensureAccessToken(forceRefresh: Boolean = false): String? {
        val currentToken = readSecret(KEY_TRAKT_TOKEN).trim().ifBlank { null } ?: return null
        val refreshToken = readSecret(KEY_TRAKT_REFRESH_TOKEN).trim().ifBlank { null }
        val expiresAt = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
        val nowMs = System.currentTimeMillis()
        val shouldRefresh =
            forceRefresh ||
                (expiresAt != null && refreshToken != null && nowMs >= (expiresAt - 60_000L))

        if (!shouldRefresh) return currentToken

        return refreshMutex.withLock {
            val tokenAgain = readSecret(KEY_TRAKT_TOKEN).trim().ifBlank { null } ?: return@withLock null
            val refreshAgain = readSecret(KEY_TRAKT_REFRESH_TOKEN).trim().ifBlank { null } ?: return@withLock tokenAgain
            val expiresAgain = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
            val refreshNow = forceRefresh || (expiresAgain != null && nowMs >= (expiresAgain - 60_000L))
            if (!refreshNow) return@withLock tokenAgain

            val refreshed = refreshAccessToken(refreshAgain)
            refreshed ?: tokenAgain
        }
    }

    suspend fun searchGetArray(traktType: String, idType: String, id: String): JSONArray? {
        val token = ensureAccessToken() ?: return null
        val url = "https://api.trakt.tv/search/$traktType?id_type=$idType&id=$id"
        val response = http.getRaw(url = url, headers = headers(token)) ?: return null

        if (response.code == 401) {
            val refreshed = ensureAccessToken(forceRefresh = true)
            if (refreshed != null) {
                val retry = http.getRaw(url = url, headers = headers(refreshed)) ?: return null
                if (retry.code in 200..299) return JSONArray(retry.body)
                Log.w(logTag, "Trakt search retry failed: ${retry.code} $url")
                return null
            }
        }

        if (response.code !in 200..299) {
            Log.w(logTag, "Trakt search failed: ${response.code} $url")
            return null
        }
        return JSONArray(response.body)
    }

    suspend fun getArray(path: String): JSONArray? {
        val token = ensureAccessToken() ?: return null
        val url = "https://api.trakt.tv$path"
        val response = http.getRaw(url = url, headers = headers(token)) ?: return null

        if (response.code == 401) {
            val refreshed = ensureAccessToken(forceRefresh = true)
            if (refreshed != null) {
                val retry = http.getRaw(url = url, headers = headers(refreshed)) ?: return null
                if (retry.code in 200..299) return JSONArray(retry.body)
                Log.w(logTag, "Trakt GET retry failed: ${retry.code} $url")
                return null
            }
        }

        if (response.code !in 200..299) {
            Log.w(logTag, "Trakt GET failed: ${response.code} $url")
            return null
        }
        return JSONArray(response.body)
    }

    suspend fun getObject(path: String): JSONObject? {
        val token = ensureAccessToken() ?: return null
        val url = "https://api.trakt.tv$path"
        val response = http.getRaw(url = url, headers = headers(token)) ?: return null

        if (response.code == 401) {
            val refreshed = ensureAccessToken(forceRefresh = true)
            if (refreshed != null) {
                val retry = http.getRaw(url = url, headers = headers(refreshed)) ?: return null
                if (retry.code in 200..299) return JSONObject(retry.body)
                Log.w(logTag, "Trakt GET retry failed: ${retry.code} $url")
                return null
            }
        }

        if (response.code !in 200..299) {
            Log.w(logTag, "Trakt GET failed: ${response.code} $url")
            return null
        }
        return JSONObject(response.body)
    }

    suspend fun post(path: String, payload: JSONObject): Boolean {
        val token = ensureAccessToken() ?: return false
        val url = "https://api.trakt.tv$path"
        val response = http.postJsonRaw(url = url, headers = jsonHeaders(token), payload = payload) ?: return false

        if (response.code == 401) {
            val refreshed = ensureAccessToken(forceRefresh = true)
            if (refreshed != null) {
                val retry = http.postJsonRaw(url = url, headers = jsonHeaders(refreshed), payload = payload) ?: return false
                return retry.code in 200..299
            }
        }

        return response.code in 200..299
    }

    suspend fun delete(path: String): Boolean {
        val token = ensureAccessToken() ?: return false
        val url = "https://api.trakt.tv$path"
        val response = http.deleteRaw(url = url, headers = headers(token)) ?: return false

        if (response.code == 401) {
            val refreshed = ensureAccessToken(forceRefresh = true)
            if (refreshed != null) {
                val retry = http.deleteRaw(url = url, headers = headers(refreshed)) ?: return false
                return retry.code in 200..299 || retry.code == 404
            }
        }

        return response.code in 200..299 || response.code == 404
    }

    private fun headers(accessToken: String?): Map<String, String> {
        val base =
            linkedMapOf(
                "trakt-api-version" to "2",
                "trakt-api-key" to traktClientId,
                "Accept" to "application/json"
            )
        val token = accessToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            base["Authorization"] = "Bearer $token"
        }
        return base
    }

    private fun jsonHeaders(accessToken: String?): Map<String, String> {
        val base = headers(accessToken).toMutableMap()
        base["Content-Type"] = "application/json"
        return base
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? {
        val payload =
            JSONObject()
                .put("refresh_token", refreshToken)
                .put("client_id", traktClientId)
                .put("client_secret", traktClientSecret)
                .put("redirect_uri", traktRedirectUri)
                .put("grant_type", "refresh_token")

        val response =
            http.postJsonForObject(
                url = TRAKT_TOKEN_URL,
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                payload = payload
            ) ?: return null

        val accessToken = response.optString("access_token").trim()
        if (accessToken.isBlank()) {
            Log.w(logTag, "Trakt token refresh response missing access_token")
            return null
        }

        val newRefreshToken = response.optString("refresh_token").trim().ifBlank { null }
        val expiresInSeconds = response.optLong("expires_in", -1L)
        val expiresAtEpochMs =
            if (expiresInSeconds > 0L) System.currentTimeMillis() + (expiresInSeconds * 1000L) else null

        writeEncrypted(KEY_TRAKT_TOKEN, accessToken)
        writeEncrypted(KEY_TRAKT_REFRESH_TOKEN, newRefreshToken)
        prefs.edit().apply {
            if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
                putLong(KEY_TRAKT_EXPIRES_AT, expiresAtEpochMs)
            } else {
                remove(KEY_TRAKT_EXPIRES_AT)
            }
        }.apply()

        return accessToken
    }
}
