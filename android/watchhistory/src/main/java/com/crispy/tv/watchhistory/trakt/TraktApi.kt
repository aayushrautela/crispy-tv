package com.crispy.tv.watchhistory.trakt

import android.util.Log
import com.crispy.tv.watchhistory.WatchHistoryHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.crispy.tv.network.CrispyHttpResponse
import org.json.JSONArray
import org.json.JSONObject

internal class TraktApi(
    private val http: WatchHistoryHttp,
    private val traktClientId: String,
    private val readAccessToken: () -> String?,
    private val onTokenExpired: (suspend () -> String?)? = null,
    private val logTag: String = "TraktApi",
) {
    private val tokenRecoveryMutex = Mutex()

    private fun currentToken(): String? = readAccessToken()?.trim()?.ifBlank { null }

    /**
     * Returns a valid access token, or null if no token is available.
     * If [forceCloudPull] is true, delegates to [onTokenExpired] to pull a
     * fresh token from the cloud (the server handles refresh).
     */
    suspend fun ensureAccessToken(forceCloudPull: Boolean = false): String? {
        val token = currentToken() ?: return null
        if (!forceCloudPull) return token

        return tokenRecoveryMutex.withLock {
            // Re-check after acquiring the lock — another coroutine may have already recovered.
            val recheck = currentToken() ?: return@withLock null
            val callback = onTokenExpired ?: return@withLock recheck
            val fresh = callback() ?: return@withLock recheck
            fresh.trim().ifBlank { null } ?: recheck
        }
    }

    suspend fun searchGetArray(traktType: String, idType: String, id: String): JSONArray? {
        val token = ensureAccessToken() ?: return null
        val url = "https://api.trakt.tv/search/$traktType?id_type=$idType&id=$id"
        val response = http.getRaw(url = url, headers = headers(token)) ?: return null

        if (response.code == 401) {
            val recovered = ensureAccessToken(forceCloudPull = true)
            if (recovered != null) {
                val retry = http.getRaw(url = url, headers = headers(recovered)) ?: return null
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
            val recovered = ensureAccessToken(forceCloudPull = true)
            if (recovered != null) {
                val retry = http.getRaw(url = url, headers = headers(recovered)) ?: return null
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
            val recovered = ensureAccessToken(forceCloudPull = true)
            if (recovered != null) {
                val retry = http.getRaw(url = url, headers = headers(recovered)) ?: return null
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
            val recovered = ensureAccessToken(forceCloudPull = true)
            if (recovered != null) {
                val retry = http.postJsonRaw(url = url, headers = jsonHeaders(recovered), payload = payload) ?: return false
                return retry.code in 200..299
            }
        }

        return response.code in 200..299
    }

    suspend fun postRaw(path: String, payload: JSONObject): CrispyHttpResponse? {
        return postRawInternal(path = path, payload = payload, forceCloudPull = false)
    }

    suspend fun delete(path: String): Boolean {
        val token = ensureAccessToken() ?: return false
        val url = "https://api.trakt.tv$path"
        val response = http.deleteRaw(url = url, headers = headers(token)) ?: return false

        if (response.code == 401) {
            val recovered = ensureAccessToken(forceCloudPull = true)
            if (recovered != null) {
                val retry = http.deleteRaw(url = url, headers = headers(recovered)) ?: return false
                return retry.code in 200..299 || retry.code == 404
            }
        }

        return response.code in 200..299 || response.code == 404
    }

    private suspend fun postRawInternal(path: String, payload: JSONObject, forceCloudPull: Boolean): CrispyHttpResponse? {
        val token = ensureAccessToken(forceCloudPull = forceCloudPull) ?: return null
        val url = "https://api.trakt.tv$path"
        val response = http.postJsonRaw(url = url, headers = jsonHeaders(token), payload = payload) ?: return null

        if (response.code == 401 && !forceCloudPull) {
            return postRawInternal(path = path, payload = payload, forceCloudPull = true)
        }

        return response
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
}
