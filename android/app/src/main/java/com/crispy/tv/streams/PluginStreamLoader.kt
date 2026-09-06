package com.crispy.tv.streams

import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.player.MetadataLabMediaType
import kotlinx.coroutines.flow.Flow

data class PluginStreamRequest(
    val mediaType: MetadataLabMediaType,
    val lookupId: String,
    val tmdbId: Int? = null,
    val season: Int?,
    val episode: Int?,
)

fun interface PluginStreamLoader {
    /** Emits one result per plugin provider as its scraper finishes. */
    fun stream(request: PluginStreamRequest): Flow<ProviderStreamsResult>
}
