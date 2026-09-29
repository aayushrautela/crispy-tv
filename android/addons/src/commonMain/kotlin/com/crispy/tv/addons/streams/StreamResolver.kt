package com.crispy.tv.addons.streams

import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.player.MetadataLabMediaType

/**
 * Resolves a title to the streams a set of addons can offer for it, and fetches
 * the subtitles those addons carry.
 *
 * A type-level port, exactly like `BackendApi` and `AccountApi` beside it: it is
 * an interface over the two client classes rather than an abstraction of the
 * transport. `AddonStreamsService` is a wrapper *around* `CrispyHttpClient`, which
 * leaks `okhttp3` types in its own signature, so an interface that abstracted
 * OkHttp would not be implementable here.
 *
 * It exists so `SelectorCoordinator` and `SubtitleRepository` can name the
 * capability without naming the caching implementation, which is a final class
 * pinned to `androidMain` by `AddonStreamsService`. The implementation is
 * [CachingStreamResolver]; the only thing it adds is a five-minute cache, and
 * a caller that does not care whether a second resolve is a cache hit has no
 * business depending on that.
 */
interface StreamResolver {
    suspend fun resolve(
        target: StreamLookupTarget,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)? = null,
        onProviderResult: ((ProviderStreamsResult) -> Unit)? = null,
    ): List<ProviderStreamsResult>

    suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>?

    suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult?

    suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle>
}
