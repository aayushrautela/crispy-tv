package com.crispy.tv.addons.playback

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamSubtitle
import com.crispy.tv.nativeengine.playback.PlaybackExternalSubtitle
import com.crispy.tv.nativeengine.playback.PlaybackSource

/**
 * The one rule that turns a provider stream into something an engine can play.
 *
 * ## Why this file exists
 *
 * The rule used to live in `:app`'s `PlayerSessionSupport.kt`, as a private
 * `toPlaybackSubtitles` plus a direct branch inside `AddonStream.toPlaybackSource`.
 * That was one rule with two homes once the TV app grew a stream picker: `:app` could
 * not own it, because `:tv` is not allowed to depend on `:app` (see `plan.md`,
 * "the sharing decision"), and `:tv` is not the right owner either, because the rule
 * is about an `AddonStream` -- a type `:addons` owns -- and about a `PlaybackSource` --
 * a type `:core-domain` owns. A rule that spans two modules' types belongs to the
 * module that can see both, and `:addons` already declares `api(project(":android:core-domain"))`
 * for exactly this kind of value mapping.
 *
 * So this is an extraction, not a copy: `:app`'s `toPlaybackSource` now calls
 * [toDirectPlaybackSource] for its direct branch and keeps only the torrent branch,
 * which needs a `TorrentResolver` and therefore stays in the app that owns one.
 *
 * ## What "direct" means here
 *
 * A stream is direct when it has a URL that is neither a magnet link nor a
 * `torrent://` URL, and [AddonStream.directPlaybackUrl] already draws that line --
 * the same line the phone drew when it chose between the direct and the torrent
 * branch. **The headers and the sidecar subtitles travel with the source**, because a
 * provider that requires a `Referer` or an auth header will not serve the video
 * without them, and a sidecar subtitle that is dropped is a subtitle the user cannot
 * turn on. Both were being dropped by the TV picker before this landed.
 */
fun AddonStream.toDirectPlaybackSource(): PlaybackSource? {
    val directUrl = directPlaybackUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PlaybackSource(
        url = directUrl,
        headers = requestHeaders,
        externalSubtitles = subtitles.toPlaybackExternalSubtitles(),
    )
}

/**
 * The sidecar subtitles of a stream, as the engine's own subtitle type.
 *
 * A subtitle with a blank URL is dropped rather than carried: the engine would try to
 * fetch it and fail, and an empty sidecar list is what "no subtitles" means
 * everywhere else in the codebase.
 */
fun List<StreamSubtitle>.toPlaybackExternalSubtitles(): List<PlaybackExternalSubtitle> =
    mapNotNull { subtitle ->
        subtitle.url.trim().takeIf { it.isNotBlank() }?.let { url ->
            PlaybackExternalSubtitle(url = url, language = subtitle.lang, name = subtitle.name)
        }
    }
