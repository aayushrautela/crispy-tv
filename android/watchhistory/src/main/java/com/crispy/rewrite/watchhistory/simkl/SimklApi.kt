package com.crispy.rewrite.watchhistory.simkl

import android.net.Uri
import android.util.Log
import com.crispy.rewrite.watchhistory.SIMKL_API_BASE
import com.crispy.rewrite.watchhistory.SIMKL_APP_NAME
import com.crispy.rewrite.watchhistory.SIMKL_AUTHORIZE_BASE
import com.crispy.rewrite.watchhistory.SIMKL_TOKEN_URL
import com.crispy.rewrite.watchhistory.WatchHistoryHttp
import org.json.JSONObject

internal class SimklApi(
    private val http: WatchHistoryHttp,
    private val simklClientId: String,
    private val simklClientSecret: String,
    private val simklRedirectUri: String,
    private val appName: String = SIMKL_APP_NAME,
    private val appVersion: String = "dev",
    private val logTag: String = "SimklApi"
) {
    fun userAgent(): String = "$appName/$appVersion"

    fun authorizeUrl(state: String): String {
        return Uri.parse(SIMKL_AUTHORIZE_BASE)
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", simklClientId)
            .appendQueryParameter("redirect_uri", simklRedirectUri)
            .appendQueryParameter("state", state)
            .appendQueryParameter("app-name", appName)
            .appendQueryParameter("app-version", appVersion)
            .build()
            .toString()
    }

    suspend fun exchangeToken(code: String): JSONObject? {
        val payload =
            JSONObject()
                .put("grant_type", "authorization_code")
                .put("code", code)
                .put("client_id", simklClientId)
                .put("client_secret", simklClientSecret)
                .put("redirect_uri", simklRedirectUri)

        return http.postJsonForObject(
            url = urlWithRequiredParams(SIMKL_TOKEN_URL),
            headers =
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to userAgent()
                ),
            payload = payload
        )
    }

    suspend fun fetchUserHandle(accessToken: String): String? {
        if (simklClientId.isBlank() || accessToken.isBlank()) {
            Log.w(logTag, "Skipping Simkl profile lookup: missing auth inputs")
            return null
        }

        val response =
            http.postJsonForObject(
                url = apiUrl("/users/settings"),
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to userAgent()
                    ),
                payload = JSONObject()
            ) ?: run {
                Log.w(logTag, "Simkl profile lookup returned no payload")
                return null
            }

        val user = response.optJSONObject("user")
        val name = user?.optString("name")?.trim().orEmpty()
        if (name.isNotBlank()) {
            return name
        }
        val account = response.optJSONObject("account")
        val accountId = account?.opt("id")?.toString()?.trim().orEmpty()
        if (accountId.isBlank()) {
            Log.w(logTag, "Simkl profile lookup succeeded but account identifier missing")
        }
        return accountId.ifBlank { null }
    }

    suspend fun getAny(path: String, accessToken: String): Any? {
        if (accessToken.isBlank() || simklClientId.isBlank()) return null
        return http.getJsonAny(
            url = apiUrl(path),
            headers =
                mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "simkl-api-key" to simklClientId,
                    "Accept" to "application/json",
                    "User-Agent" to userAgent()
                )
        )
    }

    suspend fun post(path: String, accessToken: String, payload: JSONObject): Boolean {
        if (accessToken.isBlank() || simklClientId.isBlank()) return false

        val code =
            http.postJson(
                url = apiUrl(path),
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to userAgent()
                    ),
                payload = payload
            ) ?: return false

        return (code in 200..299) || code == 409
    }

    fun apiUrl(path: String): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return urlWithRequiredParams("$SIMKL_API_BASE$normalizedPath")
    }

    fun urlWithRequiredParams(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("client_id").isNullOrBlank() && simklClientId.isNotBlank()) {
            builder.appendQueryParameter("client_id", simklClientId)
        }
        if (uri.getQueryParameter("app-name").isNullOrBlank()) {
            builder.appendQueryParameter("app-name", appName)
        }
        if (uri.getQueryParameter("app-version").isNullOrBlank()) {
            builder.appendQueryParameter("app-version", appVersion)
        }
        return builder.build().toString()
    }
}
