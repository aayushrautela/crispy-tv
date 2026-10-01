package com.crispy.tv.ai

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider

/**
 * The `androidMain` construction site for [AiInsightsRepository] — the same role
 * `search/SearchViewModelFactory` and `details/DetailsViewModelFactory` play for
 * their view models, and the same reason they are here: **a `Context` is the one
 * dependency no `commonMain` file can name**, and every provider below needs one.
 *
 * **There is deliberately no language tag here.** The three view-model factories
 * take a `languageTagProvider` because their view models were constructed with
 * one and the platform had to supply it; this repository is not, because
 * `languageTag` is a **per-call** parameter on both of its members. That is not
 * an accident of the signature — the cache is keyed by `(itemId, languageTag)`,
 * so one instance has to serve every language the device reports, and a
 * language pinned at construction would be a second repository the moment a user
 * changed it. `AppGraph` holds the tag in scope at the call site
 * (`cachedInsights = { itemId, languageTag -> … }`, and
 * `DetailsUseCases` declares both of its lambdas that way in `commonMain`), so
 * the value crosses from there and the factory never sees it.
 */
fun aiInsightsRepository(context: Context): AiInsightsRepository {
    val appContext = context.applicationContext
    return AiInsightsRepository(
        supabase = SupabaseServicesProvider.accountClient(appContext),
        activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
        backend = BackendServicesProvider.backendClient(appContext),
        cache = AiInsightsCacheStore(appContext),
    )
}
