package com.crispy.tv.search

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.domain.catalog.normalizeTmdbSearchResults
import com.crispy.tv.metadata.tmdb.TmdbJsonClient
import com.crispy.tv.network.CrispyHttpClient
import org.json.JSONArray
import org.json.JSONObject

class TmdbSearchRepository(
    apiKey: String,
    httpClient: CrispyHttpClient
) {
    private val tmdbClient = TmdbJsonClient(apiKey = apiKey, httpClient = httpClient)

    suspend fun search(query: String, filter: SearchTypeFilter, languageTag: String?): List<CatalogItem> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return emptyList()
        }

        val (path, mediaTypeHint) =
            when (filter) {
                SearchTypeFilter.ALL -> "search/multi" to null
                SearchTypeFilter.MOVIES -> "search/movie" to "movie"
                SearchTypeFilter.SERIES -> "search/tv" to "tv"
                SearchTypeFilter.PEOPLE -> "search/person" to "person"
            }

        val json =
            tmdbClient.getJson(
                path = path,
                query =
                    mapOf(
                        "query" to trimmedQuery,
                        "page" to "1",
                        "include_adult" to "false",
                        "language" to languageTag
                    )
            ) ?: return emptyList()

        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        val inputs =
            buildList {
                for (i in 0 until resultsArray.length()) {
                    val result = resultsArray.optJSONObject(i) ?: continue
                    val mediaType = result.optStringOrNull("media_type") ?: mediaTypeHint ?: continue

                    add(
                        TmdbSearchResultInput(
                            mediaType = mediaType,
                            id = result.optInt("id", 0),
                            title = result.optStringOrNull("title"),
                            name = result.optStringOrNull("name"),
                            releaseDate = result.optStringOrNull("release_date"),
                            firstAirDate = result.optStringOrNull("first_air_date"),
                            posterPath = result.optStringOrNull("poster_path"),
                            profilePath = result.optStringOrNull("profile_path"),
                            voteAverage = result.optDoubleOrNull("vote_average")
                        )
                    )
                }
            }

        val normalized = normalizeTmdbSearchResults(inputs)
        return normalized.map { item ->
            CatalogItem(
                id = item.id,
                title = item.title,
                posterUrl = item.imageUrl,
                backdropUrl = null,
                addonId = "tmdb",
                type = item.type,
                rating = item.rating?.toString(),
                year = item.year?.toString(),
                genre = null
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val raw = optString(key, "").trim()
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        val value = opt(key) ?: return null
        val number =
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        return number?.takeIf { it.isFinite() }
    }
}
