package com.crispy.tv.testing

import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderDescriptor
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.player.MetadataLabMediaType

/**
 * A [StreamResolver] that answers from values a test sets and records what it was asked.
 *
 * The port has four members and only one of them is called by the code under test in
 * these suites, but it implements all four rather than throwing from three. The rule the
 * repo already follows is that an exhaustive double belongs to the module that owns the
 * interface; `:app` now consumes `StreamResolver`, so `:app` keeps one. Throwing would
 * have been the smaller file and the one that rots silently when a member is added.
 */
class FakeStreamResolver(
    var subtitles: List<AddonSubtitle> = emptyList(),
) : StreamResolver {
    val fetchCalls = mutableListOf<Pair<MetadataLabMediaType, String>>()
    val resolveCalls = mutableListOf<StreamLookupTarget>()
    val loadProviderStreamsCalls = mutableListOf<Triple<MetadataLabMediaType, String, String>>()
    val cachedStreamsCalls = mutableListOf<StreamLookupTarget>()

    var fetchFailure: Throwable? = null
    var cachedStreamsAnswer: List<ProviderStreamsResult>? = null

    /** Set when a caller passes a non-null `onProvidersResolved`, so a test can assert it was forwarded. */
    var providersResolvedCallbackSeen = false

    override suspend fun resolve(
        target: StreamLookupTarget,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
        onProviderResult: ((ProviderStreamsResult) -> Unit)?,
    ): List<ProviderStreamsResult> {
        resolveCalls += target
        if (onProvidersResolved != null) {
            providersResolvedCallbackSeen = true
            onProvidersResolved(emptyList())
        }
        fetchFailure?.let { throw it }
        onProviderResult?.invoke(subtitles.toResult())
        return listOf(subtitles.toResult())
    }

    override suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>? {
        cachedStreamsCalls += target
        return cachedStreamsAnswer
    }

    override suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        loadProviderStreamsCalls += Triple(mediaType, lookupId, providerId)
        return null
    }

    override suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle> {
        fetchCalls += mediaType to lookupId
        fetchFailure?.let { throw it }
        return subtitles
    }

    private fun List<AddonSubtitle>.toResult(): ProviderStreamsResult =
        ProviderStreamsResult(providerId = "fake", providerName = "fake", streams = emptyList())
}

/** An [AppLogger] that keeps what it was told, so a test can assert on the warnings. */
class RecordingAppLogger : com.crispy.tv.platform.AppLogger {
    val debugs = mutableListOf<Pair<String, String>>()
    val warns = mutableListOf<Triple<String, String, Throwable?>>()

    override fun debug(tag: String, message: String) {
        debugs += tag to message
    }

    override fun info(tag: String, message: String) {
        debugs += tag to message
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        warns += Triple(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        warns += Triple(tag, message, throwable)
    }
}
