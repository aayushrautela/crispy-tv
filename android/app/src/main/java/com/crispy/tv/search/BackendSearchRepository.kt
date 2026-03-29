package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import java.util.Locale

class BackendSearchRepository(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
) {
    suspend fun search(
        query: String,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): SearchResultsPayload {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchResultsPayload()
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to search.")

        val payload = backend.searchTitles(
            accessToken = session.accessToken,
            query = normalizedQuery,
            filter = filter.toBackendSearchFilter(),
            locale = locale.toLanguageTag(),
        )
        return SearchResultsPayload(items = payload.items.map { it.toCatalogItem() })
    }

    suspend fun discoverByGenre(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
        locale: Locale = Locale.getDefault(),
    ): SearchResultsPayload {
        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to browse genres.")

        val payload = backend.searchTitlesByGenre(
            accessToken = session.accessToken,
            genre = genreSuggestion.label,
            filter = filter.toBackendSearchFilter(),
            locale = locale.toLanguageTag(),
        )
        return SearchResultsPayload(items = payload.items.map { it.toCatalogItem(defaultGenre = genreSuggestion.label) })
    }

    companion object {
        fun create(context: Context): BackendSearchRepository {
            val appContext = context.applicationContext
            return BackendSearchRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                backend = BackendServicesProvider.backendClient(appContext),
            )
        }
    }
}

private fun SearchTypeFilter.toBackendSearchFilter(): String? {
    return when (this) {
        SearchTypeFilter.ALL -> null
        SearchTypeFilter.MOVIES -> "movies"
        SearchTypeFilter.SERIES -> "series"
        SearchTypeFilter.ANIME -> "anime"
    }
}

internal fun CrispyBackendClient.BackendMetadataItem.toCatalogItem(defaultGenre: String? = null): SearchCatalogItem {
    return SearchCatalogItem(
        id = id,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        addonId = "backend",
        type =
            when {
                mediaType.equals("anime", ignoreCase = true) -> "anime"
                mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true) -> "series"
                else -> "movie"
            },
        rating = rating,
        year = year,
        genre = genre ?: defaultGenre,
        description = summary,
    )
}
