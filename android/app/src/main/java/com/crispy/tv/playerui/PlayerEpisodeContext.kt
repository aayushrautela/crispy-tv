package com.crispy.tv.playerui

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo

data class PlayerEpisodeContext(
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val overview: String?,
) {
    val seasonEpisodeLabel: String
        get() = if (season != null && episode != null) "S${season}E${episode}" else ""
}

fun MediaDetails.toPlayerEpisodeContext(): PlayerEpisodeContext? {
    if (seasonNumber == null || episodeNumber == null) return null
    return PlayerEpisodeContext(
        season = seasonNumber,
        episode = episodeNumber,
        title = title.trim().takeIf { it.isNotBlank() },
        overview = description?.trim()?.takeIf { it.isNotBlank() },
    )
}

fun MediaVideo.toPlayerEpisodeContext(): PlayerEpisodeContext? {
    if (season == null || episode == null) return null
    return PlayerEpisodeContext(
        season = season,
        episode = episode,
        title = title.trim().takeIf { it.isNotBlank() },
        overview = overview?.trim()?.takeIf { it.isNotBlank() },
    )
}
