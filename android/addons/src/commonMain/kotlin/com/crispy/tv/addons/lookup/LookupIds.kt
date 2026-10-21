package com.crispy.tv.addons.lookup

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType

fun buildAddonEpisodeLookupId(imdbId: String?, season: Int?, episode: Int?): String? {
    val normalizedImdbId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
    if (season == null || season <= 0 || episode == null || episode <= 0) return normalizedImdbId
    return "$normalizedImdbId:$season:$episode"
}

/**
 * An episode lookup id, taken apart again.
 *
 * **The lookup id is the only place an episode's season and number survive** between
 * the detail screen, the stream picker and the player, so a consumer that was handed
 * only a lookup id still has to be able to read the episode back out of it. The
 * watch-history key is `<title id>:<season>:<episode>`, and the title id on its own
 * cannot say which episode of a series was watched.
 */
data class AddonEpisodeLookup(
    val imdbId: String,
    val season: Int,
    val episode: Int,
)

/**
 * The episode a lookup id names, or null when it names no episode.
 *
 * Only the shape [buildAddonEpisodeLookupId] writes is read back: an imdb id and two
 * positive numbers, separated by colons. A lookup id that names a title rather than
 * an episode -- `tt123` -- is not an episode lookup, and saying so plainly is better
 * than guessing a season and an episode that were never there.
 */
fun parseAddonEpisodeLookupId(lookupId: String?): AddonEpisodeLookup? {
    val parts = lookupId?.trim()?.split(':') ?: return null
    if (parts.size != 3) return null
    val imdbId = parts[0].trim().takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
    val season = parts[1].trim().toIntOrNull() ?: return null
    val episode = parts[2].trim().toIntOrNull() ?: return null
    if (season <= 0 || episode <= 0) return null
    return AddonEpisodeLookup(imdbId = imdbId, season = season, episode = episode)
}

fun MediaDetails.toAddonLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: itemId?.trim()?.ifBlank { null } ?: id.trim().ifBlank { null }
}

fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
  return when (this?.lowercase()) {
    "movie" -> MetadataLabMediaType.MOVIE
    "series", "show", "tv" -> MetadataLabMediaType.SERIES
    "anime" -> MetadataLabMediaType.ANIME
    else -> null
  }
}
