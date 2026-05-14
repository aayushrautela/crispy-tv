package com.crispy.tv.search

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem

data class SearchResultBuckets(
    val all: List<SearchCatalogItem> = emptyList(),
    val movies: List<SearchCatalogItem> = emptyList(),
    val series: List<SearchCatalogItem> = emptyList(),
    val people: List<SearchCatalogItem> = emptyList(),
) {
    fun itemsFor(category: SearchCategory): List<SearchCatalogItem> {
        return when (category) {
            SearchCategory.ALL -> all
            SearchCategory.MOVIES -> movies
            SearchCategory.SERIES -> series
            SearchCategory.PEOPLE -> people
        }
    }

    val isEmpty: Boolean
        get() = all.isEmpty() && movies.isEmpty() && series.isEmpty() && people.isEmpty()
}

data class SearchResultsPayload(
    val query: String = "",
    val buckets: SearchResultBuckets = SearchResultBuckets(),
    val message: String? = null,
)

typealias SearchCatalogItem = CatalogItem

internal fun CrispyBackendClient.PersonSearchResultItem.toCatalogItem(defaultGenre: String? = null): SearchCatalogItem? {
    val normalizedName = name.trim().ifBlank { return null }
    return SearchCatalogItem(
        id = tmdbPersonId.toString(),
        mediaKey = "tmdb:person:$tmdbPersonId",
        title = normalizedName,
        posterUrl = profileUrl?.trim()?.takeIf { it.isNotBlank() },
        backdropUrl = null,
        addonId = "backend",
        type = "person",
        rating = null,
        year = null,
        genre = knownForDepartment?.trim()?.takeIf { it.isNotBlank() } ?: defaultGenre,
        description = knownForTitles.takeIf { it.isNotEmpty() }?.joinToString(" • "),
    )
}

data class SearchSuggestion(
    val title: String,
    val mediaType: String,
    val year: Int?,
    val mediaKey: String,
)

internal fun CrispyBackendClient.SearchSuggestionItem.toSearchSuggestion(): SearchSuggestion? {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isBlank()) return null
    val normalizedType = when (mediaType) {
        "tv" -> "series"
        else -> "movie"
    }
    val mediaKey = "tmdb:$normalizedType:$tmdbId"
    return SearchSuggestion(
        title = normalizedTitle,
        mediaType = normalizedType,
        year = year,
        mediaKey = mediaKey,
    )
}

internal fun CrispyBackendClient.SearchResultsResponse.toSearchResultsPayload(defaultGenre: String? = null): SearchResultsPayload {
    return SearchResultsPayload(
        query = query,
        buckets = SearchResultBuckets(
            all = all.mapNotNull { it.mediaItem.toCatalogItem(defaultGenre = defaultGenre) },
            movies = movies.mapNotNull { it.mediaItem.toCatalogItem(defaultGenre = defaultGenre) },
            series = series.mapNotNull { it.mediaItem.toCatalogItem(defaultGenre = defaultGenre) },
            people = people.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
        ),
    )
}
