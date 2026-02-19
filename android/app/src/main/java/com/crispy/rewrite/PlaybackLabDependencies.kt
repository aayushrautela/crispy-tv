package com.crispy.rewrite

import android.content.Context
import com.crispy.rewrite.nativeengine.playback.NativePlaybackController
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.nativeengine.playback.PlaybackController
import com.crispy.rewrite.nativeengine.torrent.TorrentEngineClient

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

    fun reset() {
        playbackControllerFactory = { context, callback ->
            NativePlaybackController(context, callback)
        }
        torrentResolverFactory = { context -> NativeTorrentResolver(context) }
    }
}
