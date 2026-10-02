package com.crispy.tv.addons.streams

import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.player.MetadataLabMediaType

/**
 * Resolves a title to the streams a set of addons can offer for it, and fetches
 * the subtitles those addons carry.
 *
 * A type-level port, exactly like `BackendApi` and `AccountApi` beside it: it is
 * an interface over the loader classes rather than an abstraction of the transport.
 *
 * **This KDoc used to justify that choice with a false premise.** It said
 * `AddonStreamsService` *"`is a wrapper around CrispyHttpClient`, which leaks
 * `okhttp3` types in its own signature, so an interface that abstracted OkHttp would
 * not be implementable here."* `CrispyHttpClient` is `commonMain`'s own `interface`
 * over `data class CrispyHttpResponse(val code: Int, val body: String)` and
 * `data class HttpRequest(..., val url: String, val headers: Map<String, String>, ...)`;
 * it leaks nothing, and `git grep -l 'import okhttp3' | grep '/src/commonMain/'` returns
 * zero hits repo-wide. **The conclusion happened to be right and the reason was
 * invented** — and the same invented reason was load-bearing in two sibling KDocs, so
 * one premise had to be corrected in three files.
 *
 * It exists so `SelectorCoordinator` and `SubtitleRepository` can name the
 * capability without naming the caching implementation, which was a final class pinned
 * to `androidMain` by `AddonStreamsService` and is now in `commonMain` behind
 * [AddonStreamsLoader]. The implementation is
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
