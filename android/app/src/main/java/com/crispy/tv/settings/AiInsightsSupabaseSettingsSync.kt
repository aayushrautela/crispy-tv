package com.crispy.tv.settings

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.supabase.SupabaseLabSessionStore
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class AiInsightsSupabaseSettingsSync(
    context: Context,
    private val httpClient: CrispyHttpClient,
    private val sessionStore: SupabaseLabSessionStore
) {
    private val appContext = context.applicationContext

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    suspend fun pull(): AiInsightsSettingsSnapshot? {
        val session = sessionStore.getValidSession(httpClient) ?: return null
        val profileId = fetchDefaultProfileId(session.accessToken) ?: return null

        val settings = fetchProfileSettings(session.accessToken, profileId)
        val ai = settings.optJSONObject(AI_SETTINGS_KEY) ?: JSONObject()
        return AiInsightsSettingsSnapshot(
            settings =
                AiInsightsSettings(
                    mode = AiInsightsMode.fromRaw(ai.optString("mode").takeIf { it.isNotBlank() }),
                    modelType = AiInsightsModelType.fromRaw(ai.optString("model_type").takeIf { it.isNotBlank() }),
                    customModelName = ai.optString("custom_model_name", "").trim(),
                ),
            openRouterKey = ai.optString("openrouter_key", "").trim(),
        )
    }

    suspend fun push(snapshot: AiInsightsSettingsSnapshot) {
        val session = sessionStore.getValidSession(httpClient)
            ?: throw IllegalStateException("Supabase session not found. Sign in from Labs > Supabase Sync.")
        val profileId = fetchDefaultProfileId(session.accessToken)
            ?: throw IllegalStateException("No Supabase profile found for this account.")

        val currentSettings = fetchProfileSettings(session.accessToken, profileId)
        val merged = JSONObject(currentSettings.toString())
        val ai = merged.optJSONObject(AI_SETTINGS_KEY) ?: JSONObject()

        ai.put("mode", snapshot.settings.mode.raw)
        ai.put("model_type", snapshot.settings.modelType.raw)
        ai.put("custom_model_name", snapshot.settings.customModelName)

        val key = snapshot.openRouterKey.trim()
        if (key.isEmpty()) {
            ai.remove("openrouter_key")
        } else {
            ai.put("openrouter_key", key)
        }

        merged.put(AI_SETTINGS_KEY, ai)

        upsertProfileSettings(session.accessToken, profileId, merged)
    }

    private suspend fun fetchDefaultProfileId(accessToken: String): String? {
        if (baseUrl.isEmpty()) return null

        val url =
            "$baseUrl/rest/v1/profiles" +
                "?select=id" +
                "&order=order_index.asc" +
                "&limit=1"
        val response =
            httpClient.get(
                url = url.toHttpUrl(),
                headers = baseHeaders(accessToken),
                callTimeoutMs = 10_000L
            )
        if (response.code !in 200..299) {
            return null
        }

        val array = runCatching { JSONArray(response.body.ifBlank { "[]" }) }.getOrNull() ?: JSONArray()
        val first = array.optJSONObject(0) ?: return null
        return first.optString("id").trim().ifEmpty { null }
    }

    private suspend fun fetchProfileSettings(accessToken: String, profileId: String): JSONObject {
        if (baseUrl.isEmpty()) return JSONObject()

        val url =
            "$baseUrl/rest/v1/profile_data" +
                "?select=settings" +
                "&profile_id=eq.${profileId}" +
                "&limit=1"
        val response =
            httpClient.get(
                url = url.toHttpUrl(),
                headers = baseHeaders(accessToken),
                callTimeoutMs = 10_000L
            )

        if (response.code !in 200..299) {
            return JSONObject()
        }

        val array = runCatching { JSONArray(response.body.ifBlank { "[]" }) }.getOrNull() ?: JSONArray()
        val first = array.optJSONObject(0) ?: return JSONObject()
        return first.optJSONObject("settings") ?: JSONObject()
    }

    private suspend fun upsertProfileSettings(accessToken: String, profileId: String, settings: JSONObject) {
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("SUPABASE_URL is not configured.")
        }

        val url = "$baseUrl/rest/v1/profile_data".toHttpUrl()
        val body = JSONObject().put("profile_id", profileId).put("settings", settings).toString()
        val headers =
            Headers.Builder()
                .addAll(baseHeaders(accessToken))
                .add("Prefer", "resolution=merge-duplicates,return=minimal")
                .build()

        val response = httpClient.postJson(url = url, jsonBody = body, headers = headers, callTimeoutMs = 10_000L)
        if (response.code !in 200..299) {
            throw IllegalStateException("Supabase settings sync failed (HTTP ${response.code}).")
        }
    }

    private fun baseHeaders(accessToken: String): Headers {
        return Headers.Builder()
            .add("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .add("Authorization", "Bearer $accessToken")
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    companion object {
        private const val AI_SETTINGS_KEY = "ai_insights"
    }
}
