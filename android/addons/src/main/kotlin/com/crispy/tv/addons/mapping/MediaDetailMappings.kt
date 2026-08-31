package com.crispy.tv.addons.mapping

import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.backend.CrispyBackendClient

fun CrispyBackendClient.MetadataTitleDetailResponse.toMediaDetails(): MediaDetails {
    val itemDetails = item.toMediaDetails()
    val episodeVideos = listOfNotNull(nextEpisode?.toMediaVideo())
    val mergedVideos = (episodeVideos + videos.mapNotNull { it.toMediaVideo() }).distinctBy { it.id }
    return itemDetails.copy(
        cast = cast.map { member ->
            if (!member.role.isNullOrBlank()) "${member.name} as ${member.role}" else member.name
        },
        directors = directors.map { it.name },
        creators = creators.map { it.name },
        videos = mergedVideos,
    )
}

fun CrispyBackendClient.MetadataTitleExtrasResponse.seasonNumbers(): List<Int> {
    val seasonNumbers = seasons.mapNotNull { it.parent?.seasonNumber }.filter { it > 0 }.distinct().sorted()
    return seasonNumbers
}

fun CrispyBackendClient.ClientMediaCard.toMediaDetails(): MediaDetails {
    return MediaDetails(
        id = itemId,
        itemId = itemId,
        imdbId = providerIds?.imdb,
        itemType = normalizedCatalogMediaType(),
        title = title.trim().takeIf { it.isNotBlank() } ?: itemId,
        artworkUrl = images.artwork.medium,
        logoUrl = images.logo.medium,
        description = overview,
        genres = genres,
        year = year?.toString() ?: releaseDate?.take(4),
        runtime = runtimeSeconds?.takeIf { it > 0 }?.let { "${it / 60} min" },
        certification = maturityRating,
        rating = formatRating(rating),
        cast = emptyList(),
        directors = emptyList(),
        creators = emptyList(),
        videos = emptyList(),
        seasonNumber = parent?.seasonNumber,
        episodeNumber = parent?.episodeNumber,
        addonId = "backend",
        absoluteEpisodeNumber = null,
    )
}

fun CrispyBackendClient.ClientMediaCard.toMediaVideo(): MediaVideo? {
    val canonicalId = itemId.trim().takeIf { it.isNotBlank() } ?: return null
    val season = parent?.seasonNumber
    val episode = parent?.episodeNumber
    val titleText =
        title.trim().takeIf { it.isNotBlank() }
            ?: when {
                episode != null -> "Episode $episode"
                else -> canonicalId
            }
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = season,
        episode = episode,
        released = releaseDate,
        overview = overview,
        thumbnailUrl = images.still.medium ?: images.artwork.medium,
        lookupId = buildAddonEpisodeLookupId(providerIds?.imdb, season, episode) ?: itemId,
        absoluteEpisodeNumber = null,
    )
}

fun CrispyBackendClient.MetadataVideoView.toMediaVideo(): MediaVideo? {
    val canonicalId = id.trim().ifBlank { key.trim() }.ifBlank { return null }
    val titleText = name?.trim()?.takeIf { it.isNotBlank() } ?: type?.trim()?.takeIf { it.isNotBlank() } ?: canonicalId
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = null,
        episode = null,
        released = publishedAt,
        overview = type,
        thumbnailUrl = thumbnailUrl,
        lookupId = url,
    )
}

fun CrispyBackendClient.ClientMediaCard.normalizedCatalogMediaType(): String {
    return when {
        mediaType.equals("anime", ignoreCase = true) -> "anime"
        mediaType.equals("episode", ignoreCase = true) -> "episode"
        mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true) -> "show"
        else -> "movie"
    }
}
