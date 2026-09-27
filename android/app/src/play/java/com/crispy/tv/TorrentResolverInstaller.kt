package com.crispy.tv

/**
 * Store build: leave PlaybackDependencies on its UnavailableTorrentResolver
 * default. :android:torrent-engine is not on this flavor's classpath, so the
 * engine and its foreground service are absent from the APK.
 */
internal fun installTorrentResolver(dependencies: PlaybackDependencies) {
    // Nothing to install; the default resolver fails fast on use.
}
