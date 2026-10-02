package com.crispy.tv.addons.streams

import com.crispy.tv.player.MetadataLabMediaType

/**
 * The three stream-loading members of [AddonStreamsService], as a capability.
 *
 * It exists for one caller and one reason: [CachingStreamResolver] wraps a single
 * loader instance, so a test of the cache needs to hand it a loader that answers
 * without a manifest registry and an HTTP client. `AddonStreamsService` is a
 * final class whose constructor takes both, so `open` would have bought nothing —
 * a subclass still has to build a real [com.crispy.tv.addons.registry.MetadataAddonRegistry]
 * and a real `CrispyHttpClient` before it can override anything, which is the
 * untestable collaborator problem one level down. **A type is the fix here, not a
 * keyword.**
 *
 * The members are the **union of every caller's**, measured rather than assumed:
 * `CachingStreamResolver` is the only value in the repository that calls a member
 * on an `AddonStreamsService`, and it calls exactly these three — `loadStreams`
 * (`:44`), `loadProviderStreams` (`:81`) and `fetchAddonSubtitles` (`:97`). There
 * are no extension functions on `AddonStreamsService` anywhere
 * (`git grep 'fun AddonStreamsService\.'` → zero hits), which is what makes the
 * receiver cheap to abstract: unlike `CrispyBackendClient`, nothing pins the class
 * through 39 extension declarations.
 *
 * The names and signatures are [AddonStreamsService]'s verbatim, return types
 * included, because this is a port and not a redesign — narrowing a return type to
 * the tidier-looking one is a silent behaviour change on a caller's hot path.
 *
 * Note the direction: [StreamResolver] is the capability *callers* see, and it
 * adds caching. This is the raw loader underneath it, so the two overlap on three
 * member names on purpose — that is what makes [CachingStreamResolver] a decorator
 * rather than an adapter.
 */
interface AddonStreamsLoader {
    suspend fun loadStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        preferredProviderId: String? = null,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)? = null,
        onProviderResult: ((ProviderStreamsResult) -> Unit)? = null,
    ): List<ProviderStreamsResult>

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