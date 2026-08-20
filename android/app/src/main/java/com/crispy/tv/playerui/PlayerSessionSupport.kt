package com.crispy.tv.playerui

import com.crispy.tv.TorrentResolver
import com.crispy.tv.nativeengine.playback.PlaybackExternalSubtitle
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.streams.AddonStream

internal suspend fun AddonStream.toPlaybackSource(
    torrentResolver: TorrentResolver,
    sessionId: String?,
): PlaybackSource? {
    val directUrl = directPlaybackUrl
    if (!directUrl.isNullOrBlank()) {
        return PlaybackSource(
            url = directUrl,
            headers = requestHeaders,
            externalSubtitles = subtitles.toPlaybackSubtitles(),
        )
    }

    val infoHash = p2pInfoHash ?: return null
    val torrentLink =
        if ((url ?: externalUrl)?.isMagnetLink() == true) {
            (url ?: externalUrl)!!
        } else {
            "magnet:?xt=urn:btih:$infoHash"
        }

    val localUrl = torrentResolver.resolveStreamUrl(torrentLink, sessionId ?: "")
    return PlaybackSource(
        url = localUrl,
        headers = emptyMap(),
        externalSubtitles = subtitles.toPlaybackSubtitles(),
    )
}

private fun List<com.crispy.tv.streams.StreamSubtitle>.toPlaybackSubtitles(): List<PlaybackExternalSubtitle> =
    mapNotNull { subtitle ->
        subtitle.url.trim().takeIf { it.isNotBlank() }?.let { url ->
            PlaybackExternalSubtitle(url = url, language = subtitle.lang, name = subtitle.name)
        }
    }

internal fun buildPlaybackRawId(identity: PlaybackIdentity?): String? {
    return identity?.itemId?.trim()?.takeIf { it.isNotBlank() }
}
