package com.crispy.tv.streams

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.playback.StreamLookupTarget
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared, id-driven stream resolution layer.
 *
 * Wraps a single [AddonStreamsService] instance and caches completed results keyed by
 * [StreamLookupTarget] so that Details and the Player never hit the addons twice for the
 * same title. Both surfaces obtain the same process-wide instance via [StreamResolverProvider].
 */
class StreamResolver(
    private val addonStreamsService: AddonStreamsService,
) {
    private data class CacheEntry(
        val results: List<ProviderStreamsResult>,
        val expiresAtEpochMs: Long,
    )

    private val cacheLock = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    private fun cacheKey(target: StreamLookupTarget): String =
        "${target.mediaType.name.lowercase(Locale.US)}:${target.lookupId.trim()}"

    suspend fun resolve(
        target: StreamLookupTarget,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)? = null,
        onProviderResult: ((ProviderStreamsResult) -> Unit)? = null,
    ): List<ProviderStreamsResult> {
        val results =
            addonStreamsService.loadStreams(
                mediaType = target.mediaType,
                lookupId = target.lookupId,
                onProvidersResolved = onProvidersResolved,
                onProviderResult = onProviderResult,
            )
        cacheLock.withLock {
            cache[cacheKey(target)] =
                CacheEntry(results, System.currentTimeMillis() + RESOLVE_TTL_MS)
        }
        return results
    }

    suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>? {
        cacheLock.withLock {
            val entry = cache[cacheKey(target)] ?: return null
            if (System.currentTimeMillis() > entry.expiresAtEpochMs) {
                cache.remove(cacheKey(target))
                return null
            }
            return entry.results
        }
    }

    suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? =
        addonStreamsService.loadProviderStreams(
            mediaType = mediaType,
            lookupId = lookupId,
            providerId = providerId,
        )

    suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle> = addonStreamsService.fetchAddonSubtitles(mediaType, lookupId)

    companion object {
        private const val RESOLVE_TTL_MS = 5 * 60 * 1000L
    }
}

object StreamResolverProvider {
    @Volatile
    private var instance: StreamResolver? = null

    fun get(context: Context): StreamResolver {
        val existing = instance
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            val synchronizedExisting = instance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                create(context.applicationContext).also { created -> instance = created }
            }
        }
    }

    private fun create(appContext: Context): StreamResolver {
        val addonStreamsService =
            AddonStreamsService(
                context = appContext,
                addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                httpClient = AppHttp.client(appContext),
            )
        return StreamResolver(addonStreamsService)
    }
}
