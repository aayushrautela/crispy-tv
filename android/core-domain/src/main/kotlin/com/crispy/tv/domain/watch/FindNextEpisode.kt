package com.crispy.tv.domain.watch

import java.time.Instant

/**
 * Episode metadata from an addon (e.g. Cinemeta).
 * Maps to the `videos` array entries in a Stremio meta response.
 */
data class AddonEpisodeInfo(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val released: String? = null,
)

/**
 * Result of [findNextEpisode] — the next episode the user should watch.
 */
data class NextEpisodeResult(
    val season: Int,
    val episode: Int,
    val title: String? = null,
)

/**
 * 1:1 port of Nuvio's `findNextEpisode`.
 *
 * Given the current season/episode and a full episode list from an addon,
 * returns the next *released* episode that isn't in [watchedSet].
 *
 * Sorting: ascending by season, then by episode (matching Nuvio).
 *
 * [watchedSet] keys must be in the format `{imdbId}:{season}:{episode}`
 * (the imdbId should include the `tt` prefix).
 *
 * [showId] is the IMDb ID of the show (e.g. `tt1234567`), used to
 * build lookup keys into [watchedSet].
 */
fun findNextEpisode(
    currentSeason: Int,
    currentEpisode: Int,
    episodes: List<AddonEpisodeInfo>,
    watchedSet: Set<String>? = null,
    showId: String? = null,
): NextEpisodeResult? {
    if (episodes.isEmpty()) return null

    val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

    for (ep in sorted) {
        // Skip episodes at or before the current position
        if (ep.season < currentSeason) continue
        if (ep.season == currentSeason && ep.episode <= currentEpisode) continue

        // Skip already-watched episodes
        if (watchedSet != null && showId != null) {
            val cleanShowId = if (showId.startsWith("tt")) showId else "tt$showId"
            val key1 = "$cleanShowId:${ep.season}:${ep.episode}"
            val key2 = "$showId:${ep.season}:${ep.episode}"
            if (watchedSet.contains(key1) || watchedSet.contains(key2)) continue
        }

        // Only return released episodes
        if (!isEpisodeReleased(ep.released)) continue

        return NextEpisodeResult(
            season = ep.season,
            episode = ep.episode,
            title = ep.title,
        )
    }

    return null
}

/**
 * 1:1 port of Nuvio's `isEpisodeReleased`.
 *
 * Returns `true` if [released] is a valid date string that is <= now.
 * Returns `false` if null, blank, or unparseable.
 */
private fun isEpisodeReleased(released: String?): Boolean {
    if (released.isNullOrBlank()) return false
    return try {
        val releaseInstant = Instant.parse(released)
        !releaseInstant.isAfter(Instant.now())
    } catch (_: Exception) {
        // Nuvio also returns false on parse errors
        false
    }
}
