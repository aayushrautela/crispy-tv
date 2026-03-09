package com.crispy.tv.metadata.omdb

import androidx.compose.runtime.Immutable
import com.crispy.tv.domain.metadata.OmdbDetails as DomainOmdbDetails
import com.crispy.tv.domain.metadata.OmdbRatingInput
import com.crispy.tv.domain.metadata.normalizeOmdbDetails
import com.crispy.tv.domain.metadata.normalizeOmdbImdbId
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.settings.OmdbSettingsStore
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
        val normalizedId = normalizeOmdbImdbId(imdbId) ?: return null
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

        return normalizeOmdbDetails(
            ratings = parseRatingInputs(json.optJSONArray("Ratings")),
            metascore = json.optRawString("Metascore"),
            imdbRating = json.optRawString("imdbRating"),
            imdbVotes = json.optRawString("imdbVotes"),
            type = json.optRawString("Type"),
        )
            .toAppModel()
    }

    private fun parseRatingInputs(array: JSONArray?): List<OmdbRatingInput> {
        val ratings = mutableListOf<OmdbRatingInput>()
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array?.optJSONObject(index) ?: continue
            ratings += OmdbRatingInput(
                source = item.optRawString("Source"),
                value = item.optRawString("Value"),
            )
        }
        return ratings
    }

    private fun JSONObject.optRawString(key: String): String? {
        if (isNull(key) || !has(key)) return null
        return optString(key, null)
    }

    private fun DomainOmdbDetails.toAppModel(): OmdbDetails {
        return OmdbDetails(
            ratings = ratings.map { OmdbRating(source = it.source, value = it.value) },
            metascore = metascore,
            imdbRating = imdbRating,
            imdbVotes = imdbVotes,
            type = type,
        )
    }

    private companion object {
        private const val OMDB_BASE_URL = "https://www.omdbapi.com/"
    }
}
