package com.crispy.tv.ai

/**
 * The cache seam [AiInsightsRepository] reads through.
 *
 * [AiInsightsRepository] is platform-free — its three other collaborators
 * ([com.crispy.tv.accounts.AccountApi],
 * [com.crispy.tv.accounts.ActiveProfileStore] and
 * [com.crispy.tv.backend.CrispyBackendClient]) all live in `:backend`'s
 * `commonMain` — so the only thing that pinned it to `androidMain` was the
 * concrete `AiInsightsCacheStore` in its constructor, a `Context` +
 * `SharedPreferences` holder. *A file whose parameter names a concrete
 * `Context` holder cannot be read from `commonMain` no matter how portable its
 * own body is*, which is why this is an interface and not a moved class.
 *
 * The cache key is a **BCP-47 tag string**, not a `Locale`. That is the value
 * the wire already speaks (`backend.getAiInsights(locale = …)`) and the value
 * the cache key was already derived from — `keyFor` used to call
 * `locale.toLanguageTag()` itself, so the repository held a `Locale` only to
 * hand it to code that immediately turned it back into a tag. The parameter is
 * **required and has no default**: a defaulted `Locale.getDefault()` was the
 * platform leaking into a shared signature, and a cache keyed by an implicit
 * locale is a cache two devices disagree about.
 */
interface AiInsightsCache {
    fun load(itemId: String, languageTag: String): AiInsightsResult?

    fun save(itemId: String, languageTag: String, result: AiInsightsResult)
}
