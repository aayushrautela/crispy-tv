package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.images.toUiResponsiveImageSet
import java.util.Locale

class BackendSearchRepository(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
) {
    suspend fun search(
        query: String,
        @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault(),
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
        )
        return payload.toSearchResultsPayload()
    }

    suspend fun discoverByGenre(
        genreSuggestion: SearchGenreSuggestion,
        @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.getDefault(),
    ): SearchResultsPayload {
        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to browse genres.")

        val payload = backend.searchTitlesByGenre(
            accessToken = session.accessToken,
            genre = genreSuggestion.label,
        )
        return payload.toSearchResultsPayload(defaultGenre = genreSuggestion.label)
    }

    suspend fun suggest(
        query: String,
        locale: Locale = Locale.getDefault(),
    ): List<SearchSuggestion> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return emptyList()
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return emptyList()

        val payload = backend.searchSuggestions(
            accessToken = session.accessToken,
            query = normalizedQuery,
            filter = "all",
            limit = 8,
            locale = locale.toLanguageTag(),
        )
        return payload.suggestions.mapNotNull { it.toSearchSuggestion() }
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

internal fun CrispyBackendClient.MediaItem.toCatalogItem(defaultGenre: String? = null): SearchCatalogItem? {
    val normalizedType =
        when {
            mediaType.equals("anime", ignoreCase = true) -> "anime"
            mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true) -> "show"
            else -> "movie"
        }
    val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
    return SearchCatalogItem(
        id = normalizedMediaKey,
        mediaKey = normalizedMediaKey,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        poster = poster.toUiResponsiveImageSet(),
        backdrop = backdrop.toUiResponsiveImageSet(),
        logo = logo.toUiResponsiveImageSet(),
        addonId = "backend",
        type = normalizedType,
        rating = rating?.toString(),
        year = releaseYear?.toString(),
        genre = genres.firstOrNull() ?: defaultGenre,
        description = overview ?: subtitle,
    )
}
