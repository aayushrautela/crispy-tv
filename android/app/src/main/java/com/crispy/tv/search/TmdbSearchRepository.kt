package com.crispy.tv.search

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.catalog.TmdbSearchResultInput
import com.crispy.tv.domain.catalog.normalizeTmdbSearchResults
import com.crispy.tv.metadata.tmdb.TmdbSearchRemoteDataSource
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.ratings.formatRating
import java.util.Locale

class TmdbSearchRepository internal constructor(
    private val remoteDataSource: TmdbSearchRemoteDataSource,
) {
    constructor(apiKey: String, httpClient: CrispyHttpClient) : this(
        TmdbSearchRemoteDataSource(com.crispy.tv.metadata.tmdb.TmdbJsonClient(apiKey = apiKey, httpClient = httpClient))
    )

    suspend fun search(
        query: String,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): List<CatalogItem> {
        val inputs = remoteDataSource.searchInputs(query = query, filter = filter, locale = locale)
        return inputs.toCatalogItems()
    }

    suspend fun discoverByGenre(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): List<CatalogItem> {
        val inputs = remoteDataSource.discoverInputs(genreSuggestion = genreSuggestion, filter = filter, locale = locale)
        return inputs.toCatalogItems(genreLabel = genreSuggestion.label)
    }

    private fun List<TmdbSearchResultInput>.toCatalogItems(genreLabel: String? = null): List<CatalogItem> {
        val normalized = normalizeTmdbSearchResults(this)
        val withImagesOnly = normalized.filter { item -> !item.imageUrl.isNullOrBlank() }
        return withImagesOnly.map { item ->
            CatalogItem(
                id = item.id,
                title = item.title,
                posterUrl = item.imageUrl,
                backdropUrl = null,
                addonId = "tmdb",
                type = item.type,
                rating = formatRating(item.rating),
                year = item.year?.toString(),
                genre = genreLabel
            )
        }
    }
}
