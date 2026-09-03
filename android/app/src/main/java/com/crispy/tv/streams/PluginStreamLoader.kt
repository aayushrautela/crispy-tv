package com.crispy.tv.streams

import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.player.MetadataLabMediaType

data class PluginStreamRequest(
    val mediaType: MetadataLabMediaType,
    val lookupId: String,
    val title: String?,
    val year: String?,
    val season: Int?,
    val episode: Int?,
)

fun interface PluginStreamLoader {
    suspend fun load(request: PluginStreamRequest): List<ProviderStreamsResult>
}
