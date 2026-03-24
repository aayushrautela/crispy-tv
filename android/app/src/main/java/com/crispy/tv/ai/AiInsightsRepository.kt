package com.crispy.tv.ai

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale

class AiInsightsRepository(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: CrispyBackendClient,
    @Suppress("UNUSED_PARAMETER") httpClient: CrispyHttpClient,
    private val cacheStore: AiInsightsCacheStore,
) {
    fun loadCached(
        tmdbId: Int,
        mediaType: MetadataLabMediaType,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? = cacheStore.load(tmdbId, mediaType, locale)

    suspend fun generate(
        tmdbId: Int,
        mediaType: MetadataLabMediaType,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult {
        val session = supabase.ensureValidSession()
            ?: throw IllegalStateException("Sign in to use AI insights.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            throw IllegalStateException("Select a profile to use AI insights.")
        }

        val payload = backend.getAiInsights(
            accessToken = session.accessToken,
            profileId = profileId,
            tmdbId = tmdbId,
            mediaType = mediaType.toBackendMediaType(),
            locale = locale.toLanguageTag(),
        )
        return AiInsightsResult(
            insights = payload.insights.map { card ->
                AiInsightCard(
                    type = card.type,
                    title = card.title,
                    category = card.category,
                    content = card.content,
                )
            },
            trivia = payload.trivia,
        ).also { result ->
            cacheStore.save(tmdbId, mediaType, locale, result)
        }
    }

    companion object {
        fun create(context: Context, httpClient: CrispyHttpClient): AiInsightsRepository {
            val appContext = context.applicationContext
            return AiInsightsRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                backend = BackendServicesProvider.backendClient(appContext),
                httpClient = httpClient,
                cacheStore = AiInsightsCacheStore(appContext),
            )
        }
    }
}

private fun MetadataLabMediaType.toBackendMediaType(): String {
    return when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "tv"
    }
}
