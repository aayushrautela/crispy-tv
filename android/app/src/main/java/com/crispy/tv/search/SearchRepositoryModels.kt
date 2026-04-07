package com.crispy.tv.search

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem

data class SearchResultBuckets(
    val all: List<SearchCatalogItem> = emptyList(),
    val movies: List<SearchCatalogItem> = emptyList(),
    val series: List<SearchCatalogItem> = emptyList(),
    val anime: List<SearchCatalogItem> = emptyList(),
) {
    fun itemsFor(category: SearchCategory): List<SearchCatalogItem> {
        return when (category) {
            SearchCategory.ALL -> all
            SearchCategory.MOVIES -> movies
            SearchCategory.SERIES -> series
            SearchCategory.ANIME -> anime
        }
    }

    val isEmpty: Boolean
        get() = all.isEmpty() && movies.isEmpty() && series.isEmpty() && anime.isEmpty()
}

data class SearchResultsPayload(
    val query: String = "",
    val buckets: SearchResultBuckets = SearchResultBuckets(),
    val message: String? = null,
)

typealias SearchCatalogItem = CatalogItem

internal fun CrispyBackendClient.SearchResultsResponse.toSearchResultsPayload(defaultGenre: String? = null): SearchResultsPayload {
    return SearchResultsPayload(
        query = query,
        buckets = SearchResultBuckets(
            all = all.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
            movies = movies.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
            series = series.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
            anime = anime.mapNotNull { it.toCatalogItem(defaultGenre = defaultGenre) },
        ),
    )
}
