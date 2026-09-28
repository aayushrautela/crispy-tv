package com.crispy.tv.player

/**
 * Resolves a magnet link to a playable stream URL.
 *
 * Lives in :player rather than :app so the torrent engine module can implement it
 * without depending on the app. Store builds have no implementation on the
 * classpath; the app installs an unavailable implementation instead.
 */
interface TorrentResolver {
    suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String
    fun stopAndClear()
    fun close()
}
