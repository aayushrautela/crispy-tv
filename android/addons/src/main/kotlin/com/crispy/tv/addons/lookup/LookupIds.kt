package com.crispy.tv.addons.lookup

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale

fun buildAddonEpisodeLookupId(imdbId: String?, season: Int?, episode: Int?): String? {
    val normalizedImdbId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return null
    if (season == null || season <= 0 || episode == null || episode <= 0) return normalizedImdbId
    return "$normalizedImdbId:$season:$episode"
}

fun MediaDetails.toAddonLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: itemId?.trim()?.ifBlank { null } ?: id.trim().ifBlank { null }
}

fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
  return when (this?.lowercase(Locale.US)) {
    "movie" -> MetadataLabMediaType.MOVIE
    "series", "show", "tv" -> MetadataLabMediaType.SERIES
    "anime" -> MetadataLabMediaType.ANIME
    else -> null
  }
}
