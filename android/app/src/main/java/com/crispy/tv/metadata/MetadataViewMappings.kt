package com.crispy.tv.metadata

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.ratings.formatRating
import java.util.Locale

internal fun CrispyBackendClient.MetadataTitleDetailResponse.toMediaDetails(): MediaDetails {
    return item.toMediaDetails().copy(
        videos = emptyList(),
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
        videos = emptyList(),
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        addonId = "backend",
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
        showExternalIds.imdb?.trim()?.takeIf { it.isNotBlank() }
            ?: showTmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
    val lookupId =
        if (season != null && episode != null && showLookupBase != null) {
            "${normalizeNuvioMediaId(showLookupBase).contentId}:$season:$episode"
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
    )
}

internal fun CrispyBackendClient.MetadataView.normalizedCatalogMediaType(): String {
    return if (mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)) {
        "series"
    } else {
        "movie"
    }
}

internal fun MediaDetails.providerBaseLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: (showTmdbId ?: tmdbId)?.takeIf { it > 0 }?.let { "tmdb:$it" }
}

internal fun MediaDetails.tmdbLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: primaryTmdbId()?.takeIf { it > 0 }?.let { "tmdb:$it" }
}

internal fun MediaDetails.primaryTmdbId(): Int? {
    return showTmdbId ?: tmdbId
}

internal fun MediaDetails.mergeEnhancements(
    enhancement: MediaDetails,
    resolvedImdbId: String? = null,
): MediaDetails {
    return copy(
        imdbId = imdbId ?: resolvedImdbId ?: enhancement.imdbId,
        posterUrl = posterUrl ?: enhancement.posterUrl,
        backdropUrl = backdropUrl ?: enhancement.backdropUrl,
        logoUrl = logoUrl ?: enhancement.logoUrl,
        description = description ?: enhancement.description,
        genres = if (genres.isNotEmpty()) genres else enhancement.genres,
        year = year ?: enhancement.year,
        runtime = runtime ?: enhancement.runtime,
        certification = certification ?: enhancement.certification,
        rating = rating ?: enhancement.rating,
        cast = if (cast.isNotEmpty()) cast else enhancement.cast,
        directors = if (directors.isNotEmpty()) directors else enhancement.directors,
        creators = if (creators.isNotEmpty()) creators else enhancement.creators,
        tmdbId = tmdbId ?: enhancement.tmdbId,
        showTmdbId = showTmdbId ?: enhancement.showTmdbId,
    )
}

internal fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
    return when (this?.lowercase(Locale.US)) {
        "movie" -> MetadataLabMediaType.MOVIE
        "series", "show", "tv" -> MetadataLabMediaType.SERIES
        else -> null
    }
}
