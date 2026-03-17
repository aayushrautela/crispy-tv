package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.network.CrispyHttpClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AiSearchRepository(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val httpClient: CrispyHttpClient,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
) {
    suspend fun search(
        query: String,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): SearchResultsPayload {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchResultsPayload()
        }
        if (filter == SearchTypeFilter.PEOPLE) {
            return SearchResultsPayload(message = "AI search supports movies and shows right now.")
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to use AI search.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            return SearchResultsPayload(message = "Select a profile to use AI search.")
        }

        val baseUrl = supabaseUrl.trim().trimEnd('/')
        val anonKey = supabaseAnonKey.trim()
        if (baseUrl.isBlank() || anonKey.isBlank()) {
            return SearchResultsPayload(message = "Supabase is not configured.")
        }

        val requestBody =
            JSONObject()
                .put("query", normalizedQuery)
                .put("filter", filter.toAiFilter())
                .put("profileId", profileId)
                .put("locale", locale.toLanguageTag())
                .toString()

        val headers =
            Headers.Builder()
                .add("apikey", anonKey)
                .add("Authorization", "Bearer ${session.accessToken.trim()}")
                .add("Content-Type", "application/json")
                .add("Accept", "application/json")
                .build()

        val response =
            runCatching {
                httpClient.postJson(
                    url = "$baseUrl/functions/v1/ai-search".toHttpUrl(),
                    jsonBody = requestBody,
                    headers = headers,
                    callTimeoutMs = CALL_TIMEOUT_MS,
                )
            }.getOrElse {
                return SearchResultsPayload(message = "AI search is unreachable right now. Please try again.")
            }

        if (response.code !in 200..299) {
            return SearchResultsPayload(message = extractErrorMessage(response.body))
        }

        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: JSONObject()
        val items = json.optJSONArray("items").toCatalogItems()
        val fallbackUsed = json.optBoolean("fallbackUsed", false)
        val message =
            when {
                items.isEmpty() -> "No AI matches found."
                fallbackUsed -> "Showing closest TMDB matches."
                else -> null
            }

        return SearchResultsPayload(items = items, message = message)
    }

    private fun JSONArray?.toCatalogItems(): List<CatalogItem> {
        val array = this ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", 0)
                val mediaType = item.optString("mediaType").trim().ifBlank { item.optString("type").trim() }
                val title = item.optString("title").trim()
                if (id <= 0 || title.isBlank()) {
                    continue
                }

                add(
                    CatalogItem(
                        id = "tmdb:$id",
                        title = title,
                        posterUrl = item.optString("posterUrl").trim().ifBlank { null },
                        backdropUrl = item.optString("backdropUrl").trim().ifBlank { null },
                        addonId = "tmdb",
                        type = if (mediaType == "tv") "series" else mediaType,
                        rating = item.optString("rating").trim().ifBlank { null },
                        year = item.optString("year").trim().ifBlank { null },
                        description = item.optString("overview").trim().ifBlank { null },
                    )
                )
            }
        }
    }

    private fun extractErrorMessage(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) {
            return "AI search is unavailable right now."
        }

        val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
        val message =
            parsed?.let { json ->
                listOf("message", "error", "error_description")
                    .firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() } }
            }

        return message ?: trimmed
    }

    private fun SearchTypeFilter.toAiFilter(): String {
        return when (this) {
            SearchTypeFilter.MOVIES -> "movies"
            SearchTypeFilter.SERIES -> "series"
            else -> "all"
        }
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 45_000L

        fun create(context: Context, httpClient: CrispyHttpClient): AiSearchRepository {
            val appContext = context.applicationContext
            return AiSearchRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                httpClient = httpClient,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
        }
    }
}
