package com.crispy.tv.metadata.tmdb

import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.search.SearchGenreSuggestion
import com.crispy.tv.search.SearchTypeFilter
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

internal class TmdbSearchRemoteDataSource(
    private val tmdbClient: TmdbJsonClient,
) {
    suspend fun searchInputs(
        query: String,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): List<TmdbSearchResultInput> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return emptyList()
        }

        val requestQuery =
            mapOf(
                "query" to trimmedQuery,
                "page" to "1",
                "include_adult" to "false",
                "language" to locale.toTmdbLanguageTag(),
            )

        return when (filter) {
            SearchTypeFilter.ALL -> {
                fetchInterleavedInputs(
                    first = EndpointRequest(path = "search/movie", mediaTypeHint = "movie", query = requestQuery),
                    second = EndpointRequest(path = "search/tv", mediaTypeHint = "tv", query = requestQuery),
                )
            }
            SearchTypeFilter.MOVIES -> {
                fetchInputs(path = "search/movie", mediaTypeHint = "movie", query = requestQuery)
            }
            SearchTypeFilter.SERIES -> {
                fetchInputs(path = "search/tv", mediaTypeHint = "tv", query = requestQuery)
            }
            SearchTypeFilter.PEOPLE -> {
                fetchInputs(path = "search/person", mediaTypeHint = "person", query = requestQuery)
            }
        }
    }

    suspend fun discoverInputs(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): List<TmdbSearchResultInput> {
        return when (filter) {
            SearchTypeFilter.ALL -> {
                val movieRequest =
                    EndpointRequest(
                        path = "discover/movie",
                        mediaTypeHint = "movie",
                        query = discoverQuery(genreSuggestion.movieGenreId, locale),
                    )
                val tvRequest =
                    genreSuggestion.tvGenreId?.let { genreId ->
                        EndpointRequest(
                            path = "discover/tv",
                            mediaTypeHint = "tv",
                            query = discoverQuery(genreId, locale),
                        )
                    }

                if (tvRequest == null) {
                    fetchInputs(movieRequest)
                } else {
                    fetchInterleavedInputs(movieRequest, tvRequest)
                }
            }
            SearchTypeFilter.MOVIES -> {
                fetchInputs(
                    path = "discover/movie",
                    mediaTypeHint = "movie",
                    query = discoverQuery(genreSuggestion.movieGenreId, locale),
                )
            }
            SearchTypeFilter.SERIES -> {
                val genreId = genreSuggestion.tvGenreId ?: return emptyList()
                fetchInputs(
                    path = "discover/tv",
                    mediaTypeHint = "tv",
                    query = discoverQuery(genreId, locale),
                )
            }
            SearchTypeFilter.PEOPLE -> emptyList()
        }
    }

    private suspend fun fetchInterleavedInputs(
        first: EndpointRequest,
        second: EndpointRequest,
    ): List<TmdbSearchResultInput> =
        coroutineScope {
            val firstItems = async { fetchInputs(first) }
            val secondItems = async { fetchInputs(second) }
            interleave(firstItems.await(), secondItems.await())
        }

    private suspend fun fetchInputs(request: EndpointRequest): List<TmdbSearchResultInput> {
        return fetchInputs(path = request.path, mediaTypeHint = request.mediaTypeHint, query = request.query)
    }

    private suspend fun fetchInputs(
        path: String,
        mediaTypeHint: String?,
        query: Map<String, String>,
    ): List<TmdbSearchResultInput> {
        val json = tmdbClient.getJson(path = path, query = query) ?: return emptyList()
        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        return buildList {
            for (index in 0 until resultsArray.length()) {
                val result = resultsArray.optJSONObject(index) ?: continue
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
                        voteAverage = result.optDoubleOrNull("vote_average"),
                    )
                )
            }
        }
    }

    private fun discoverQuery(genreId: Int, locale: Locale): Map<String, String> {
        return mapOf(
            "with_genres" to genreId.toString(),
            "page" to "1",
            "include_adult" to "false",
            "sort_by" to "popularity.desc",
            "language" to locale.toTmdbLanguageTag(),
        )
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a

        val out = ArrayList<T>(a.size + b.size)
        val maxSize = maxOf(a.size, b.size)
        for (index in 0 until maxSize) {
            if (index < a.size) out.add(a[index])
            if (index < b.size) out.add(b[index])
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val raw = optString(key, "").trim()
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }

    private data class EndpointRequest(
        val path: String,
        val mediaTypeHint: String?,
        val query: Map<String, String>,
    )
}
