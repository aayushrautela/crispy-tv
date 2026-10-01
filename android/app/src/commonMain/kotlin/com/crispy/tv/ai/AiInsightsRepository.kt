package com.crispy.tv.ai

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.backend.CrispyBackendClient

/**
 * Reads and generates AI insights for one item, behind the caller's session and
 * profile.
 *
 * **Every collaborator arrives as a value.** The `Context`, the service
 * providers and the `applicationContext` reads that used to live in this
 * file's `companion object` are now in `aiInsightsRepository(context)`, in
 * `androidMain` — *a class whose companion constructs it from a `Context` is a
 * composition root wearing a class's clothes, and the split is the class moving
 * while the factory stays.*
 *
 * `languageTag` is a **required** BCP-47 tag, with no default. It was
 * `locale: Locale = Locale.getDefault()` on both members, which meant the
 * device locale decided the wire value and the cache key implicitly, and this
 * file then called `toLanguageTag()` on it at the last moment. `AppGraph` was
 * already holding the tag and reconstructing a `Locale` to hand in, so the
 * value made a round trip — tag to `Locale` to tag — carrying no information in
 * the middle. The tag now crosses as itself, the same way
 * `DetailsUseCases.cachedInsights` and `generateInsights` already receive it
 * (both are `(itemId: String, languageTag: String) -> …`, in `commonMain`).
 */
class AiInsightsRepository(
    private val supabase: AccountApi,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: CrispyBackendClient,
    private val cache: AiInsightsCache,
) {
    fun loadCached(itemId: String, languageTag: String): AiInsightsResult? =
        cache.load(itemId, languageTag)

    suspend fun generate(itemId: String, languageTag: String): AiInsightsResult {
        val session = supabase.ensureValidSession()
            ?: throw IllegalStateException("Sign in to use AI insights.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim().orEmpty()
        if (profileId.isBlank()) {
            throw IllegalStateException("Select a profile to use AI insights.")
        }

        val payload = backend.getAiInsights(
            accessToken = session.accessToken,
            profileId = profileId,
            itemId = itemId,
            locale = languageTag,
        )
        return payload.also { result ->
            cache.save(itemId, languageTag, result)
        }
    }
}
