package com.crispy.tv.addons.model

data class MediaDetails(
    val id: String,
    val itemId: String? = null,
    val imdbId: String?,
    val itemType: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val year: String?,
    val runtime: String?,
    val certification: String?,
    val rating: String?,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val videos: List<MediaVideo> = emptyList(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val addonId: String?,
    val parentMediaType: String? = null,
    val absoluteEpisodeNumber: Int? = null,
)

data class MediaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?,
    val lookupId: String? = null,
    val absoluteEpisodeNumber: Int? = null,
)
