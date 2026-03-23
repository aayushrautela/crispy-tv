package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.network.CrispyHttpClient
import java.util.Locale

class AiSearchRepository(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    @Suppress("UNUSED_PARAMETER") httpClient: CrispyHttpClient,
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
        if (filter == SearchTypeFilter.PEOPLE) {
            return SearchResultsPayload(message = "AI search supports movies and shows right now.")
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            ?: return SearchResultsPayload(message = "Sign in to use AI search.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            return SearchResultsPayload(message = "Select a profile to use AI search.")
        }

        return SearchResultsPayload(
            message = "AI search is temporarily disabled until it has a backend /v1 replacement.",
        )
    }

    companion object {
        fun create(context: Context, httpClient: CrispyHttpClient): AiSearchRepository {
            val appContext = context.applicationContext
            return AiSearchRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                httpClient = httpClient,
            )
        }
    }
}
