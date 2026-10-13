package com.crispy.tv.playerui

import com.crispy.tv.nativeengine.playback.NativeTrack

/**
 * The name line of an audio-track row: the engine's own title when the track
 * carries one, and the language name otherwise.
 *
 * The language name is the common case rather than a fallback -- most containers
 * label a track by language only -- which is why this returns it and not "Track
 * N", the web panel's last resort.
 */
internal fun audioTrackName(
    track: NativeTrack,
    languageName: String,
): String = track.title?.takeIf { it.isNotBlank() } ?: languageName

/**
 * The second line of an audio-track row, or `null` when there is nothing worth one.
 *
 * The language joins the line only when a real title already names the track,
 * repeating the existing behaviour of showing "English" under "Commentary"; a row
 * whose title *is* the language would repeat itself otherwise. It is also dropped
 * when the tag is blank or `und`, because the line above already reads "Unknown" or
 * "Undetermined" and a second line saying so adds nothing.
 */
internal fun audioTrackSubtitle(
    track: NativeTrack,
    languageName: String,
): String? {
    val hasTitle = !track.title.isNullOrBlank()
    return buildList {
            if (hasTitle && normalizeLang(track.language) != "und") add(languageName)
            audioCodecAndChannels(track.codec, track.channelCount)?.let { add(it) }
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" • ")
}

/**
 * The codec and channel summary of one track, e.g. `EAC3 • 6ch`, or `null` when the
 * engine reported neither.
 *
 * The two engines spell a codec three ways -- mpv's bare `eac3`, its older
 * `ec-3` spelling, and Exo's `audio/eac3` MIME type -- so the tag is cut at the
 * last `/` and uppercased rather than shown raw, which keeps one row from reading
 * `audio/eac3` next to another reading `EAC3`. Uppercasing is locale-invariant and
 * every tag is ASCII, so there is no locale-dependent case mapping here.
 *
 * **A count, never a layout.** Neither engine reports speaker positions, so this is
 * `6ch` and not `5.1` -- the same answer the web panel gives.
 */
internal fun audioCodecAndChannels(
    codec: String?,
    channelCount: Int?,
): String? {
    val codecLabel = codec?.trim()?.substringAfter('/')?.takeIf { it.isNotEmpty() }?.uppercase()
    val channels = channelCount?.takeIf { it > 0 }?.let { "${it}ch" }
    return listOfNotNull(codecLabel, channels).takeIf { it.isNotEmpty() }?.joinToString(" • ")
}
