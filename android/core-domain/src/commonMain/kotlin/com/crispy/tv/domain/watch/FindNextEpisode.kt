package com.crispy.tv.domain.watch

/**
 * Episode metadata for a show.
 *
 * This is a lightweight descriptor used by watch-history flows to
 * compute "up next" episodes.
 */
data class EpisodeInfo(
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
 * Returns the next episode worth watching, or null when none is released yet.
 *
 * [nowMs] is required rather than defaulted: a wall-clock fallback would make
 * the result depend on when the caller happened to run, which domain rules must
 * never do. Every fixture and every caller supplies it.
 */
fun findNextEpisode(
    currentSeason: Int,
    currentEpisode: Int,
    episodes: List<EpisodeInfo>,
    watchedSet: Set<String>? = null,
    showId: String? = null,
    nowMs: Long,
): NextEpisodeResult? {
    if (episodes.isEmpty()) return null

    val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

    for (ep in sorted) {
        if (ep.season < currentSeason) continue
        if (ep.season == currentSeason && ep.episode <= currentEpisode) continue

        if (watchedSet != null && showId != null) {
            val cleanShowId = if (showId.startsWith("tt")) showId else "tt$showId"
            val key1 = "$cleanShowId:${ep.season}:${ep.episode}"
            val key2 = "$showId:${ep.season}:${ep.episode}"
            if (watchedSet.contains(key1) || watchedSet.contains(key2)) continue
        }

        if (!isEpisodeReleased(ep.released, nowMs)) continue

        return NextEpisodeResult(
            season = ep.season,
            episode = ep.episode,
            title = ep.title,
        )
    }

    return null
}

private fun isEpisodeReleased(released: String?, nowMs: Long): Boolean {
    if (released.isNullOrBlank()) return false
    val trimmed = released.trim()

    parseIso8601InstantToEpochMillis(trimmed)?.let { releaseMillis ->
        return releaseMillis <= nowMs
    }

    val releaseDay = parseIso8601DateToEpochDay(trimmed.take(10)) ?: return false
    return releaseDay <= utcEpochDayOf(nowMs)
}
