package com.crispy.tv.addons.streams

import android.util.Log
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.lookup.StreamLookupTarget
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared, id-driven stream resolution layer.
 *
 * Wraps a single [AddonStreamsService] instance and caches completed results keyed by
 * [StreamLookupTarget] so that Details and the Player never hit the addons twice for the
 * same title. Both surfaces obtain the same process-wide instance via [StreamResolverProvider].
 *
 * The caching is the whole reason this is a class rather than the [StreamResolver]
 * interface: it is the only behaviour it adds, and only [cachedStreams] can observe it.
 * It stays in `androidMain` because [AddonStreamsService] is there — it is built from a
 * `Context` and an `okhttp3` client — so the port is the half that could travel.
 */
class CachingStreamResolver(
    private val addonStreamsService: AddonStreamsService,
) : StreamResolver {
    private data class CacheEntry(
        val results: List<ProviderStreamsResult>,
        val expiresAtEpochMs: Long,
    )

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    private fun cacheKey(target: StreamLookupTarget): String =
        "${target.mediaType.name.lowercase(Locale.US)}:${target.lookupId.trim()}"

    override suspend fun resolve(
        target: StreamLookupTarget,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
        onProviderResult: ((ProviderStreamsResult) -> Unit)?,
    ): List<ProviderStreamsResult> {
        Log.d(TAG, "resolve() start mediaType=${target.mediaType} lookupId='${target.lookupId}'")
        val results =
            try {
                addonStreamsService.loadStreams(
                    mediaType = target.mediaType,
                    lookupId = target.lookupId,
                    onProvidersResolved = onProvidersResolved,
                    onProviderResult = onProviderResult,
                )
            } catch (e: Throwable) {
                Log.e(TAG, "resolve() failed mediaType=${target.mediaType} lookupId='${target.lookupId}'", e)
                throw e
            }
        Log.d(TAG, "resolve() done mediaType=${target.mediaType} lookupId='${target.lookupId}' count=${results.size}")
        cacheLock.withLock {
            cache[cacheKey(target)] =
                CacheEntry(results, System.currentTimeMillis() + RESOLVE_TTL_MS)
        }
        return results
    }

    override suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>? {
        cacheLock.withLock {
            val entry = cache[cacheKey(target)] ?: return null
            if (System.currentTimeMillis() > entry.expiresAtEpochMs) {
                cache.remove(cacheKey(target))
                return null
            }
            return entry.results
        }
    }

    override suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        Log.d(TAG, "loadProviderStreams() start mediaType=$mediaType lookupId='$lookupId' providerId='$providerId'")
        return try {
            addonStreamsService
                .loadProviderStreams(
                    mediaType = mediaType,
                    lookupId = lookupId,
                    providerId = providerId,
                ).also {
                    Log.d(TAG, "loadProviderStreams() done providerId='$providerId' found=${it != null}")
                }
        } catch (e: Throwable) {
            Log.e(TAG, "loadProviderStreams() failed providerId='$providerId'", e)
            throw e
        }
    }

    override suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle> = addonStreamsService.fetchAddonSubtitles(mediaType, lookupId)

    companion object {
        private const val TAG = "StreamResolver"
        private const val RESOLVE_TTL_MS = 5 * 60 * 1000L
    }
}
