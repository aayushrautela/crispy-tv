package com.crispy.tv.playback

import com.crispy.tv.details.StreamProviderUiState
import com.crispy.tv.details.StreamSelectorUiState
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.providerBaseLookupId
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import java.util.Locale

val TMDB_ID_REGEX: Regex = Regex("\\btmdb:(?:movie:|show:|tv:)?(\\d+)", RegexOption.IGNORE_CASE)

data class StreamLookupTarget(
    val mediaType: MetadataLabMediaType,
    val lookupId: String,
)

typealias PlayerStreamLookupTarget = StreamLookupTarget

fun resolveStreamLookupTarget(
    details: MediaDetails,
    selectedSeason: Int?,
    seasonEpisodes: List<MediaVideo>,
    fallbackMediaType: MetadataLabMediaType,
): StreamLookupTarget {
    val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: fallbackMediaType
    val lookupId =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> details.providerBaseLookupId().orEmpty()
            MetadataLabMediaType.SERIES,
            MetadataLabMediaType.ANIME -> {
                val fromLoadedEpisodes = seasonEpisodes.firstOrNull { !it.lookupId.isNullOrBlank() }?.lookupId?.trim()
                if (fromLoadedEpisodes != null) {
                    fromLoadedEpisodes
                } else {
                    val season = selectedSeason ?: seasonEpisodes.firstOrNull()?.season ?: 1
                    val base = details.providerBaseLookupId().orEmpty()
                    val canonicalBase = normalizeNuvioMediaId(base).contentId.trim()
                    if (canonicalBase.isNotBlank() && season > 0) {
                        "$canonicalBase:$season:1"
                    } else {
                        base
                    }
                }
            }
        }

    return StreamLookupTarget(mediaType = mediaType, lookupId = lookupId)
}

fun findEpisodeForLookupId(
    lookupId: String,
    currentEpisodes: List<MediaVideo>,
    cachedEpisodes: Collection<List<MediaVideo>> = emptyList(),
): MediaVideo? {
    val normalizedLookupId = lookupId.trim()
    if (normalizedLookupId.isBlank()) return null

    return sequenceOf(currentEpisodes.asSequence(), cachedEpisodes.asSequence().flatten())
        .flatten()
        .firstOrNull { episode ->
            episode.lookupId?.equals(normalizedLookupId, ignoreCase = true) == true ||
                episode.id.equals(normalizedLookupId, ignoreCase = true)
        }
}

fun buildEpisodeLookupId(
    details: MediaDetails,
    season: Int,
    episode: Int,
): String? {
    if (season <= 0 || episode <= 0) return null
    val base = details.providerBaseLookupId() ?: return null
    val canonicalBase = normalizeNuvioMediaId(base).contentId.trim()
    if (canonicalBase.isBlank()) return null
    return "$canonicalBase:$season:$episode"
}

fun buildPlayerSubtitle(
    mediaType: MetadataLabMediaType,
    details: MediaDetails,
    playerTitle: String,
    season: Int?,
    episode: Int?,
): String? {
    return when (mediaType) {
        MetadataLabMediaType.SERIES,
        MetadataLabMediaType.ANIME -> {
            val normalizedPlayerTitle = playerTitle.trim().ifBlank { null }
            val episodeLabel =
                when {
                    season != null && episode != null -> {
                        "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
                    }

                    season != null -> "Season $season"
                    else -> null
                }
            val seriesTitle =
                details.title
                    .trim()
                    .ifBlank { null }
                    ?.takeUnless { it == normalizedPlayerTitle }
            listOfNotNull(seriesTitle, episodeLabel)
                .joinToString(separator = " • ")
                .ifBlank { null }
        }

        MetadataLabMediaType.MOVIE -> details.year?.trim()?.ifBlank { null }
    }
}

fun extractTmdbIdOrNull(rawId: String?): Int? {
    val value = rawId?.trim().orEmpty()
    if (value.isBlank()) return null
    val match = TMDB_ID_REGEX.find(value) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
}

fun ProviderStreamsResult.toUiState(): StreamProviderUiState {
    return StreamProviderUiState(
        providerId = providerId,
        providerName = providerName,
        isLoading = false,
        errorMessage = errorMessage,
        streams = streams,
        attemptedUrl = attemptedUrl,
    )
}

fun StreamProviderDescriptor.toLoadingUiState(): StreamProviderUiState {
    return StreamProviderUiState(
        providerId = providerId,
        providerName = providerName,
        isLoading = true,
    )
}

fun List<StreamProviderUiState>.applyProviderResult(result: ProviderStreamsResult): List<StreamProviderUiState> {
    var matched = false
    val updated =
        map { provider ->
            if (provider.providerId.equals(result.providerId, ignoreCase = true)) {
                matched = true
                result.toUiState()
            } else {
                provider
            }
        }

    return if (matched) updated else updated + result.toUiState()
}

fun List<StreamProviderUiState>.finalizeFrom(results: List<ProviderStreamsResult>): List<StreamProviderUiState> {
    if (isEmpty()) return emptyList()
    val byProviderId = results.associateBy { result -> result.providerId.lowercase(Locale.US) }

    return map { provider ->
        byProviderId[provider.providerId.lowercase(Locale.US)]?.toUiState() ?: provider.copy(isLoading = false)
    }
}

fun StreamSelectorUiState.matchesTarget(target: StreamLookupTarget): Boolean {
    return mediaType == target.mediaType && lookupId == target.lookupId
}

fun buildStreamStatusMessage(
    providers: List<StreamProviderUiState>,
    isLoading: Boolean,
): String {
    val totalStreams = providers.sumOf { provider -> provider.streams.size }
    val partialFailure = providers.any { provider -> provider.errorMessage != null }

    if (isLoading) {
        return if (totalStreams > 0) {
            "Found $totalStreams stream${if (totalStreams == 1) "" else "s"} so far..."
        } else {
            "Fetching streams..."
        }
    }

    return when {
        totalStreams > 0 -> "Found $totalStreams stream${if (totalStreams == 1) "" else "s"}."
        partialFailure -> "No streams found. Some providers failed to load."
        providers.isEmpty() -> "No stream providers are available for this title."
        else -> "No streams found for this title."
    }
}
