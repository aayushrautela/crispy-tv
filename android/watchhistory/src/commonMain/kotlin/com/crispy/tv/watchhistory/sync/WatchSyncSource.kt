package com.crispy.tv.watchhistory.sync

import com.crispy.tv.domain.watch.WatchSyncEffect

/**
 * The watch_sync channel, as its callers see it.
 *
 * The implementation stays in `androidMain` permanently: it owns an OkHttp
 * socket, an okio stream reader and an `org.json` event parse, none of which
 * exists on a KMP target. The policy -- when to open, when to close, what a
 * `watch_changed` event means -- already lives in the pure `reduceWatchSync`
 * reducer in `:core-domain`; this is only the socket lifecycle.
 *
 * Named after the class it replaces, so the three existing consumers --
 * `HomeViewModel`, `TvHomeViewModel` and `LibraryScreen` -- needed no import
 * edit when the seam went in. Only the construction sites name
 * `OkHttpWatchSyncSource`.
 */
interface WatchSyncSource {
    fun onSurfaceVisible()

    fun onSurfaceHidden()

    fun close()
}
