package com.crispy.tv.playerui

import com.crispy.tv.addons.playback.resolvePlaybackSource
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.TorrentResolver
import com.crispy.tv.addons.streams.AddonStream

/**
 * The phone's own answer to "what should the engine play for this stream?".
 *
 * The rule is no longer written here: it is
 * `com.crispy.tv.addons.playback.resolvePlaybackSource`, because the TV app needs the
 * same answer and `:tv` cannot depend on `:app`. The phone keeps this function
 * because its callers hold a non-null `TorrentResolver` -- the phone's store build
 * installs one that fails fast -- while the TV answers the same question with a
 * resolver or without one, depending on what its build ships.
 */
internal suspend fun AddonStream.toPlaybackSource(
    torrentResolver: TorrentResolver,
    sessionId: String?,
): PlaybackSource? = resolvePlaybackSource(
    torrentResolver = torrentResolver,
    sessionId = sessionId,
)

internal fun buildPlaybackRawId(identity: PlaybackIdentity?): String? {
    return identity?.itemId?.trim()?.takeIf { it.isNotBlank() }
}
