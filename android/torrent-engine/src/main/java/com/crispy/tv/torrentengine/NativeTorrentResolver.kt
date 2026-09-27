package com.crispy.tv.torrentengine

import android.content.Context
import com.crispy.tv.player.TorrentResolver

/**
 * Sideload-build [TorrentResolver] backed by the bundled torrent engine.
 *
 * Only reachable from builds that depend on :android:torrent-engine, which is
 * the mechanism that keeps the engine out of store builds.
 */
class NativeTorrentResolver(context: Context) : TorrentResolver {
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
