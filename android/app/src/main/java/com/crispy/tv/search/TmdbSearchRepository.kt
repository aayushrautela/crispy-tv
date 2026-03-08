package com.crispy.tv.search

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.domain.catalog.normalizeTmdbSearchResults
import com.crispy.tv.metadata.tmdb.TmdbJsonClient
import com.crispy.tv.network.CrispyHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

class TmdbSearchRepository internal constructor(
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

        return when (filter) {
            SearchTypeFilter.ALL -> {
                fetchInterleavedCatalogItems(
                    first = EndpointRequest(path = "search/movie", mediaTypeHint = "movie", query = requestQuery),
                    second = EndpointRequest(path = "search/tv", mediaTypeHint = "tv", query = requestQuery)
                )
            }
            SearchTypeFilter.MOVIES -> {
                fetchCatalogItems(path = "search/movie", mediaTypeHint = "movie", query = requestQuery)
            }
            SearchTypeFilter.SERIES -> {
                fetchCatalogItems(path = "search/tv", mediaTypeHint = "tv", query = requestQuery)
            }
            SearchTypeFilter.PEOPLE -> {
                fetchCatalogItems(path = "search/person", mediaTypeHint = "person", query = requestQuery)
            }
        }
    }

    suspend fun discoverByGenre(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
        languageTag: String?
    ): List<CatalogItem> {
        return when (filter) {
            SearchTypeFilter.ALL -> {
                val movieRequest =
                    EndpointRequest(
                        path = "discover/movie",
                        mediaTypeHint = "movie",
                        query = discoverQuery(genreSuggestion.movieGenreId, languageTag),
                        genreLabel = genreSuggestion.label
                    )
                val tvRequest =
                    genreSuggestion.tvGenreId?.let { genreId ->
                        EndpointRequest(
                            path = "discover/tv",
                            mediaTypeHint = "tv",
                            query = discoverQuery(genreId, languageTag),
                            genreLabel = genreSuggestion.label
                        )
                    }

                if (tvRequest == null) {
                    fetchCatalogItems(movieRequest)
                } else {
                    fetchInterleavedCatalogItems(movieRequest, tvRequest)
                }
            }
            SearchTypeFilter.MOVIES -> {
                fetchCatalogItems(
                    path = "discover/movie",
                    mediaTypeHint = "movie",
                    query = discoverQuery(genreSuggestion.movieGenreId, languageTag),
                    genreLabel = genreSuggestion.label
                )
            }
            SearchTypeFilter.SERIES -> {
                val genreId = genreSuggestion.tvGenreId ?: return emptyList()
                fetchCatalogItems(
                    path = "discover/tv",
                    mediaTypeHint = "tv",
                    query = discoverQuery(genreId, languageTag),
                    genreLabel = genreSuggestion.label
                )
            }
            SearchTypeFilter.PEOPLE -> emptyList()
        }
    }

    private suspend fun fetchInterleavedCatalogItems(
        first: EndpointRequest,
        second: EndpointRequest
    ): List<CatalogItem> =
        coroutineScope {
            val firstItems = async { fetchCatalogItems(first) }
            val secondItems = async { fetchCatalogItems(second) }
            interleave(firstItems.await(), secondItems.await())
        }

    private suspend fun fetchCatalogItems(request: EndpointRequest): List<CatalogItem> {
        return fetchCatalogItems(
            path = request.path,
            mediaTypeHint = request.mediaTypeHint,
            query = request.query,
            genreLabel = request.genreLabel
        )
    }

    private suspend fun fetchCatalogItems(
        path: String,
        mediaTypeHint: String?,
        query: Map<String, String?>,
        genreLabel: String? = null
    ): List<CatalogItem> {
        val inputs = fetchInputs(path = path, mediaTypeHint = mediaTypeHint, query = query)
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
                genre = genreLabel
            )
        }
    }

    private fun discoverQuery(genreId: Int, languageTag: String?): Map<String, String?> {
        return mapOf(
            "with_genres" to genreId.toString(),
            "page" to "1",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "language" to languageTag
        )
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

    private data class EndpointRequest(
        val path: String,
        val mediaTypeHint: String?,
        val query: Map<String, String?>,
        val genreLabel: String? = null
    )
}
