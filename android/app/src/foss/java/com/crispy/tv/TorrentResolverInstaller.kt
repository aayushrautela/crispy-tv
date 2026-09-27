package com.crispy.tv

/**
 * Sideload build: install the real torrent resolver.
 *
 * The engine lives in :android:torrent-engine, which only this flavor depends
 * on. The play variant of this file is the one that keeps the engine out of
 * store builds.
 */
internal fun installTorrentResolver(dependencies: PlaybackDependencies) {
    dependencies.torrentResolverFactory = { context ->
        com.crispy.tv.torrentengine.NativeTorrentResolver(context)
    }
}
