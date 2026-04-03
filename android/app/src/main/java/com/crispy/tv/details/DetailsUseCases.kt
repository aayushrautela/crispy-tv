package com.crispy.tv.details

import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.seasonNumbers
import com.crispy.tv.metadata.toMediaDetails
import com.crispy.tv.metadata.toMediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import java.util.Locale

internal data class DetailsScreenLoadResult(
    val details: MediaDetails?,
    val titleDetail: CrispyBackendClient.MetadataTitleDetailResponse?,
    val omdbContent: CrispyBackendClient.MetadataTitleContentResponse?,
    val statusMessage: String,
    val providerState: ProviderState,
    val watchCta: WatchCta,
    val continueVideoId: String?,
    val seasons: List<Int>,
)

data class RuntimeDetailsEntry(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
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
) {
    private val episodeWatchStateResolver = EpisodeWatchStateResolver(userMediaRepository)

    fun clearEpisodeWatchStateCache() {
        episodeWatchStateResolver.clearCache()
    }

    suspend fun loadScreen(
        mediaKey: String,
        requestedMediaType: MetadataLabMediaType,
        runtimeEntry: RuntimeDetailsEntry?,
        nowMs: Long,
    ): DetailsScreenLoadResult {
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val titleDetailResult =
            session?.let {
                runCatching {
                    catalogRepository.getTitleDetail(accessToken = it.accessToken, mediaKey = mediaKey)
                }
            }
        val titleDetail = titleDetailResult?.getOrNull()
        val titleDetailError = titleDetailResult?.exceptionOrNull()
        val titleContentResult =
            session?.let {
                runCatching {
                    catalogRepository.getTitleContent(accessToken = it.accessToken, mediaKey = mediaKey)
                }
            }
        val titleContent = titleContentResult?.getOrNull()
        val titleContentError = titleContentResult?.exceptionOrNull()
        val details = titleDetail?.toMediaDetails()?.let { ensureImdbId(it, requestedMediaType) }
        val watchCtaResolver = WatchCtaResolver(userMediaRepository, requestedMediaType)
        val providerState = watchCtaResolver.resolveProviderState(details, mediaKey)
        val (watchCta, continueVideoId) = watchCtaResolver.resolveWatchCta(details, providerState, nowMs)

        val seasons =
            if (details?.mediaType?.toMetadataLabMediaTypeOrNull() == MetadataLabMediaType.MOVIE) {
                emptyList()
            } else {
                val backendSeasons = titleDetail?.seasonNumbers().orEmpty()
                val seasonCount = titleDetail?.item?.seasonCount ?: 0
                when {
                    backendSeasons.isNotEmpty() -> backendSeasons
                    seasonCount > 0 -> (1..seasonCount).toList()
                    else -> emptyList()
                }
            }

        val statusMessage =
            when {
                details != null -> ""
                session == null -> "Sign in to load details."
                titleDetailError != null -> titleDetailError.message ?: "Unable to load details."
                titleContentError != null -> titleContentError.message ?: "Unable to load details."
                else -> "Unable to load details."
            }

        return DetailsScreenLoadResult(
            details = details,
            titleDetail = titleDetail,
            omdbContent = titleContent,
            statusMessage = statusMessage,
            providerState = providerState,
            watchCta = watchCta,
            continueVideoId = continueVideoId,
            seasons = seasons,
        )
    }

    suspend fun loadSeasonEpisodes(
        mediaKey: String,
        season: Int,
        details: MediaDetails,
    ): DetailsSeasonEpisodesResult {
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

    fun loadCachedAiInsights(
        contentId: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? {
        return aiRepository.loadCached(contentId, locale)
    }

    suspend fun generateAiInsights(
        contentId: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult {
        return aiRepository.generate(contentId, locale)
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
        val source = userMediaRepository.preferredProvider()
        val enriched = ensureImdbId(details, details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE)
        if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
            return DetailsMutationResult(
                details = enriched,
                success = false,
                statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl).",
            )
        }

        val request = buildTitleWatchHistoryRequest(enriched)
        val result = userMediaRepository.setInWatchlist(request, desired, source)
        return DetailsMutationResult(
            details = enriched,
            success = mutationSucceeded(source, result),
            statusMessage = result.statusMessage,
        )
    }

    suspend fun updateWatched(
        details: MediaDetails,
        desired: Boolean,
    ): DetailsMutationResult {
        val source = userMediaRepository.preferredProvider()
        val enriched = ensureImdbId(details, details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE)
        if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
            return DetailsMutationResult(
                details = enriched,
                success = false,
                statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl).",
            )
        }

        val request = buildTitleWatchHistoryRequest(enriched)
        val result =
            if (desired) {
                userMediaRepository.markWatched(request, source)
            } else {
                userMediaRepository.unmarkWatched(request, source)
            }
        return DetailsMutationResult(
            details = enriched,
            success = mutationSucceeded(source, result),
            statusMessage = result.statusMessage,
        )
    }

    suspend fun updateEpisodeWatched(
        details: MediaDetails,
        video: MediaVideo,
        desired: Boolean,
    ): DetailsMutationResult {
        val source = userMediaRepository.preferredProvider()
        val enriched = ensureImdbId(details, details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES)
        if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
            return DetailsMutationResult(
                details = enriched,
                success = false,
                statusMessage = "Couldn't resolve an IMDb id for this show (required for Simkl).",
            )
        }

        val season = video.season ?: return DetailsMutationResult(
            details = enriched,
            success = false,
            statusMessage = "Episode metadata is incomplete.",
        )
        val episode = video.episode ?: return DetailsMutationResult(
            details = enriched,
            success = false,
            statusMessage = "Episode metadata is incomplete.",
        )
        val contentType = enriched.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES
        val request =
            WatchHistoryRequest(
                contentId = enriched.id,
                mediaKey = enriched.mediaKey,
                contentType = contentType,
                title = enriched.title,
                season = season,
                episode = episode,
                remoteImdbId = enriched.imdbId,
                provider = video.provider ?: enriched.provider,
                providerId = video.providerId ?: enriched.providerId,
                parentMediaType = parentMediaTypeFor(contentType),
                parentProvider = video.parentProvider ?: enriched.parentProvider ?: enriched.provider,
                parentProviderId = video.parentProviderId ?: enriched.parentProviderId ?: enriched.providerId,
                absoluteEpisodeNumber = video.absoluteEpisodeNumber ?: enriched.absoluteEpisodeNumber,
            )
        val result =
            if (desired) {
                userMediaRepository.markWatched(request, source)
            } else {
                userMediaRepository.unmarkWatched(request, source)
            }
        return DetailsMutationResult(
            details = enriched,
            success = mutationSucceeded(source, result),
            statusMessage = result.statusMessage,
        )
    }

    suspend fun updateRating(
        details: MediaDetails,
        rating: Int?,
    ): DetailsMutationResult {
        val source = userMediaRepository.preferredProvider()
        if (source == WatchProvider.SIMKL && rating == null) {
            return DetailsMutationResult(
                details = details,
                success = false,
                statusMessage = "Removing ratings is not supported for Simkl yet.",
            )
        }

        val enriched = ensureImdbId(details, details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE)
        if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
            return DetailsMutationResult(
                details = enriched,
                success = false,
                statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl).",
            )
        }

        val request = buildTitleWatchHistoryRequest(enriched)
        val result = userMediaRepository.setRating(request, rating, source)
        return DetailsMutationResult(
            details = enriched,
            success = mutationSucceeded(source, result),
            statusMessage = result.statusMessage,
        )
    }

    private fun buildTitleWatchHistoryRequest(details: MediaDetails): WatchHistoryRequest {
        val contentType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        return WatchHistoryRequest(
            contentId = details.id,
            mediaKey = details.mediaKey,
            contentType = contentType,
            title = details.title,
            remoteImdbId = details.imdbId,
            provider = details.provider,
            providerId = details.providerId,
            parentMediaType = details.parentMediaType,
            parentProvider = details.parentProvider,
            parentProviderId = details.parentProviderId,
            absoluteEpisodeNumber = details.absoluteEpisodeNumber,
        )
    }

    private fun mutationSucceeded(
        source: WatchProvider?,
        result: WatchHistoryResult,
    ): Boolean {
        return when (source) {
            WatchProvider.TRAKT -> result.syncedToTrakt
            WatchProvider.SIMKL -> result.syncedToSimkl
            WatchProvider.LOCAL -> true
            null -> result.accepted
        }
    }

    private fun parentMediaTypeFor(contentType: MetadataLabMediaType): String? {
        return when (contentType) {
            MetadataLabMediaType.MOVIE -> null
            MetadataLabMediaType.SERIES -> "show"
            MetadataLabMediaType.ANIME -> "anime"
        }
    }
}
