package com.crispy.tv.addons.streams

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.lookup.StreamLookupTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared, id-driven stream resolution layer.
 *
 * Wraps a single [AddonStreamsLoader] instance and caches completed results keyed by
 * [StreamLookupTarget] so that Details and the Player never hit the addons twice for the
 * same title. Both surfaces obtain the same process-wide instance via [StreamResolverProvider].
 *
 * The caching is the whole reason this is a class rather than the [StreamResolver]
 * interface: it is the only behaviour it adds, and only [cachedStreams] can observe it.
 *
 * ## What used to hold it in `androidMain`, and what each of those actually was
 *
 * This KDoc said it *"stays in `androidMain` because [AddonStreamsService] is there — it
 * is built from a `Context` and an `okhttp3` client — so the port is the half that could
 * travel"*. Both halves were false, and so was the conclusion, which is the part worth
 * keeping:
 *
 * - **There is no `okhttp3` client anywhere in the chain.** `AddonStreamsService` names
 *   no `okhttp3` type; its collaborator is `CrispyHttpClient`, `commonMain`'s own
 *   interface over `data class CrispyHttpResponse(val code: Int, val body: String)`.
 *   `git grep -l 'import okhttp3' | grep '/src/commonMain/'` returns zero hits repo-wide.
 * - **[AddonStreamsService] is in `commonMain` now**, so the one-way edge this file sat
 *   behind is gone. It is named through [AddonStreamsLoader] rather than the class
 *   because the class's constructor needs a registry and an HTTP client, which is what
 *   made this file untestable while it was pinned to it.
 * - The pins that *were* real were three, and all three are arguments:
 *   `android.util.Log` → `logger: AppLogger`; `java.util.Locale` → **deleted**, because
 *   the single use was `target.mediaType.name.lowercase(Locale.US)` and Kotlin's
 *   `lowercase()` is locale-invariant — a locale is a *rendering* context, so the
 *   Turkish dotless-i rule put `ı` in a cache key on a Turkish device; and
 *   `System.currentTimeMillis()` → `nowMs`.
 *
 * `nowMs` is not a `Dispatchers.IO`-style necessity — it is what makes the TTL
 * **testable**: [cachedStreams]'s expiry rule is a comparison against the clock, so a
 * fixed clock can never distinguish "expired" from "not yet", and that rule is the one
 * member this class adds over [StreamResolver].
 */
class CachingStreamResolver(
    private val addonStreamsLoader: AddonStreamsLoader,
    private val logger: AppLogger,
    private val nowMs: () -> Long,
) : StreamResolver {
    private data class CacheEntry(
        val results: List<ProviderStreamsResult>,
        val expiresAtEpochMs: Long,
    )

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    private fun cacheKey(target: StreamLookupTarget): String =
        "${target.mediaType.name.lowercase()}:${target.lookupId.trim()}"

    override suspend fun resolve(
        target: StreamLookupTarget,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
        onProviderResult: ((ProviderStreamsResult) -> Unit)?,
    ): List<ProviderStreamsResult> {
        logger.debug(TAG, "resolve() start mediaType=${target.mediaType} lookupId='${target.lookupId}'")
        val results =
            try {
                addonStreamsLoader.loadStreams(
                    mediaType = target.mediaType,
                    lookupId = target.lookupId,
                    onProvidersResolved = onProvidersResolved,
                    onProviderResult = onProviderResult,
                )
            } catch (e: Throwable) {
                logger.error(TAG, "resolve() failed mediaType=${target.mediaType} lookupId='${target.lookupId}'", e)
                throw e
            }
        logger.debug(TAG, "resolve() done mediaType=${target.mediaType} lookupId='${target.lookupId}' count=${results.size}")
        cacheLock.withLock {
            cache[cacheKey(target)] = CacheEntry(results, nowMs() + RESOLVE_TTL_MS)
        }
        return results
    }

    override suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>? {
        cacheLock.withLock {
            val entry = cache[cacheKey(target)] ?: return null
            if (nowMs() > entry.expiresAtEpochMs) {
                cache.remove(cacheKey(target))
                return null
            }
            return entry.results
        }
    }

    /**
     * How many entries the resolve cache is holding, expired ones included.
     *
     * This exists because an expired entry's removal is **unobservable through the public
     * contract**: [cachedStreams] answers `null` for a stale key whether or not the entry was
     * evicted, so a suite written against the contract alone cannot see the line at all. Removing
     * `cache.remove(...)` from the expiry branch leaves every assertion green and turns the map into
     * an unbounded one that keeps a dead result set per target ever resolved.
     *
     * So the eviction is real, and the honest way to pin it is to widen the observation rather than
     * to delete the behaviour. `internal` keeps it out of `:app`, which consumes this class.
     */
    internal suspend fun cachedEntryCount(): Int = cacheLock.withLock { cache.size }

    override suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        logger.debug(TAG, "loadProviderStreams() start mediaType=$mediaType lookupId='$lookupId' providerId='$providerId'")
        return try {
            addonStreamsLoader
                .loadProviderStreams(
                    mediaType = mediaType,
                    lookupId = lookupId,
                    providerId = providerId,
                ).also {
                    logger.debug(TAG, "loadProviderStreams() done providerId='$providerId' found=${it != null}")
                }
        } catch (e: Throwable) {
            logger.error(TAG, "loadProviderStreams() failed providerId='$providerId'", e)
            throw e
        }
    }

    override suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle> = addonStreamsLoader.fetchAddonSubtitles(mediaType, lookupId)

    companion object {
        private const val TAG = "StreamResolver"
        private const val RESOLVE_TTL_MS = 5 * 60 * 1000L
    }
}