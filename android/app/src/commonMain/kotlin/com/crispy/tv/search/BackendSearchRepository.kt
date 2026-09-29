package com.crispy.tv.search

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi

class BackendSearchRepository(
    private val supabase: AccountApi,
    private val backend: BackendApi,
) {
    suspend fun search(query: String): SearchResultsPayload {
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

    suspend fun discoverByGenre(genreSuggestion: SearchGenreSuggestion): SearchResultsPayload {
        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to browse genres.")

        val payload = backend.searchTitlesByGenre(
            accessToken = session.accessToken,
            genre = genreSuggestion.label,
        )
        return payload.toSearchResultsPayload(defaultGenre = genreSuggestion.label)
    }
}
