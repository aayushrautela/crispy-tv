package com.crispy.tv.ai

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AiInsightsRepository(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val httpClient: CrispyHttpClient,
    private val cacheStore: AiInsightsCacheStore,
    private val supabaseUrl: String,
    private val supabasePublishableKey: String,
) {
    fun loadCached(
        tmdbId: Int,
        mediaType: MetadataLabMediaType,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? = cacheStore.load(tmdbId, mediaType, locale)

    suspend fun generate(
        tmdbId: Int,
        mediaType: MetadataLabMediaType,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult {
        val session = supabase.ensureValidSession()
            ?: throw IllegalStateException("Sign in to use AI insights.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            throw IllegalStateException("Select a profile to use AI insights.")
        }

        val baseUrl = supabaseUrl.trim().trimEnd('/')
        val publishableKey = supabasePublishableKey.trim()
        if (baseUrl.isBlank() || publishableKey.isBlank()) {
            throw IllegalStateException("Supabase is not configured.")
        }

        val requestBody =
            JSONObject()
                .put("tmdbId", tmdbId)
                .put("mediaType", mediaType.toApiValue())
                .put("profileId", profileId)
                .put("locale", locale.toLanguageTag())
                .toString()

        val headers =
            Headers.Builder()
                .add("apikey", publishableKey)
                .add("Authorization", "Bearer ${session.accessToken.trim()}")
                .add("Content-Type", "application/json")
                .add("Accept", "application/json")
                .build()

        val response =
            runCatching {
                httpClient.postJson(
                    url = "$baseUrl/functions/v1/ai-insights".toHttpUrl(),
                    jsonBody = requestBody,
                    headers = headers,
                    callTimeoutMs = CALL_TIMEOUT_MS,
                )
            }.getOrElse {
                throw IllegalStateException("AI insights are unreachable right now. Please try again.")
            }

        if (response.code !in 200..299) {
            throw IllegalStateException(extractErrorMessage(response.body))
        }

        val result = parseAiInsightsResult(response.body)
        cacheStore.save(tmdbId = tmdbId, mediaType = mediaType, locale = locale, result = result)
        return result
    }

    private fun parseAiInsightsResult(rawBody: String): AiInsightsResult {
        val json = runCatching { JSONObject(rawBody) }.getOrNull() ?: JSONObject()
        val trivia = json.optString("trivia", "").trim()
        val insightsArray = json.optJSONArray("insights") ?: JSONArray()
        val insights =
            buildList {
                for (i in 0 until insightsArray.length()) {
                    val obj = insightsArray.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val category = obj.optString("category", "").trim()
                    val content = obj.optString("content", "").trim()
                    val type = obj.optString("type", "").trim()
                    if (title.isBlank() || category.isBlank() || content.isBlank() || type.isBlank()) continue
                    add(AiInsightCard(type = type, title = title, category = category, content = content))
                }
            }

        if (insights.isEmpty()) {
            throw IllegalStateException("AI insights are unavailable right now. Please try again in a moment.")
        }

        return AiInsightsResult(insights = insights, trivia = trivia)
    }

    private fun extractErrorMessage(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) {
            return "AI insights are unavailable right now."
        }

        val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
        val message =
            parsed?.let { json ->
                listOf("message", "error", "error_description")
                    .firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() } }
            }

        return message ?: trimmed
    }

    private fun MetadataLabMediaType.toApiValue(): String {
        return when (this) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> "tv"
        }
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 45_000L

        fun create(context: Context, httpClient: CrispyHttpClient): AiInsightsRepository {
            val appContext = context.applicationContext
            return AiInsightsRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                httpClient = httpClient,
                cacheStore = AiInsightsCacheStore(appContext),
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            )
        }
    }
}
