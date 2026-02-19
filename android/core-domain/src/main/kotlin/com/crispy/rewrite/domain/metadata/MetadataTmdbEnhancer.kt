package com.crispy.rewrite.domain.metadata

enum class MetadataMediaType {
    MOVIE,
    SERIES;

    companion object {
        fun fromContractValue(value: String): MetadataMediaType {
            return if (value.equals("series", ignoreCase = true)) SERIES else MOVIE
        }
    }
}

data class MetadataSeason(
    val id: String,
    val name: String,
    val overview: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val airDate: String?
)

data class MetadataVideo(
    val season: Int?,
    val episode: Int?,
    val released: String?
)

data class MetadataRecord(
    val id: String,
    val imdbId: String?,
    val cast: List<String>,
    val director: List<String>,
    val castWithDetails: List<String>,
    val similar: List<String>,
    val collectionItems: List<String>,
    val seasons: List<MetadataSeason>,
    val videos: List<MetadataVideo>
)

fun needsTmdbMetaEnrichment(meta: MetadataRecord, mediaType: MetadataMediaType): Boolean {
    if (meta.castWithDetails.isEmpty()) {
        return true
    }
    if (meta.similar.isEmpty()) {
        return true
    }
    return mediaType == MetadataMediaType.MOVIE && meta.collectionItems.isEmpty()
}

fun mergeAddonAndTmdbMeta(
    addonMeta: MetadataRecord,
    tmdbMeta: MetadataRecord?,
    mediaType: MetadataMediaType
): MetadataRecord {
    if (tmdbMeta == null) {
        return addonMeta
    }

    return addonMeta.copy(
        id = addonMeta.id.ifBlank { tmdbMeta.id },
        imdbId = addonMeta.imdbId.nonBlankOrNull() ?: tmdbMeta.imdbId.nonBlankOrNull(),
        cast = addonMeta.cast.ifEmpty { tmdbMeta.cast },
        director = addonMeta.director.ifEmpty { tmdbMeta.director },
        castWithDetails = addonMeta.castWithDetails.ifEmpty { tmdbMeta.castWithDetails },
        similar = addonMeta.similar.ifEmpty { tmdbMeta.similar },
        collectionItems =
            if (mediaType == MetadataMediaType.MOVIE) {
                addonMeta.collectionItems.ifEmpty { tmdbMeta.collectionItems }
            } else {
                addonMeta.collectionItems
            }
    )
}

fun withDerivedSeasons(meta: MetadataRecord, mediaType: MetadataMediaType): MetadataRecord {
    if (mediaType != MetadataMediaType.SERIES || meta.seasons.isNotEmpty()) {
        return meta
    }

    val bySeason = linkedMapOf<Int, SeasonAccumulator>()
    for (video in meta.videos) {
        val seasonNumber = video.season
        val episodeNumber = video.episode
        if (seasonNumber == null || seasonNumber <= 0 || episodeNumber == null || episodeNumber <= 0) {
            continue
        }

        val accumulator = bySeason.getOrPut(seasonNumber) { SeasonAccumulator() }
        accumulator.episodeCount += 1
        if (accumulator.airDate == null) {
            accumulator.airDate = video.released.nonBlankOrNull()
        }
    }

    val derived = bySeason
        .entries
        .sortedBy { it.key }
        .map { (seasonNumber, accumulator) ->
            MetadataSeason(
                id = "${meta.id}:season:$seasonNumber",
                name = "Season $seasonNumber",
                overview = "",
                seasonNumber = seasonNumber,
                episodeCount = accumulator.episodeCount,
                airDate = accumulator.airDate
            )
        }

    return meta.copy(seasons = derived)
}

fun bridgeCandidateIds(
    contentId: String,
    season: Int?,
    episode: Int?,
    tmdbMeta: MetadataRecord?
): List<String> {
    val normalizedContentId = contentId.trim()
    if (normalizedContentId.isEmpty()) {
        return emptyList()
    }

    val candidates = mutableListOf(normalizedEpisodeId(normalizedContentId, season, episode))
    if (normalizedContentId.startsWith("tmdb:", ignoreCase = true)) {
        val bridgedBase =
            tmdbMeta?.imdbId.nonBlankOrNull()
                ?: tmdbMeta?.id.nonBlankOrNull()?.takeIf {
                    !it.equals(normalizedContentId, ignoreCase = true)
                }
        if (bridgedBase != null) {
            candidates += normalizedEpisodeId(bridgedBase, season, episode)
        }
    }

    return candidates.distinct()
}

private data class SeasonAccumulator(
    var episodeCount: Int = 0,
    var airDate: String? = null
)

private fun normalizedEpisodeId(contentId: String, season: Int?, episode: Int?): String {
    return if (season != null && season > 0 && episode != null && episode > 0) {
        "$contentId:$season:$episode"
    } else {
        contentId
    }
}

private fun String?.nonBlankOrNull(): String? {
    val candidate = this?.trim()
    return if (candidate.isNullOrEmpty()) null else candidate
}
