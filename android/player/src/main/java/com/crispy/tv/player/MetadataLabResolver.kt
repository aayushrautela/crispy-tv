package com.crispy.tv.player

import com.crispy.tv.domain.metadata.AddonMetadataCandidate
import com.crispy.tv.domain.metadata.MetadataMediaType
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.bridgeCandidateIds
import com.crispy.tv.domain.metadata.mergeAddonAndTmdbMeta
import com.crispy.tv.domain.metadata.mergeAddonPrimaryMetadata
import com.crispy.tv.domain.metadata.needsTmdbMetaEnrichment
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.domain.metadata.withDerivedSeasons

enum class MetadataLabMediaType {
    MOVIE,
    SERIES
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
        request: MetadataLabRequest,
        normalizedId: com.crispy.tv.domain.metadata.NuvioMediaId
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
        val normalized = normalizeNuvioMediaId(request.rawId)
        require(normalized.contentId.isNotBlank()) { "content id is required" }

        val payload = dataSource.load(request = request, normalizedId = normalized)
        require(payload.addonResults.isNotEmpty()) { "addon results must not be empty" }

        val domainMediaType = request.mediaType.toDomainMediaType()
        val primary = mergeAddonPrimaryMetadata(payload.addonResults, request.preferredAddonId)
        val needsEnrichment = needsTmdbMetaEnrichment(payload.addonMeta, domainMediaType)
        val merged = withDerivedSeasons(
            mergeAddonAndTmdbMeta(payload.addonMeta, payload.tmdbMeta, domainMediaType),
            domainMediaType
        )

        return MetadataLabResolution(
            contentId = normalized.contentId,
            videoId = normalized.videoId,
            addonLookupId = normalized.addonLookupId,
            primaryId = primary.primaryId,
            primaryTitle = primary.title,
            sources = primary.sources,
            needsEnrichment = needsEnrichment,
            bridgeCandidateIds = bridgeCandidateIds(
                contentId = normalized.contentId,
                season = normalized.season,
                episode = normalized.episode,
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
        val normalized = normalizeNuvioMediaId(request.rawId)
        require(normalized.contentId.isNotBlank()) { "content id is required" }
        return MetadataLabResolution(
            contentId = normalized.contentId,
            videoId = normalized.videoId,
            addonLookupId = normalized.addonLookupId,
            primaryId = normalized.contentId,
            primaryTitle = "Unavailable",
            sources = emptyList(),
            needsEnrichment = false,
            bridgeCandidateIds = listOf(normalized.addonLookupId),
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
    }
}
