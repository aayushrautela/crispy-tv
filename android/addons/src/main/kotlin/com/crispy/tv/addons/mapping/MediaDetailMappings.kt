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
    val seasonNumbers = seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
    return seasonNumbers
}

fun CrispyBackendClient.MetadataView.toMediaDetails(): MediaDetails {
    return MediaDetails(
        id = id,
        itemId = itemId,
        imdbId = externalIds.imdb,
        itemType = normalizedCatalogMediaType(),
        title = title?.trim()?.takeIf { it.isNotBlank() } ?: subtitle?.trim()?.takeIf { it.isNotBlank() } ?: id,
        posterUrl = images.posterUrl,
        backdropUrl = images.backdropUrl,
        logoUrl = images.logoUrl,
        description = summary ?: overview,
        genres = genres,
        year = releaseYear?.toString() ?: releaseDate?.take(4),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        certification = certification,
        rating = formatRating(rating),
        cast = emptyList(),
        directors = emptyList(),
        creators = emptyList(),
        videos = nextEpisode?.let { listOfNotNull(it.toMediaVideo()) } ?: emptyList(),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        addonId = "backend",
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

fun CrispyBackendClient.MetadataEpisodeView.toMediaVideo(): MediaVideo? {
    val canonicalId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    val titleText =
        title?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                episode != null -> "Episode $episode"
                else -> canonicalId
            }
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = season,
        episode = episode,
        released = airDate,
        overview = summary,
        thumbnailUrl = images.stillUrl ?: images.posterUrl,
        lookupId = buildAddonEpisodeLookupId(showExternalIds.imdb, season, episode) ?: itemId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

fun CrispyBackendClient.MetadataView.toMediaVideo(): MediaVideo? {
    val canonicalId = itemId.trim().takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    val titleText =
        title?.trim()?.takeIf { it.isNotBlank() }
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
        overview = overview ?: summary,
        thumbnailUrl = images.stillUrl ?: images.posterUrl,
        lookupId = buildAddonEpisodeLookupId(externalIds.imdb, season, episode) ?: itemId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

fun CrispyBackendClient.MetadataEpisodePreview.toMediaVideo(): MediaVideo? {
  val canonicalId = id.trim().takeIf { it.isNotBlank() } ?: return null
  val season = seasonNumber
  val episode = episodeNumber
  val titleText =
    title?.trim()?.takeIf { it.isNotBlank() }
      ?: when {
        episode != null -> "Episode $episode"
        else -> canonicalId
      }
  return MediaVideo(
    id = canonicalId,
    title = titleText,
    season = season,
    episode = episode,
    released = airDate,
    overview = summary,
    thumbnailUrl = images.stillUrl ?: images.posterUrl,
    lookupId = itemId,
    absoluteEpisodeNumber = absoluteEpisodeNumber,
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

fun CrispyBackendClient.MetadataView.normalizedCatalogMediaType(): String {
    return when {
        itemType.equals("anime", ignoreCase = true) -> "anime"
        itemType.equals("episode", ignoreCase = true) -> "episode"
        itemType.equals("show", ignoreCase = true) || itemType.equals("tv", ignoreCase = true) -> "show"
        else -> "movie"
    }
}

fun CrispyBackendClient.MetadataCardView.normalizedCatalogMediaType(): String {
    return when {
        itemType.equals("anime", ignoreCase = true) -> "anime"
        itemType.equals("show", ignoreCase = true) || itemType.equals("tv", ignoreCase = true) -> "show"
        else -> "movie"
    }
}
