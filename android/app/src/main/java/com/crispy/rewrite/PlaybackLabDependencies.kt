package com.crispy.rewrite

import android.content.Context
import com.crispy.rewrite.metadata.RemoteMetadataLabDataSource
import com.crispy.rewrite.nativeengine.playback.NativePlaybackController
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.nativeengine.playback.PlaybackController
import com.crispy.rewrite.nativeengine.torrent.TorrentEngineClient
import com.crispy.rewrite.player.CoreDomainMetadataLabResolver
import com.crispy.rewrite.player.MetadataLabResolver

interface TorrentResolver {
    suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String
    fun stopAndClear()
    fun close()
}

private class NativeTorrentResolver(context: Context) : TorrentResolver {
    private val client = TorrentEngineClient(context)

    override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
        return client.startTorrentAndResolveStreamUrl(magnetLink = magnetLink, sessionId = sessionId)
    }

    override fun stopAndClear() {
        client.stopAllIfConnected(clearStorage = true)
    }

    override fun close() {
        client.close()
    }
}

private fun newMetadataResolver(): MetadataLabResolver {
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
            tmdbApiKey = BuildConfig.TMDB_API_KEY
        )
    )
}

object PlaybackLabDependencies {
    @Volatile
    var playbackControllerFactory: (Context, (NativePlaybackEvent) -> Unit) -> PlaybackController =
        { context, callback ->
            NativePlaybackController(context, callback)
        }

    @Volatile
    var torrentResolverFactory: (Context) -> TorrentResolver = { context ->
        NativeTorrentResolver(context)
    }

    @Volatile
    var metadataResolverFactory: () -> MetadataLabResolver = {
        newMetadataResolver()
    }

    fun reset() {
        playbackControllerFactory = { context, callback ->
            NativePlaybackController(context, callback)
        }
        torrentResolverFactory = { context -> NativeTorrentResolver(context) }
        metadataResolverFactory = {
            newMetadataResolver()
        }
    }
}
