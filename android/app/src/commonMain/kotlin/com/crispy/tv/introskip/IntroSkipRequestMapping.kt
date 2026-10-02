package com.crispy.tv.introskip

import com.crispy.tv.player.PlaybackIdentity

/**
 * The [IntroSkipRequest] that would ask about this episode, or `null` when asking
 * is pointless.
 *
 * This is the seam between playback identity and the intro-skip service, and it is
 * where the two disagree about what an episode is. [PlaybackIdentity] numbers an
 * episode only for a series -- a movie carries `null` season and episode -- while
 * [IntroSkipRequest] makes both non-null and lets the service's own `normalize`
 * reject a non-positive number. A `null` here therefore means "do not spend a
 * request", which is the decision this function exists to make: without it every
 * movie playback would build a request out of sentinels and discover the same
 * thing from inside the service, one network call later.
 *
 * `imdbId` is the only id [PlaybackIdentity] carries. `malId` and `kitsuId` are
 * left null so the service resolves them through ARM and Kitsu, which is the path
 * AniSkip needs anyway since it is keyed by MyAnimeList id.
 *
 * Deliberately **not** re-checking the `tt\d+` shape that `normalize` applies to
 * the id. That is one rule in one place, and duplicating it here is how two
 * copies come to disagree about which ids are valid.
 *
 * `internal` because `private` is a property of the file, so a `commonTest` in
 * this module could not name it.
 */
internal fun PlaybackIdentity.toIntroSkipRequestOrNull(): IntroSkipRequest? {
    val episodeSeason = season?.takeIf { it > 0 } ?: return null
    val episodeNumber = episode?.takeIf { it > 0 } ?: return null
    val imdb = imdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return IntroSkipRequest(
        imdbId = imdb,
        season = episodeSeason,
        episode = episodeNumber,
    )
}
