package com.crispy.tv.playerui

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.streams.AddonStream

internal fun AddonStream.toPlaybackSource(): PlaybackSource? {
    val playbackUrl = playbackUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val externalSubtitles =
        subtitles.mapNotNull { subtitle ->
            subtitle.url.trim().takeIf { it.isNotBlank() }?.let { url ->
                PlaybackExternalSubtitle(url = url, language = subtitle.lang, name = subtitle.name)
            }
        }
    return PlaybackSource(
        url = playbackUrl,
        headers = requestHeaders,
        externalSubtitles = externalSubtitles,
    )
}

internal fun buildPlaybackRawId(identity: PlaybackIdentity?, snapshot: PlayerLaunchSnapshot?): String? {
    snapshot?.contentId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    identity?.itemId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return null
}
