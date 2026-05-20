package com.crispy.tv.search

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem

data class SearchResultBuckets(
    val movies: List<SearchCatalogItem> = emptyList(),
    val series: List<SearchCatalogItem> = emptyList(),
    val people: List<SearchCatalogItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = movies.isEmpty() && series.isEmpty() && people.isEmpty()
}

data class SearchResultsPayload(
    val query: String = "",
    val buckets: SearchResultBuckets = SearchResultBuckets(),
    val message: String? = null,
)

typealias SearchCatalogItem = CatalogItem

internal fun CrispyBackendClient.PersonSearchResultItem.toCatalogItem(defaultGenre: String? = null): SearchCatalogItem? {
    val normalizedName = name.trim().ifBlank { return null }
    val personItemId = "person:$tmdbPersonId"
    return SearchCatalogItem(
        id = personItemId,
        itemId = personItemId,
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
    val itemType: String,
    val year: Int?,
    val itemId: String,
)

internal fun CrispyBackendClient.SearchSuggestionItem.toSearchSuggestion(): SearchSuggestion? {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isBlank()) return null
    val normalizedType = when (itemType) {
        "tv" -> "show"
        else -> "movie"
    }
    val itemId = itemId.trim().ifBlank { return null }
    return SearchSuggestion(
        title = normalizedTitle,
        itemType = normalizedType,
        year = year,
        itemId = itemId,
    )
}

internal fun CrispyBackendClient.SearchResultsResponse.toSearchResultsPayload(defaultGenre: String? = null): SearchResultsPayload {
    return SearchResultsPayload(
        query = query,
        buckets = SearchResultBuckets(
            movies = movies.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
            series = series.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
            people = people.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
        ),
    )
}
