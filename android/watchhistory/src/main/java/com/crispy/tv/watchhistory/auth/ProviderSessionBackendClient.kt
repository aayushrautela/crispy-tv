package com.crispy.tv.watchhistory.auth

import com.crispy.tv.player.ProviderSessionBackend
import com.crispy.tv.player.ProviderSessionBackendResult
import com.crispy.tv.player.ProviderSessionDisconnectResult
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderSession
import com.crispy.tv.watchhistory.WatchHistoryHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal class ProviderSessionBackendClient(
    private val http: WatchHistoryHttp,
    supabaseUrl: String,
    private val publishableKey: String,
    private val accessTokenProvider: suspend () -> String?,
    private val profileIdProvider: suspend () -> String?,
) : ProviderSessionBackend {
    private val baseUrl = supabaseUrl.trim().trimEnd('/')

    override fun isConfigured(): Boolean {
        return baseUrl.isNotBlank() && publishableKey.isNotBlank()
    }

    override suspend fun exchangeProviderSession(
        provider: WatchProvider,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): ProviderSessionBackendResult {
        val payload =
            JSONObject()
                .put("profileId", profileIdOrError() ?: return missingProfileResult())
                .put("code", code.trim())
                .put("redirectUri", redirectUri.trim())
                .put("codeVerifier", codeVerifier.trim())
        return sendFunctionRequest(functionName = exchangeFunctionName(provider), payload = payload)
    }

    override suspend fun resolveProviderSession(provider: WatchProvider, forceRefresh: Boolean): ProviderSessionBackendResult {
        val profileId = profileIdOrError() ?: return missingProfileResult()
        if (forceRefresh) {
            return sendFunctionRequest(
                functionName = "provider-refresh",
                payload = JSONObject().put("profileId", profileId).put("provider", providerKey(provider)),
            )
        }

        if (!isConfigured()) {
            return ProviderSessionBackendResult(errorMessage = "Missing SUPABASE_URL or SUPABASE_PUBLISHABLE_KEY.")
        }

        val sessionToken = accessTokenProvider()?.trim().orEmpty()
        if (sessionToken.isBlank()) {
            return ProviderSessionBackendResult(errorMessage = "Not signed in.")
        }

        val url =
            "$baseUrl/rest/v1/provider_accounts".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "select",
                    "provider,access_token,access_token_expires_at,provider_username,provider_user_id,connected_at"
                )
                .addQueryParameter("profile_id", "eq.$profileId")
                .addQueryParameter("provider", "eq.${providerKey(provider)}")
                .addQueryParameter("limit", "1")
                .build()
                .toString()
        val response =
            http.getRaw(url = url, headers = authHeaders(sessionToken))
                ?: return ProviderSessionBackendResult(errorMessage = "Supabase provider session lookup is unavailable.")

        if (response.code !in 200..299) {
            val body = response.body.toJsonObjectOrNull()
            return ProviderSessionBackendResult(
                errorMessage = body.extractErrorMessage() ?: "Provider session lookup failed (HTTP ${response.code}).",
            )
        }

        val array = response.body.toJsonArrayOrNull() ?: JSONArray()
        if (array.length() == 0) {
            return ProviderSessionBackendResult(session = null)
        }

        return ProviderSessionBackendResult(session = array.optJSONObject(0)?.toSessionOrNull())
    }

    override suspend fun disconnectProviderSession(provider: WatchProvider): ProviderSessionDisconnectResult {
        val profileId = profileIdOrError() ?: return ProviderSessionDisconnectResult(success = false, errorMessage = "No active profile selected.")
        val result =
            sendFunctionRequest(
                functionName = "provider-disconnect",
                payload = JSONObject().put("profileId", profileId).put("provider", providerKey(provider)),
            )
        return ProviderSessionDisconnectResult(success = result.errorMessage == null, errorMessage = result.errorMessage)
    }

    private suspend fun sendFunctionRequest(functionName: String, payload: JSONObject): ProviderSessionBackendResult {
        if (!isConfigured()) {
            return ProviderSessionBackendResult(errorMessage = "Missing SUPABASE_URL or SUPABASE_PUBLISHABLE_KEY.")
        }

        val sessionToken = accessTokenProvider()?.trim().orEmpty()
        if (sessionToken.isBlank()) {
            return ProviderSessionBackendResult(errorMessage = "Not signed in.")
        }

        val response =
            http.postJsonRaw(
                url = "$baseUrl/functions/v1/$functionName",
                headers = authHeaders(sessionToken),
                payload = payload,
            ) ?: return ProviderSessionBackendResult(errorMessage = "Supabase Edge Functions are unavailable.")

        val body = response.body.toJsonObjectOrNull()
        if (response.code == 404) {
            return ProviderSessionBackendResult(session = null)
        }
        if (response.code !in 200..299) {
            return ProviderSessionBackendResult(
                errorMessage = body.extractErrorMessage() ?: "Provider auth request failed (HTTP ${response.code}).",
            )
        }

        if (body == null) {
            return ProviderSessionBackendResult(errorMessage = "Provider auth returned an invalid response.")
        }

        return ProviderSessionBackendResult(session = body.toSessionOrNull())
    }

    private suspend fun profileIdOrError(): String? {
        return profileIdProvider()?.trim()?.ifBlank { null }
    }

    private fun missingProfileResult(): ProviderSessionBackendResult {
        return ProviderSessionBackendResult(errorMessage = "No active profile selected.")
    }

    private fun exchangeFunctionName(provider: WatchProvider): String {
        return when (provider) {
            WatchProvider.TRAKT -> "trakt-oauth-exchange"
            WatchProvider.SIMKL -> "simkl-oauth-exchange"
            WatchProvider.LOCAL -> throw IllegalArgumentException("Local provider does not use provider auth functions.")
        }
    }

    private fun providerKey(provider: WatchProvider): String {
        return when (provider) {
            WatchProvider.TRAKT -> "trakt"
            WatchProvider.SIMKL -> "simkl"
            WatchProvider.LOCAL -> throw IllegalArgumentException("Local provider does not use provider auth functions.")
        }
    }

    private fun authHeaders(accessToken: String): Map<String, String> {
        return mapOf(
            "apikey" to publishableKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Bearer $accessToken",
        )
    }
}

private fun String.toJsonObjectOrNull(): JSONObject? {
    return runCatching { JSONObject(this.trim()) }.getOrNull()
}

private fun String.toJsonArrayOrNull(): JSONArray? {
    return runCatching { JSONArray(this.trim()) }.getOrNull()
}

private fun JSONObject?.extractErrorMessage(): String? {
    val body = this ?: return null
    val candidates = listOf("message", "error_description", "error")
    for (key in candidates) {
        val value = body.optString(key).trim()
        if (value.isNotBlank()) return value
    }
    return null
}

private fun JSONObject.toSessionOrNull(): WatchProviderSession? {
    val direct = optJSONObject("session") ?: this
    val accessToken = direct.optString("accessToken").trim().ifBlank {
        direct.optString("access_token").trim()
    }
    if (accessToken.isBlank()) return null

    val expiresAt =
        direct.optLong("expiresAtEpochMs", 0L).takeIf { it > 0L }
            ?: direct.optLong("expires_at", 0L).takeIf { it > 0L }
            ?: direct.optLong("expiresAt", 0L).takeIf { it > 0L }
            ?: direct.optString("access_token_expires_at").trim().ifBlank { null }?.let(::parseIsoToEpochMs)
    val userHandle =
        direct.optString("userHandle").trim().ifBlank {
            direct.optString("providerUsername").trim()
        }.ifBlank {
            direct.optString("provider_username").trim()
        }.ifBlank {
            direct.optString("providerUserId").trim()
        }.ifBlank {
            direct.optString("provider_user_id").trim()
        }.ifBlank { null }
    val connectedAt =
        direct.optString("connected_at").trim().ifBlank { null }?.let(::parseIsoToEpochMs)
            ?: System.currentTimeMillis()

    return WatchProviderSession(
        accessToken = accessToken,
        expiresAtEpochMs = expiresAt,
        userHandle = userHandle,
        connectedAtEpochMs = connectedAt,
    )
}

private fun parseIsoToEpochMs(raw: String): Long? {
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
