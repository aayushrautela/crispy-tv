package com.crispy.tv.player

import com.crispy.tv.domain.metadata.AddonMetadataCandidate
import com.crispy.tv.domain.metadata.MetadataMediaType
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.bridgeCandidateIds
import com.crispy.tv.domain.metadata.withDerivedSeasons

enum class MetadataLabMediaType {
    MOVIE,
    SERIES,
    ANIME;

    val label: String
        get() = when (this) {
            MOVIE -> "movie"
            SERIES -> "series"
            ANIME -> "anime"
        }
}

data class MetadataLabRequest(
    val rawId: String,
    val mediaType: MetadataLabMediaType,
    val preferredAddonId: String?
)

data class MetadataLabPayload(
    val addonResults: List<AddonMetadataCandidate>,
    val addonMeta: MetadataRecord,
    val tmdbMeta: MetadataRecord?,
    val transportStats: List<MetadataTransportStat> = emptyList()
)

data class MetadataTransportStat(
    val addonId: String,
    val streamLookupId: String,
    val streamCount: Int,
    val subtitleLookupId: String,
    val subtitleCount: Int
)

fun interface MetadataLabDataSource {
    suspend fun load(
        request: MetadataLabRequest
    ): MetadataLabPayload
}

data class MetadataLabResolution(
    val contentId: String,
    val videoId: String?,
    val addonLookupId: String,
    val primaryId: String,
    val primaryTitle: String,
    val sources: List<String>,
    val needsEnrichment: Boolean,
    val bridgeCandidateIds: List<String>,
    val mergedImdbId: String?,
    val mergedSeasonNumbers: List<Int>,
    val transportStats: List<MetadataTransportStat> = emptyList()
)

fun interface MetadataLabResolver {
    suspend fun resolve(request: MetadataLabRequest): MetadataLabResolution
}

class CoreDomainMetadataLabResolver(
    private val dataSource: MetadataLabDataSource
) : MetadataLabResolver {
    override suspend fun resolve(request: MetadataLabRequest): MetadataLabResolution {
        val contentId = request.rawId.trim()
        require(contentId.isNotBlank()) { "content id is required" }
        val parsedLookupId = parseTmdbLookupId(contentId)

        val payload = dataSource.load(request = request)
        require(payload.addonResults.isNotEmpty()) { "addon results must not be empty" }

        val domainMediaType = request.mediaType.toDomainMediaType()
        val primary = payload.addonResults.first()
        val merged = withDerivedSeasons(payload.tmdbMeta ?: payload.addonMeta, domainMediaType)

        return MetadataLabResolution(
            contentId = parsedLookupId.baseId,
            videoId = parsedLookupId.videoId,
            addonLookupId = parsedLookupId.videoId ?: parsedLookupId.baseId,
            primaryId = primary.mediaId,
            primaryTitle = primary.title,
            sources = listOf(primary.addonId),
            needsEnrichment = false,
            bridgeCandidateIds = bridgeCandidateIds(
                contentId = parsedLookupId.baseId,
                season = parsedLookupId.season,
                episode = parsedLookupId.episode,
                tmdbMeta = payload.tmdbMeta
            ),
            mergedImdbId = merged.imdbId,
            mergedSeasonNumbers = merged.seasons.map { it.seasonNumber },
            transportStats = payload.transportStats
        )
    }
}

object DefaultMetadataLabResolver : MetadataLabResolver {
    override suspend fun resolve(request: MetadataLabRequest): MetadataLabResolution {
        val contentId = request.rawId.trim()
        require(contentId.isNotBlank()) { "content id is required" }
        val parsedLookupId = parseTmdbLookupId(contentId)
        return MetadataLabResolution(
            contentId = parsedLookupId.baseId,
            videoId = parsedLookupId.videoId,
            addonLookupId = parsedLookupId.videoId ?: parsedLookupId.baseId,
            primaryId = parsedLookupId.baseId,
            primaryTitle = "Unavailable",
            sources = emptyList(),
            needsEnrichment = false,
            bridgeCandidateIds = listOf(parsedLookupId.videoId ?: parsedLookupId.baseId),
            mergedImdbId = null,
            mergedSeasonNumbers = emptyList(),
            transportStats = emptyList()
        )
    }
}

private fun MetadataLabMediaType.toDomainMediaType(): MetadataMediaType {
    return when (this) {
        MetadataLabMediaType.MOVIE -> MetadataMediaType.MOVIE
        MetadataLabMediaType.SERIES -> MetadataMediaType.SERIES
        MetadataLabMediaType.ANIME -> MetadataMediaType.ANIME
    }
}

private data class ParsedTmdbLookupId(
    val baseId: String,
    val videoId: String?,
    val season: Int?,
    val episode: Int?
)

private fun parseTmdbLookupId(rawId: String): ParsedTmdbLookupId {
    val trimmed = rawId.trim()
    val parts = trimmed.split(":")
    if (parts.size >= 3) {
        val season = parts[parts.lastIndex - 1].toIntOrNull()
        val episode = parts.last().toIntOrNull()
        if (season != null && season > 0 && episode != null && episode > 0) {
            val baseId = parts.dropLast(2).joinToString(":").trim()
            return ParsedTmdbLookupId(
                baseId = baseId,
                videoId = "$baseId:$season:$episode",
                season = season,
                episode = episode
            )
        }
    }
    return ParsedTmdbLookupId(baseId = trimmed, videoId = null, season = null, episode = null)
}
