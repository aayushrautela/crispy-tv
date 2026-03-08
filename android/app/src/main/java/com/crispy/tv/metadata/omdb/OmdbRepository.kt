package com.crispy.tv.metadata.omdb

import androidx.compose.runtime.Immutable
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.settings.OmdbSettingsStore
import java.util.Locale
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class OmdbRating(
    val source: String,
    val value: String,
)

@Immutable
data class OmdbDetails(
    val ratings: List<OmdbRating> = emptyList(),
    val metascore: String? = null,
    val imdbRating: String? = null,
    val imdbVotes: String? = null,
    val type: String? = null,
)

class OmdbRepository internal constructor(
    private val settingsStore: OmdbSettingsStore,
    private val httpClient: CrispyHttpClient,
) {
    val isConfigured: Boolean
        get() = settingsStore.loadOmdbKey().isNotBlank()

    suspend fun load(imdbId: String): OmdbDetails? {
        val normalizedId = normalizeImdbId(imdbId) ?: return null
        val normalizedApiKey = settingsStore.loadOmdbKey().trim()
        if (normalizedApiKey.isBlank()) return null

        val response =
            runCatching {
                httpClient.get(
                    url = OMDB_BASE_URL
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("i", normalizedId)
                        .addQueryParameter("apikey", normalizedApiKey)
                        .build(),
                    headers = Headers.headersOf("Accept", "application/json"),
                    callTimeoutMs = 12_000L,
                )
            }.getOrNull() ?: return null

        if (response.code !in 200..299) return null
        if (response.body.isBlank()) return null

        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        if (!json.optString("Response").equals("True", ignoreCase = true)) return null

        val imdbRating = json.optStringValue("imdbRating")
        val metascore = json.optStringValue("Metascore")
        val ratings = parseRatings(json.optJSONArray("Ratings"), imdbRating = imdbRating, metascore = metascore)

        return OmdbDetails(
            ratings = ratings,
            metascore = metascore,
            imdbRating = imdbRating,
            imdbVotes = json.optStringValue("imdbVotes"),
            type = json.optStringValue("Type"),
        )
    }

    private fun parseRatings(
        array: JSONArray?,
        imdbRating: String?,
        metascore: String?,
    ): List<OmdbRating> {
        val ratings = mutableListOf<OmdbRating>()
        val seenSources = linkedSetOf<String>()

        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            val source = item.optStringValue("Source") ?: continue
            val value = item.optStringValue("Value") ?: continue
            val normalizedSource = source.lowercase(Locale.US)
            if (!seenSources.add(normalizedSource)) continue
            ratings += OmdbRating(source = source, value = value)
        }

        if (imdbRating != null && seenSources.add(INTERNET_MOVIE_DATABASE_SOURCE.lowercase(Locale.US))) {
            ratings += OmdbRating(source = INTERNET_MOVIE_DATABASE_SOURCE, value = "$imdbRating/10")
        }

        if (metascore != null && seenSources.add(METACRITIC_SOURCE.lowercase(Locale.US))) {
            ratings += OmdbRating(source = METACRITIC_SOURCE, value = "$metascore/100")
        }

        return ratings
    }

    private fun normalizeImdbId(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized.takeIf { IMDB_ID_REGEX.matches(it) }
    }

    private fun JSONObject.optStringValue(key: String): String? {
        val value = optString(key).trim()
        if (value.isBlank() || value.equals("N/A", ignoreCase = true)) return null
        return value
    }

    private companion object {
        private const val OMDB_BASE_URL = "https://www.omdbapi.com/"
        private const val INTERNET_MOVIE_DATABASE_SOURCE = "Internet Movie Database"
        private const val METACRITIC_SOURCE = "Metacritic"
        private val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)
    }
}
