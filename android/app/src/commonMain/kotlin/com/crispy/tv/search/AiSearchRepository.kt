package com.crispy.tv.search

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi

class AiSearchRepository(
    private val supabase: AccountApi,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: BackendApi,
) {
    /**
     * [languageTag] is a BCP-47 tag such as `en-GB` and is sent to the backend
     * verbatim. It is a `String` rather than a `java.util.Locale` because a tag
     * is what crosses the wire — the conversion used to happen here, and doing
     * it at the edge means the repository holds no JVM type and the platform
     * locale stays in the composition root where it belongs. There is
     * deliberately no default: a repository that guesses a language would hide
     * a missing locale decision behind a plausible-looking request.
     */
    suspend fun search(
        query: String,
        languageTag: String,
    ): SearchResultsPayload {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchResultsPayload()
        }
        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to use AI search.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            return SearchResultsPayload(message = "Select a profile to use AI search.")
        }

        val payload = backend.searchAiTitles(
            accessToken = session.accessToken,
            profileId = profileId,
            query = normalizedQuery,
            locale = languageTag,
        )
        return payload.toSearchResultsPayload()
    }
}
