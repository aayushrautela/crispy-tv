package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.CrispyHttpClient
import java.util.Locale

class AiSearchRepository(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: CrispyBackendClient,
    @Suppress("UNUSED_PARAMETER") httpClient: CrispyHttpClient,
) {
    suspend fun search(
        query: String,
        locale: Locale = Locale.getDefault(),
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
            locale = locale.toLanguageTag(),
        )
        return payload.toSearchResultsPayload()
    }

    companion object {
        fun create(context: Context, httpClient: CrispyHttpClient): AiSearchRepository {
            val appContext = context.applicationContext
            return AiSearchRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                backend = BackendServicesProvider.backendClient(appContext),
                httpClient = httpClient,
            )
        }
    }
}
