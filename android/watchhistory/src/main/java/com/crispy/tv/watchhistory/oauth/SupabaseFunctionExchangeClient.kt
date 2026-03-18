package com.crispy.tv.watchhistory.oauth

import com.crispy.tv.watchhistory.WatchHistoryHttp
import org.json.JSONObject

internal data class SupabaseFunctionExchangeResult(
    val payload: JSONObject? = null,
    val errorMessage: String? = null,
)

internal class SupabaseFunctionExchangeClient(
    private val http: WatchHistoryHttp,
    supabaseUrl: String,
    supabasePublishableKey: String,
) {
    private val functionsBaseUrl = supabaseUrl.trim().trimEnd('/')
    private val publishableKey = supabasePublishableKey.trim()

    fun isConfigured(): Boolean {
        return functionsBaseUrl.isNotBlank() && publishableKey.isNotBlank()
    }

    suspend fun post(functionName: String, payload: JSONObject): SupabaseFunctionExchangeResult {
        if (functionsBaseUrl.isBlank()) {
            return SupabaseFunctionExchangeResult(errorMessage = "Missing SUPABASE_URL.")
        }
        if (publishableKey.isBlank()) {
            return SupabaseFunctionExchangeResult(errorMessage = "Missing SUPABASE_PUBLISHABLE_KEY.")
        }

        val url = "$functionsBaseUrl/functions/v1/${functionName.trim()}"
        val response =
            http.postJsonRaw(
                url = url,
                headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "apikey" to publishableKey,
                    ),
                payload = payload,
            ) ?: return SupabaseFunctionExchangeResult(errorMessage = "OAuth exchange is unavailable right now.")

        val body = response.body.trim()
        val json = body.toJsonObjectOrNull()
        if (response.code !in 200..299) {
            return SupabaseFunctionExchangeResult(
                errorMessage = json.extractErrorMessage() ?: "OAuth exchange failed (HTTP ${response.code}).",
            )
        }
        if (json == null) {
            return SupabaseFunctionExchangeResult(errorMessage = "OAuth exchange returned an invalid response.")
        }
        return SupabaseFunctionExchangeResult(payload = json)
    }
}

private fun String.toJsonObjectOrNull(): JSONObject? {
    return runCatching { JSONObject(this) }.getOrNull()
}

private fun JSONObject?.extractErrorMessage(): String? {
    val body = this ?: return null
    val errorDescription = body.optString("error_description").trim()
    if (errorDescription.isNotBlank()) return errorDescription
    val error = body.optString("error").trim()
    if (error.isNotBlank()) return error
    return null
}
