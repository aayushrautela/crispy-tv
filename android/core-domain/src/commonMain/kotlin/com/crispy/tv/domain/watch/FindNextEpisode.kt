package com.crispy.tv.domain.watch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

fun findNextEpisode(
    currentSeason: Int,
    currentEpisode: Int,
    episodes: List<EpisodeInfo>,
    watchedSet: Set<String>? = null,
    showId: String? = null,
    nowMs: Long? = null,
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

private fun isEpisodeReleased(released: String?, nowMs: Long?): Boolean {
    if (released.isNullOrBlank()) return false
    val trimmed = released.trim()
    val nowInstant = nowMs?.let(Instant::ofEpochMilli) ?: Instant.now()
    return try {
        val releaseInstant = Instant.parse(trimmed)
        !releaseInstant.isAfter(nowInstant)
    } catch (_: Exception) {
        try {
            val date = LocalDate.parse(trimmed.take(10))
            val nowDate = nowInstant.atZone(ZoneOffset.UTC).toLocalDate()
            !date.isAfter(nowDate)
        } catch (_: Exception) {
            // Return false on parse errors
            false
        }
    }
}
