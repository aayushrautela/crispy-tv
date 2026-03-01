package com.crispy.tv.search

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.domain.catalog.normalizeTmdbSearchResults
import com.crispy.tv.metadata.tmdb.TmdbJsonClient
import com.crispy.tv.network.CrispyHttpClient
import org.json.JSONArray
import org.json.JSONObject

class TmdbSearchRepository(
    private val tmdbClient: TmdbJsonClient,
) {
    constructor(apiKey: String, httpClient: CrispyHttpClient) : this(
        TmdbJsonClient(apiKey = apiKey, httpClient = httpClient)
    )

    suspend fun search(query: String, filter: SearchTypeFilter, languageTag: String?): List<CatalogItem> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return emptyList()
        }

        val requestQuery =
            mapOf(
                "query" to trimmedQuery,
                "page" to "1",
                "include_adult" to "false",
                "language" to languageTag
            )

        val inputs =
            when (filter) {
                SearchTypeFilter.ALL -> {
                    val movies = fetchInputs(path = "search/movie", mediaTypeHint = "movie", query = requestQuery)
                    val series = fetchInputs(path = "search/tv", mediaTypeHint = "tv", query = requestQuery)
                    interleave(movies, series)
                }
                SearchTypeFilter.MOVIES -> fetchInputs(path = "search/movie", mediaTypeHint = "movie", query = requestQuery)
                SearchTypeFilter.SERIES -> fetchInputs(path = "search/tv", mediaTypeHint = "tv", query = requestQuery)
                SearchTypeFilter.PEOPLE -> fetchInputs(path = "search/person", mediaTypeHint = "person", query = requestQuery)
            }

        val normalized = normalizeTmdbSearchResults(inputs)
        val withImagesOnly = normalized.filter { item -> !item.imageUrl.isNullOrBlank() }
        return withImagesOnly.map { item ->
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

    private suspend fun fetchInputs(
        path: String,
        mediaTypeHint: String?,
        query: Map<String, String?>
    ): List<TmdbSearchResultInput> {
        val json = tmdbClient.getJson(path = path, query = query) ?: return emptyList()
        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        return buildList {
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
                        posterPath = result.optStringOrNull("poster_path") ?: result.optStringOrNull("backdrop_path"),
                        profilePath = result.optStringOrNull("profile_path"),
                        voteAverage = result.optDoubleOrNull("vote_average")
                    )
                )
            }
        }
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a

        val out = ArrayList<T>(a.size + b.size)
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
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
