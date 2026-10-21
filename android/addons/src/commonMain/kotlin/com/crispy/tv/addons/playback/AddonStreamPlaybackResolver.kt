package com.crispy.tv.addons.playback

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.TorrentResolver

/**
 * The stream to play, for a stream a provider offered.
 *
 * ## Why this is here and not in the app
 *
 * A provider's stream is either something the player can open -- a URL, with the
 * headers and the sidecar subtitles that go with it -- or a magnet link that has to
 * be turned into a URL first. **That second step needs a torrent engine**, and an
 * engine is a capability an app either has or does not have: the phone's sideload
 * build has one, the phone's store build does not, and the TV app's answer is the
 * same question.
 *
 * So the rule lives here, in a module both apps can reach, and the *capability* is
 * passed in as a nullable resolver. A null resolver is not a stub: it is the honest
 * answer from a build that ships no torrent engine, and this function then refuses
 * the magnet link rather than pretending it can play it.
 */
suspend fun AddonStream.resolvePlaybackSource(
    torrentResolver: TorrentResolver?,
    sessionId: String?,
): PlaybackSource? {
    // The direct branch first, and always: a stream that carries a plain URL is
    // played directly even when it also carries an info hash, because the engine is
    // only needed to turn a magnet link into something the player can open.
    toDirectPlaybackSource()?.let { directSource ->
        return directSource
    }

    val resolver = torrentResolver ?: return null
    val infoHash = p2pInfoHash ?: return null
    val rawLink = url ?: externalUrl
    val torrentLink = if (rawLink != null && rawLink.startsWith("magnet:", ignoreCase = true)) {
        rawLink
    } else {
        "magnet:?xt=urn:btih:$infoHash"
    }

    val localUrl = resolver.resolveStreamUrl(torrentLink, sessionId ?: "")
    return PlaybackSource(
        url = localUrl,
        headers = emptyMap(),
        externalSubtitles = subtitles.toPlaybackExternalSubtitles(),
    )
}
