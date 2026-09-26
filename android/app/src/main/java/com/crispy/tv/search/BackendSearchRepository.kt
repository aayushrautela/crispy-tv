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

    /**
     * Keyword completions for the search box. A suggestion is only a name, so
     * nothing is resolved here; the caller runs a real search to turn a picked
     * suggestion into media. Returns an empty list when unauthenticated so a
     * missing session never surfaces as an error while the user is typing.
     */
    suspend fun suggestions(query: String): List<String> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull() ?: return emptyList()

        return backend.searchSuggestions(
            accessToken = session.accessToken,
            query = normalizedQuery,
        ).suggestions
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
