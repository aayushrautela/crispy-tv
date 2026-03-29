package com.crispy.tv.metadata

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.metadata.normalizeMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.ratings.formatRating
import java.util.Locale

internal fun CrispyBackendClient.MetadataTitleDetailResponse.toMediaDetails(): MediaDetails {
    val itemDetails = item.toMediaDetails()
    val mergedVideos = (itemDetails.videos + videos.mapNotNull { it.toMediaVideo() }).distinctBy { it.id }
    return itemDetails.copy(
        cast = cast.map { it.name },
        directors = directors.map { it.name },
        creators = creators.map { it.name },
        videos = mergedVideos,
    )
}

internal fun CrispyBackendClient.MetadataTitleDetailResponse.seasonNumbers(): List<Int> {
    val seasonNumbers = seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
    if (seasonNumbers.isNotEmpty()) return seasonNumbers
    val seasonCount = item.seasonCount ?: return emptyList()
    return if (seasonCount > 0) (1..seasonCount).toList() else emptyList()
}

internal fun CrispyBackendClient.MetadataView.toMediaDetails(): MediaDetails {
    return MediaDetails(
        id = id,
        imdbId = externalIds.imdb,
        mediaType = normalizedCatalogMediaType(),
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
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        addonId = "backend",
        provider = provider,
        providerId = providerId,
        parentMediaType = parentMediaType,
        parentProvider = parentProvider,
        parentProviderId = parentProviderId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

internal fun CrispyBackendClient.MetadataEpisodeView.toMediaVideo(): MediaVideo? {
    val canonicalId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    val titleText =
        title?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                episode != null -> "Episode $episode"
                else -> canonicalId
            }
    val showLookupBase =
        canonicalProviderLookupId(parentProvider, parentProviderId)
            ?: showId?.trim()?.takeIf { it.isNotBlank() }
            ?: externalProviderLookupId(showExternalIds)
    val lookupId =
        if (season != null && episode != null && showLookupBase != null) {
            "${normalizeMediaId(showLookupBase).contentId}:$season:$episode"
        } else {
            null
        }
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = season,
        episode = episode,
        released = airDate,
        overview = summary,
        thumbnailUrl = images.stillUrl ?: images.posterUrl,
        lookupId = lookupId,
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
        provider = provider,
        providerId = providerId,
        parentProvider = parentProvider,
        parentProviderId = parentProviderId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

internal fun CrispyBackendClient.MetadataEpisodePreview.toMediaVideo(): MediaVideo? {
    val canonicalId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    val titleText =
        title?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                episode != null -> "Episode $episode"
                else -> canonicalId
            }
    val showLookupBase =
        canonicalProviderLookupId(parentProvider, parentProviderId)
    val lookupId =
        if (season != null && episode != null && showLookupBase != null) {
            "${normalizeMediaId(showLookupBase).contentId}:$season:$episode"
        } else {
            null
        }
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = season,
        episode = episode,
        released = airDate,
        overview = summary,
        thumbnailUrl = images.stillUrl ?: images.posterUrl,
        lookupId = lookupId,
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
        provider = provider,
        providerId = providerId,
        parentProvider = parentProvider,
        parentProviderId = parentProviderId,
        absoluteEpisodeNumber = absoluteEpisodeNumber,
    )
}

internal fun CrispyBackendClient.MetadataVideoView.toMediaVideo(): MediaVideo? {
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
        tmdbId = null,
        showTmdbId = null,
    )
}

internal fun CrispyBackendClient.MetadataView.normalizedCatalogMediaType(): String {
    return when {
        mediaType.equals("anime", ignoreCase = true) -> "anime"
        mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true) -> "series"
        else -> "movie"
    }
}

internal fun CrispyBackendClient.MetadataCardView.normalizedCatalogMediaType(): String {
    return when {
        mediaType.equals("anime", ignoreCase = true) -> "anime"
        mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true) -> "series"
        else -> "movie"
    }
}

internal fun CrispyBackendClient.MetadataCardView.toCatalogItem(): CatalogItem? {
    val itemId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val itemTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: subtitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return CatalogItem(
        id = itemId,
        title = itemTitle,
        posterUrl = images.posterUrl,
        backdropUrl = images.backdropUrl,
        logoUrl = images.logoUrl,
        addonId = "backend",
        type = normalizedCatalogMediaType(),
        rating = formatRating(rating),
        year = releaseYear?.toString() ?: releaseDate?.take(4),
        genre = null,
        description = summary ?: overview,
    )
}

internal fun MediaDetails.providerBaseLookupId(): String? {
    return canonicalProviderLookupId(parentProvider, parentProviderId)
        ?: canonicalProviderLookupId(provider, providerId)
        ?: id.trim().takeIf { it.isNotBlank() }
        ?: imdbId?.trim()?.takeIf { it.isNotBlank() }
}

internal fun CrispyBackendClient.MetadataView.providerBaseLookupId(): String? {
    return canonicalProviderLookupId(parentProvider, parentProviderId)
        ?: canonicalProviderLookupId(provider, providerId)
        ?: id.trim().takeIf { it.isNotBlank() }
        ?: externalProviderLookupId(externalIds)
}

internal fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
    return when (this?.lowercase(Locale.US)) {
        "movie" -> MetadataLabMediaType.MOVIE
        "series", "show", "tv" -> MetadataLabMediaType.SERIES
        "anime" -> MetadataLabMediaType.ANIME
        else -> null
    }
}

private fun canonicalProviderLookupId(provider: String?, providerId: String?): String? {
    val normalizedProvider = provider?.trim()?.lowercase(Locale.US).orEmpty()
    val normalizedProviderId = providerId?.trim().orEmpty()
    if (normalizedProvider.isBlank() || normalizedProviderId.isBlank()) return null
    return "$normalizedProvider:$normalizedProviderId"
}

private fun externalProviderLookupId(externalIds: CrispyBackendClient.MetadataExternalIds?): String? {
    val ids = externalIds ?: return null
    return ids.tvdb?.takeIf { it > 0 }?.let { "tvdb:$it" }
        ?: ids.kitsu?.takeIf { it > 0 }?.let { "kitsu:$it" }
        ?: ids.imdb?.trim()?.takeIf { it.isNotBlank() }
        ?: ids.tmdb?.takeIf { it > 0 }?.let { "tmdb:$it" }
}
