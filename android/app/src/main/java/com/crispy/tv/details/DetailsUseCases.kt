package com.crispy.tv.details

import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.episodesForSeason
import com.crispy.tv.metadata.seasonNumbers
import com.crispy.tv.metadata.toMediaDetails
import com.crispy.tv.metadata.toMediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class DetailsScreenLoadResult(
    val details: MediaDetails?,
    val titleDetail: CrispyBackendClient.MetadataTitleDetailResponse?,
    val statusMessage: String,
    val providerState: ProviderState,
    val watchCta: WatchCta,
    val continueVideoId: String?,
    val seasons: List<Int>,
)

internal data class DetailsSecondaryLoadResult(
    val titleReviews: CrispyBackendClient.MetadataTitleReviewsResponse?,
    val titleContent: CrispyBackendClient.MetadataTitleContentResponse?,
    val titleRatings: CrispyBackendClient.MetadataTitleRatingsResponse?,
)

data class RuntimeDetailsEntry(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
)

internal data class RuntimeEpisodeTarget(
    val episodeId: String,
    val seasonNumber: Int?,
)

internal data class DetailsSeasonEpisodesResult(
    val videos: List<MediaVideo> = emptyList(),
    val episodeWatchStates: Map<String, EpisodeWatchState> = emptyMap(),
    val effectiveSeasonNumber: Int? = null,
    val includedSeasonNumbers: List<Int> = emptyList(),
    val errorMessage: String? = null,
)

internal data class DetailsMutationResult(
    val details: MediaDetails,
    val success: Boolean,
    val statusMessage: String,
)

internal class DetailsUseCases(
    private val sessionRepository: SessionRepository,
    private val catalogRepository: CatalogRepository,
    private val userMediaRepository: UserMediaRepository,
    private val aiRepository: AiInsightsRepository,
    private val addonStreamsService: AddonStreamsService,
    private val backendContextResolver: BackendContextResolver,
) {
    private val episodeWatchStateResolver = EpisodeWatchStateResolver(userMediaRepository)
    private val cachedBaseResults = ConcurrentHashMap<String, DetailsScreenLoadResult>()

    fun clearEpisodeWatchStateCache() {
        episodeWatchStateResolver.clearCache()
    }

    suspend fun loadScreen(
        mediaKey: String,
        requestedMediaType: MetadataLabMediaType,
        runtimeEntry: RuntimeDetailsEntry?,
        nowMs: Long,
    ): DetailsScreenLoadResult {
        val cacheKey = detailsCacheKey(mediaKey, requestedMediaType)
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        val profileId = backendContext?.profileId
        cachedBaseResults[cacheKey]?.takeIf { cached -> cached.details != null && accessToken != null }?.let { cached ->
            val watchCtaResolver = WatchCtaResolver(userMediaRepository, requestedMediaType)
            val providerState = watchCtaResolver.resolveProviderState(cached.details, mediaKey)
            val ctaResolution = watchCtaResolver.resolveWatchCta(cached.details, providerState, nowMs)
            return cached.copy(
                providerState = providerState,
                watchCta = ctaResolution.watchCta,
                continueVideoId = ctaResolution.continueVideoId,
            )
        }
        val titleDetailResult =
            accessToken?.let {
                runCatching {
                    catalogRepository.getTitleDetail(accessToken = it, mediaKey = mediaKey)
                }
            }
        val titleDetail = titleDetailResult?.getOrNull()
        val titleDetailError = titleDetailResult?.exceptionOrNull()
        val details = titleDetail?.toMediaDetails()?.let { ensureImdbId(it, requestedMediaType) }
        val watchCtaResolver = WatchCtaResolver(userMediaRepository, requestedMediaType)
        val providerState = watchCtaResolver.resolveProviderState(details, mediaKey)
        val ctaResolution = watchCtaResolver.resolveWatchCta(details, providerState, nowMs)

        val seasons =
            if (details?.mediaType?.toMetadataLabMediaTypeOrNull() == MetadataLabMediaType.MOVIE) {
                emptyList()
            } else {
                titleDetail?.seasonNumbers().orEmpty()
            }

        val statusMessage =
            when {
                details != null -> ""
                accessToken == null -> "Sign in to load details."
                titleDetailError != null -> titleDetailError.message ?: "Unable to load details."
                else -> "Unable to load details."
            }

        val result = DetailsScreenLoadResult(
            details = details,
            titleDetail = titleDetail,
            statusMessage = statusMessage,
            providerState = providerState,
            watchCta = ctaResolution.watchCta,
            continueVideoId = ctaResolution.continueVideoId,
            seasons = seasons,
        )
        if (result.details != null) {
            cachedBaseResults[cacheKey] = result
        } else {
            cachedBaseResults.remove(cacheKey)
        }
        return result
    }

    fun resolveRuntimeEpisodeTarget(
        videos: List<MediaVideo>,
        runtimeEntry: RuntimeDetailsEntry?,
    ): RuntimeEpisodeTarget? {
        if (runtimeEntry == null || videos.isEmpty()) return null

        val seasonNumber = runtimeEntry.seasonNumber
        val episodeNumber = runtimeEntry.episodeNumber
        val absoluteEpisodeNumber = runtimeEntry.absoluteEpisodeNumber

        val exactMatch =
            videos.firstOrNull { video ->
                val seasonMatches = seasonNumber == null || video.season == seasonNumber
                val episodeMatches = episodeNumber == null || video.episode == episodeNumber
                val absoluteMatches = absoluteEpisodeNumber == null || video.absoluteEpisodeNumber == absoluteEpisodeNumber
                seasonMatches && episodeMatches && absoluteMatches
            }
        if (exactMatch != null) {
            return RuntimeEpisodeTarget(
                episodeId = exactMatch.id,
                seasonNumber = exactMatch.season,
            )
        }

        val absoluteMatch =
            absoluteEpisodeNumber?.let { absolute ->
                videos.firstOrNull { video -> video.absoluteEpisodeNumber == absolute }
            }
        if (absoluteMatch != null) {
            return RuntimeEpisodeTarget(
                episodeId = absoluteMatch.id,
                seasonNumber = absoluteMatch.season,
            )
        }

        val seasonEpisodeMatch =
            videos.firstOrNull { video ->
                video.season == seasonNumber && video.episode == episodeNumber
            }
        return seasonEpisodeMatch?.let { video ->
            RuntimeEpisodeTarget(
                episodeId = video.id,
                seasonNumber = video.season,
            )
        }
    }

    suspend fun loadSecondaryContent(
        mediaKey: String,
    ): DetailsSecondaryLoadResult {
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        val profileId = backendContext?.profileId

        val titleReviews =
            accessToken?.takeIf { !profileId.isNullOrBlank() }?.let {
                runCatching {
                    catalogRepository.getTitleReviews(
                        accessToken = it,
                        profileId = checkNotNull(profileId),
                        mediaKey = mediaKey,
                    )
                }.getOrNull()
            }
        val titleContent =
            accessToken?.let {
                runCatching {
                    catalogRepository.getTitleContent(accessToken = it, mediaKey = mediaKey)
                }.getOrNull()
            }
        val titleRatings =
            accessToken?.takeIf { !profileId.isNullOrBlank() }?.let {
                runCatching {
                    catalogRepository.getTitleRatings(
                        accessToken = it,
                        profileId = checkNotNull(profileId),
                        mediaKey = mediaKey,
                    )
                }.getOrNull()
            }

        return DetailsSecondaryLoadResult(
            titleReviews = titleReviews,
            titleContent = titleContent,
            titleRatings = titleRatings,
        )
    }

    suspend fun resolveWatchCta(
        details: MediaDetails?,
        providerState: ProviderState,
        requestedMediaType: MetadataLabMediaType,
        nowMs: Long,
    ): WatchCtaResolver.Resolution {
        return WatchCtaResolver(userMediaRepository, requestedMediaType).resolveWatchCta(details, providerState, nowMs)
    }

    suspend fun resolveProviderState(
        details: MediaDetails?,
        itemId: String,
        requestedMediaType: MetadataLabMediaType,
    ): ProviderState {
        return WatchCtaResolver(userMediaRepository, requestedMediaType).resolveProviderState(details, itemId)
    }

    suspend fun loadSeasonEpisodes(
        mediaKey: String,
        season: Int,
        details: MediaDetails,
        titleDetail: CrispyBackendClient.MetadataTitleDetailResponse? = null,
    ): DetailsSeasonEpisodesResult {
        titleDetail
            ?.episodesForSeason(season)
            ?.mapNotNull(CrispyBackendClient.MetadataEpisodeView::toMediaVideo)
            ?.takeIf { it.isNotEmpty() }
            ?.let { videos ->
                return DetailsSeasonEpisodesResult(
                    videos = videos,
                    episodeWatchStates = resolveEpisodeWatchStates(details, videos),
                    effectiveSeasonNumber = season,
                    includedSeasonNumbers = titleDetail.seasonNumbers(),
                )
            }

        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
            ?: return DetailsSeasonEpisodesResult(errorMessage = "Sign in to load episodes.")

        val episodeResponse =
            runCatching {
                catalogRepository.listEpisodes(
                    accessToken = session.accessToken,
                    mediaKey = mediaKey,
                    seasonNumber = season,
                )
            }.getOrElse {
                return DetailsSeasonEpisodesResult(errorMessage = "Failed to load episodes.")
            }

        val videos =
            episodeResponse.episodes
                .mapNotNull(CrispyBackendClient.MetadataEpisodeView::toMediaVideo)
                .sortedWith(compareBy<MediaVideo>({ it.episode ?: Int.MAX_VALUE }, { it.title.lowercase(Locale.US) }, { it.id }))
        val episodeWatchStates = resolveEpisodeWatchStates(details, videos)

        return DetailsSeasonEpisodesResult(
            videos = videos,
            episodeWatchStates = episodeWatchStates,
            effectiveSeasonNumber = episodeResponse.effectiveSeasonNumber,
            includedSeasonNumbers = episodeResponse.includedSeasonNumbers.sorted(),
        )
    }

    suspend fun resolveEpisodeWatchStates(
        details: MediaDetails,
        videos: List<MediaVideo>,
    ): Map<String, EpisodeWatchState> {
        return episodeWatchStateResolver.resolve(details, videos)
    }

    suspend fun ensureImdbId(
        details: MediaDetails,
        requestedMediaType: MetadataLabMediaType,
    ): MediaDetails {
        return WatchCtaResolver(userMediaRepository, requestedMediaType).ensureImdbId(details)
    }

    private fun detailsCacheKey(
        mediaKey: String,
        requestedMediaType: MetadataLabMediaType,
    ): String {
        return "${requestedMediaType.name.lowercase(Locale.US)}:${mediaKey.trim()}"
    }

    fun loadCachedAiInsights(
        mediaKey: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? {
        return aiRepository.loadCached(mediaKey, locale)
    }

    suspend fun generateAiInsights(
        mediaKey: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult {
        return aiRepository.generate(mediaKey, locale)
    }

    suspend fun loadStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        onProvidersResolved: (List<StreamProviderDescriptor>) -> Unit,
        onProviderResult: (ProviderStreamsResult) -> Unit,
    ): List<ProviderStreamsResult> {
        return addonStreamsService.loadStreams(
            mediaType = mediaType,
            lookupId = lookupId,
            onProvidersResolved = onProvidersResolved,
            onProviderResult = onProviderResult,
        )
    }

    suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        return addonStreamsService.loadProviderStreams(
            mediaType = mediaType,
            lookupId = lookupId,
            providerId = providerId,
        )
    }

suspend fun updateWatchlist(
    details: MediaDetails,
    desired: Boolean,
): DetailsMutationResult {
    val mediaKey = details.mediaKey?.trim()?.ifBlank { null }
        ?: return DetailsMutationResult(
            details = details,
            success = false,
            statusMessage = "Title media key is unavailable.",
        )
    val result = userMediaRepository.setTitleInWatchlist(mediaKey, desired)
    return DetailsMutationResult(
        details = details,
        success = mutationSucceeded(result),
        statusMessage = result.statusMessage,
    )
}

suspend fun updateWatched(
    details: MediaDetails,
    desired: Boolean,
): DetailsMutationResult {
    val request = buildTitleWatchHistoryRequest(details)
    val result =
        if (desired) {
            userMediaRepository.markWatched(request)
        } else {
            userMediaRepository.unmarkWatched(request)
        }
    return DetailsMutationResult(
        details = details,
        success = mutationSucceeded(result),
        statusMessage = result.statusMessage,
    )
}

suspend fun updateEpisodeWatched(
    details: MediaDetails,
    video: MediaVideo,
    desired: Boolean,
): DetailsMutationResult {
    val season = video.season ?: return DetailsMutationResult(
        details = details,
        success = false,
        statusMessage = "Episode metadata is incomplete.",
    )
    val episode = video.episode ?: return DetailsMutationResult(
        details = details,
        success = false,
        statusMessage = "Episode metadata is incomplete.",
    )
    val contentType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES
    val request =
        WatchHistoryRequest(
            mediaKey = details.mediaKey,
            contentType = contentType,
            title = details.title,
            season = season,
            episode = episode,
            absoluteEpisodeNumber = video.absoluteEpisodeNumber ?: details.absoluteEpisodeNumber,
        )
    val result =
        if (desired) {
            userMediaRepository.markWatched(request)
        } else {
            userMediaRepository.unmarkWatched(request)
        }
    return DetailsMutationResult(
        details = details,
        success = mutationSucceeded(result),
        statusMessage = result.statusMessage,
    )
}

suspend fun updateRating(
    details: MediaDetails,
    rating: Int?,
): DetailsMutationResult {
    val mediaKey = details.mediaKey?.trim()?.ifBlank { null }
        ?: return DetailsMutationResult(
            details = details,
            success = false,
            statusMessage = "Title media key is unavailable.",
        )
    val result = userMediaRepository.setTitleRating(mediaKey, rating)
    return DetailsMutationResult(
        details = details,
        success = mutationSucceeded(result),
        statusMessage = result.statusMessage,
    )
}

private fun buildTitleWatchHistoryRequest(details: MediaDetails): WatchHistoryRequest {
    val contentType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
    return WatchHistoryRequest(
        mediaKey = details.mediaKey,
        contentType = contentType,
        title = details.title,
        absoluteEpisodeNumber = details.absoluteEpisodeNumber,
    )
}

    private fun mutationSucceeded(result: WatchHistoryResult): Boolean {
        return result.accepted
    }

    private fun parentMediaTypeFor(contentType: MetadataLabMediaType): String? {
        return when (contentType) {
            MetadataLabMediaType.MOVIE -> null
            MetadataLabMediaType.SERIES -> "show"
            MetadataLabMediaType.ANIME -> "anime"
        }
    }
}
