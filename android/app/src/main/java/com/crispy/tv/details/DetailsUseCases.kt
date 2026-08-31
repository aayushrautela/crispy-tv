package com.crispy.tv.details

import android.util.Log
import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.mapping.toMediaDetails
import com.crispy.tv.addons.mapping.toMediaVideo
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderDescriptor
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

internal data class DetailsExtrasLoadResult(
    val titleExtras: CrispyBackendClient.MetadataTitleExtrasResponse?,
)

internal data class DetailsRatingsLoadResult(
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

internal class DetailsUseCases(
    private val sessionRepository: SessionRepository,
    private val catalogRepository: CatalogRepository,
    internal val userMediaRepository: UserMediaRepository,
    private val aiRepository: AiInsightsRepository,
    private val streamResolver: StreamResolver,
    private val backendContextResolver: BackendContextResolver,
    private val crispyBackendClient: CrispyBackendClient,
) {
    private val episodeWatchStateResolver = EpisodeWatchStateResolver(
        crispyBackendClient = crispyBackendClient,
        backendContextResolver = backendContextResolver,
        userMediaRepository = userMediaRepository,
    )
    private val cachedBaseResults = ConcurrentHashMap<String, DetailsScreenLoadResult>()

    fun clearEpisodeWatchStateCache() {
    }

    suspend fun loadScreen(
        itemId: String,
        requestedMediaType: MetadataLabMediaType,
        runtimeEntry: RuntimeDetailsEntry?,
        nowMs: Long,
    ): DetailsScreenLoadResult {
        val cacheKey = detailsCacheKey(itemId, requestedMediaType)
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        val profileId = backendContext?.profileId
        cachedBaseResults[cacheKey]?.takeIf { cached -> cached.details != null && accessToken != null }?.let { cached ->
            Log.d(
                TAG,
                "loadScreen CACHE HIT requestedItemId=$itemId -> cachedItemId=${cached.details?.itemId} cachedTitle=${cached.details?.title}",
            )
            val watchCtaResolver = WatchCtaResolver(userMediaRepository, requestedMediaType)
            val providerState = watchCtaResolver.resolveProviderState(cached.details, itemId)
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
                    catalogRepository.getTitleDetail(accessToken = it, itemId = itemId)
                }
            }
        val titleDetail = titleDetailResult?.getOrNull()
        val titleDetailError = titleDetailResult?.exceptionOrNull()
        val details = titleDetail?.toMediaDetails()?.let { ensureImdbId(it, requestedMediaType) }
        Log.d(
            TAG,
            "loadScreen requestedItemId=$itemId requestedType=$requestedMediaType -> responseItemId=${details?.itemId} responseTitle=${details?.title}",
        )
        val watchCtaResolver = WatchCtaResolver(userMediaRepository, requestedMediaType)
        val providerState = watchCtaResolver.resolveProviderState(details, itemId)
        val ctaResolution = watchCtaResolver.resolveWatchCta(details, providerState, nowMs)

        val seasons = emptyList<Int>()

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

    suspend fun loadExtras(
        itemId: String,
    ): DetailsExtrasLoadResult {
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken

        val titleExtras =
            if (accessToken == null) {
                Log.w(TAG, "Skipping title extras load: missing access token for itemId=$itemId")
                null
            } else {
                runCatching {
                    catalogRepository.getTitleExtras(
                        accessToken = accessToken,
                        itemId = itemId,
                    )
                }.onSuccess { extras ->
                    Log.d(
                        TAG,
                        "Loaded title extras for itemId=$itemId seasons=${extras.seasons.size} reviews=${extras.reviews.size} similar=${extras.similar.size} hasCollection=${extras.collection != null}",
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Failed to load title extras for itemId=$itemId", error)
                }.getOrNull()
            }

        return DetailsExtrasLoadResult(titleExtras = titleExtras)
    }

    suspend fun loadAllEpisodes(
        itemId: String,
    ): List<MediaVideo> {
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        if (accessToken == null) {
            Log.w(TAG, "Skipping series episodes load: missing access token for itemId=$itemId")
            return emptyList()
        }

        return runCatching {
            catalogRepository.getSeriesEpisodes(
                accessToken = accessToken,
                seriesItemId = itemId,
                season = null,
            )
        }.onSuccess { response ->
            Log.d(TAG, "Loaded series episodes for itemId=$itemId count=${response.items.size}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to load series episodes for itemId=$itemId", error)
        }.getOrNull()
            ?.items
            ?.mapNotNull(CrispyBackendClient.ClientMediaCard::toMediaVideo)
            .orEmpty()
    }

    suspend fun loadRatings(
        itemId: String,
    ): DetailsRatingsLoadResult {
        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        val profileId = backendContext?.profileId

        val titleRatings =
            accessToken?.takeIf { !profileId.isNullOrBlank() }?.let {
                runCatching {
                    catalogRepository.getTitleRatings(
                        accessToken = it,
                        profileId = checkNotNull(profileId),
                        itemId = itemId,
                    )
                }.getOrNull()
            }

        return DetailsRatingsLoadResult(titleRatings = titleRatings)
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
        season: Int,
        details: MediaDetails,
    ): DetailsSeasonEpisodesResult {
        val seriesItemId = details.itemId?.trim()?.takeIf { it.isNotBlank() }
        if (seriesItemId.isNullOrBlank()) {
            return DetailsSeasonEpisodesResult(errorMessage = "No episodes found for this season.")
        }

        val backendContext = runCatching { backendContextResolver.resolve() }.getOrNull()
        val session = runCatching { sessionRepository.ensureValidSession() }.getOrNull()
        val accessToken = backendContext?.accessToken ?: session?.accessToken
        if (accessToken == null) {
            return DetailsSeasonEpisodesResult(errorMessage = "Sign in to load episodes.")
        }

        val response = runCatching {
            catalogRepository.getSeriesEpisodes(
                accessToken = accessToken,
                seriesItemId = seriesItemId,
                season = season,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to load episodes for itemId=$seriesItemId season=$season", error)
        }.getOrNull()

        val videos = response
            ?.items
            ?.mapNotNull(CrispyBackendClient.ClientMediaCard::toMediaVideo)
            .orEmpty()
        if (videos.isEmpty()) {
            return DetailsSeasonEpisodesResult(errorMessage = "No episodes found for this season.")
        }

        return DetailsSeasonEpisodesResult(
            videos = videos,
            episodeWatchStates = resolveEpisodeWatchStates(details, videos),
            effectiveSeasonNumber = season,
            includedSeasonNumbers = emptyList(),
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
        itemId: String,
        requestedMediaType: MetadataLabMediaType,
    ): String {
        return "${requestedMediaType.name.lowercase(Locale.US)}:${itemId.trim()}"
    }

    fun loadCachedAiInsights(
        itemId: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? {
        return aiRepository.loadCached(itemId, locale)
    }

    suspend fun generateAiInsights(
        itemId: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult {
        return aiRepository.generate(itemId, locale)
    }

    suspend fun loadStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        onProvidersResolved: (List<StreamProviderDescriptor>) -> Unit,
        onProviderResult: (ProviderStreamsResult) -> Unit,
    ): List<ProviderStreamsResult> {
        return streamResolver.resolve(
            target = StreamLookupTarget(mediaType = mediaType, lookupId = lookupId),
            onProvidersResolved = onProvidersResolved,
            onProviderResult = onProviderResult,
        )
    }

    suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        return streamResolver.loadProviderStreams(
            mediaType = mediaType,
            lookupId = lookupId,
            providerId = providerId,
        )
    }

    private companion object {
        private const val TAG = "DetailsUseCases"
    }
}
