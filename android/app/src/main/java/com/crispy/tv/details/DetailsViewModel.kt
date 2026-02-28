package com.crispy.tv.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.BuildConfig
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.ai.AiInsightsCacheStore
import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.ai.OpenRouterClient
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.TmdbImdbIdResolver
import com.crispy.tv.metadata.tmdb.TmdbEnrichment
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.settings.AiInsightsSettingsStore
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

data class DetailsUiState(
    val itemId: String,
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val tmdbIsLoading: Boolean = false,
    val tmdbEnrichment: TmdbEnrichment? = null,
    val statusMessage: String = "",
    val aiMode: AiInsightsMode = AiInsightsMode.ON_DEMAND,
    val aiConfigured: Boolean = false,
    val aiIsLoading: Boolean = false,
    val aiInsights: AiInsightsResult? = null,
    val aiStoryVisible: Boolean = false,
    val isWatched: Boolean = false,
    val isInWatchlist: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
    val isMutating: Boolean = false,
    val watchCta: WatchCta = WatchCta(),
    val continueVideoId: String? = null,
    val selectedSeason: Int? = null,
    val seasons: List<Int> = emptyList(),
    val seasonEpisodes: List<MediaVideo> = emptyList(),
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
) {
    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

data class StreamProviderUiState(
    val providerId: String,
    val providerName: String,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val attemptedUrl: String? = null,
)

data class StreamSelectorUiState(
    val visible: Boolean = false,
    val mediaType: MetadataLabMediaType? = null,
    val lookupId: String? = null,
    val selectedProviderId: String? = null,
    val providers: List<StreamProviderUiState> = emptyList(),
    val isLoading: Boolean = false,
) {
    val totalStreamCount: Int
        get() = providers.sumOf { provider -> provider.streams.size }
}

sealed interface DetailsNavigationEvent {
    data class OpenPlayer(
        val playbackUrl: String,
        val title: String,
        val identity: PlaybackIdentity,
    ) : DetailsNavigationEvent
}

class DetailsViewModel internal constructor(
    private val itemId: String,
    private val mediaType: String,
    private val watchHistoryService: WatchHistoryService,
    private val tmdbImdbIdResolver: TmdbImdbIdResolver,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
    private val aiSettingsStore: AiInsightsSettingsStore,
    private val aiRepository: AiInsightsRepository,
    private val addonStreamsService: AddonStreamsService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState(itemId = itemId))
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<DetailsNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<DetailsNavigationEvent> = _navigationEvents.asSharedFlow()

    private val requestedMediaType: MetadataLabMediaType =
        checkNotNull(mediaType.toMetadataLabMediaTypeOrNull()) { "Unsupported mediaType: $mediaType" }

    private var aiJob: Job? = null
    private var streamLoadJob: Job? = null
    private var episodesJob: Job? = null
    private val seasonEpisodesCache = mutableMapOf<Int, List<MediaVideo>>()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val aiSnapshot = aiSettingsStore.loadSnapshot()

            episodesJob?.cancel()
            seasonEpisodesCache.clear()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    tmdbIsLoading = true,
                    statusMessage = "Loading...",
                    aiMode = aiSnapshot.settings.mode,
                    aiConfigured = aiSnapshot.openRouterKey.isNotBlank(),
                    aiIsLoading = false,
                    aiInsights = null,
                    aiStoryVisible = false,
                    watchCta = WatchCta(),
                    continueVideoId = null,
                    seasons = emptyList(),
                    seasonEpisodes = emptyList(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                    streamSelector = StreamSelectorUiState(),
                )
            }

            val tmdbResult =
                withContext(Dispatchers.IO) {
                    tmdbEnrichmentRepository.load(rawId = itemId, mediaTypeHint = requestedMediaType)
                }

            val tmdbEnrichment = tmdbResult?.enrichment
            val tmdbFallbackDetails = tmdbResult?.fallbackDetails

            var mergedDetails: MediaDetails? = tmdbFallbackDetails

            mergedDetails =
                withContext(Dispatchers.IO) {
                    mergedDetails?.let { details ->
                        if (details.imdbId == null && tmdbEnrichment?.imdbId != null) {
                            details.copy(imdbId = tmdbEnrichment.imdbId)
                        } else {
                            details
                        }
                    }
                }

            val enrichedDetails =
                withContext(Dispatchers.IO) {
                    mergedDetails?.let { details ->
                        if (details.imdbId == null) ensureImdbId(details) else details
                    }
                }

            val providerState =
                withContext(Dispatchers.IO) {
                    resolveProviderState(enrichedDetails)
                }

            val (watchCta, continueVideoId) =
                withContext(Dispatchers.IO) {
                    resolveWatchCta(enrichedDetails, providerState, nowMs)
                }

            _uiState.update { state ->
                val details = enrichedDetails
                val isSeries = details?.mediaType?.trim()?.equals("series", ignoreCase = true) == true
                val seasonCount = (tmdbEnrichment?.titleDetails as? TmdbTvDetails)?.numberOfSeasons ?: 0
                val seasons = if (isSeries && seasonCount > 0) (1..seasonCount).toList() else emptyList()
                val selectedSeason =
                    when {
                        state.selectedSeason != null && state.selectedSeason in seasons -> state.selectedSeason
                        seasons.isNotEmpty() -> seasons.first()
                        else -> null
                    }

                val statusMessage =
                    when {
                        details != null -> ""
                        else -> "Unable to load details."
                    }
                state.copy(
                    isLoading = false,
                    tmdbIsLoading = false,
                    details = details,
                    tmdbEnrichment = tmdbEnrichment,
                    statusMessage = statusMessage,
                    isWatched = providerState.isWatched,
                    isInWatchlist = providerState.isInWatchlist,
                    isRated = providerState.isRated,
                    userRating = providerState.userRating,
                    watchCta = watchCta,
                    continueVideoId = continueVideoId,
                    seasons = seasons,
                    selectedSeason = selectedSeason,
                    seasonEpisodes = emptyList(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }

            val seasonToLoad = _uiState.value.selectedSeasonOrFirst
            if (enrichedDetails?.mediaType?.trim()?.equals("series", ignoreCase = true) == true && seasonToLoad != null) {
                loadEpisodesForSeason(seasonToLoad, force = true)
            }

            val tmdbId = tmdbEnrichment?.tmdbId
            val detailsForAi = enrichedDetails
            val aiMode = aiSnapshot.settings.mode
            val aiConfigured = aiSnapshot.openRouterKey.isNotBlank()

            if (tmdbId != null && detailsForAi != null && aiMode != AiInsightsMode.OFF) {
                val cached = withContext(Dispatchers.IO) { aiRepository.loadCached(tmdbId, requestedMediaType) }
                if (cached != null) {
                    _uiState.update { it.copy(aiInsights = cached) }
                } else if (aiMode == AiInsightsMode.ALWAYS && aiConfigured) {
                    startAiGeneration(
                        tmdbId = tmdbId,
                        details = detailsForAi,
                        reviews = tmdbEnrichment.reviews.orEmpty(),
                        showStory = false,
                        announce = false,
                    )
                }
            }
        }
    }

    fun onAiInsightsClick() {
        val state = uiState.value
        if (state.aiMode == AiInsightsMode.OFF) {
            _uiState.update { it.copy(statusMessage = "AI insights are off. Enable them in Settings > AI Insights.") }
            return
        }
        if (!state.aiConfigured) {
            _uiState.update { it.copy(statusMessage = "Add an OpenRouter key in Settings > AI Insights.") }
            return
        }

        val details = state.details
        val tmdbId = state.tmdbEnrichment?.tmdbId
        if (details == null || tmdbId == null) {
            _uiState.update { it.copy(statusMessage = "TMDB data isn't ready yet. Try again in a moment.") }
            return
        }

        val cachedOrLoaded = state.aiInsights
        if (cachedOrLoaded != null) {
            _uiState.update { it.copy(aiStoryVisible = true) }
            return
        }

        if (state.aiIsLoading) return

        startAiGeneration(
            tmdbId = tmdbId,
            details = details,
            reviews = state.tmdbEnrichment.reviews.orEmpty(),
            showStory = true,
            announce = true,
        )
    }

    fun dismissAiInsightsStory() {
        _uiState.update { it.copy(aiStoryVisible = false) }
    }

    private fun startAiGeneration(
        tmdbId: Int,
        details: MediaDetails,
        reviews: List<com.crispy.tv.metadata.tmdb.TmdbReview>,
        showStory: Boolean,
        announce: Boolean,
    ) {
        aiJob?.cancel()
        aiJob =
            viewModelScope.launch {
                if (announce) {
                    _uiState.update { it.copy(aiIsLoading = true, statusMessage = "Generating AI insights...") }
                } else {
                    _uiState.update { it.copy(aiIsLoading = true) }
                }

                runCatching {
                    withContext(Dispatchers.IO) {
                        aiRepository.generate(
                            tmdbId = tmdbId,
                            mediaType = requestedMediaType,
                            details = details,
                            reviews = reviews,
                        )
                    }
                }.onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            aiIsLoading = false,
                            aiInsights = result,
                            aiStoryVisible = showStory,
                            statusMessage = if (announce) "" else it.statusMessage,
                        )
                    }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            aiIsLoading = false,
                            statusMessage = e.message ?: "AI insights are unavailable right now.",
                        )
                    }
                }
            }
    }

    fun onSeasonSelected(season: Int) {
        val cached = seasonEpisodesCache[season]
        _uiState.update {
            it.copy(
                selectedSeason = season,
                seasonEpisodes = cached ?: emptyList(),
                episodesIsLoading = cached == null,
                episodesStatusMessage = "",
            )
        }
        if (cached == null) {
            loadEpisodesForSeason(season)
        }
    }

    fun onOpenStreamSelector() {
        val state = _uiState.value
        val details = state.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "Details are still loading.") }
            return
        }

        val target =
            state.continueVideoId
                ?.takeIf { it.isNotBlank() }
                ?.let { videoId ->
                    StreamLookupTarget(
                        mediaType = requestedMediaType,
                        lookupId = videoId,
                    )
                }
                ?: resolveStreamLookupTarget(
                    details = details,
                    selectedSeason = state.selectedSeasonOrFirst,
                    seasonEpisodes = state.seasonEpisodes,
                    fallbackMediaType = requestedMediaType,
                )

        openStreamSelectorWithTarget(target, blankIdMessage = "Unable to resolve stream lookup id for this title.")
    }

    private fun loadEpisodesForSeason(
        season: Int,
        force: Boolean = false,
    ) {
        val state = _uiState.value
        val details = state.details ?: return
        val tmdb = state.tmdbEnrichment ?: return
        if (details.mediaType.trim().equals("series", ignoreCase = true).not()) return
        if (tmdb.mediaType != MetadataLabMediaType.SERIES) return

        val cached = if (!force) seasonEpisodesCache[season] else null
        if (cached != null) {
            _uiState.update { it.copy(seasonEpisodes = cached, episodesIsLoading = false, episodesStatusMessage = "") }
            return
        }

        episodesJob?.cancel()
        _uiState.update { it.copy(episodesIsLoading = true, episodesStatusMessage = "", seasonEpisodes = emptyList()) }

        episodesJob =
            viewModelScope.launch {
                val episodes =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            tmdbEnrichmentRepository.loadSeasonEpisodes(tmdbId = tmdb.tmdbId, seasonNumber = season)
                        }
                    }.getOrElse {
                        _uiState.update { current ->
                            if (current.selectedSeasonOrFirst != season) current
                            else current.copy(
                                episodesIsLoading = false,
                                episodesStatusMessage = "Failed to load episodes.",
                            )
                        }
                        return@launch
                    }

                val videos =
                    episodes
                        .mapNotNull { ep ->
                            val lookupId = buildEpisodeLookupId(details = details, season = ep.seasonNumber, episode = ep.episodeNumber)
                                ?: return@mapNotNull null
                            MediaVideo(
                                id = lookupId,
                                title = ep.name,
                                season = ep.seasonNumber,
                                episode = ep.episodeNumber,
                                released = ep.airDate,
                                overview = ep.overview,
                                thumbnailUrl = ep.stillUrl,
                            )
                        }
                        .sortedWith(
                            compareBy<MediaVideo>({ it.episode ?: Int.MAX_VALUE }, { it.title.lowercase(Locale.US) }, { it.id })
                        )

                seasonEpisodesCache[season] = videos

                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else current.copy(
                        seasonEpisodes = videos,
                        episodesIsLoading = false,
                        episodesStatusMessage = if (videos.isEmpty()) "No episodes found." else "",
                    )
                }
            }
    }

    private fun buildEpisodeLookupId(
        details: MediaDetails,
        season: Int,
        episode: Int,
    ): String? {
        if (season <= 0 || episode <= 0) return null
        val base = details.imdbId?.trim().takeIf { !it.isNullOrBlank() }
            ?: details.id.trim().takeIf { it.isNotBlank() }
            ?: return null
        val canonicalBase = normalizeNuvioMediaId(base).contentId.trim()
        if (canonicalBase.isBlank()) return null
        return "$canonicalBase:$season:$episode"
    }

    fun onOpenStreamSelectorForEpisode(videoId: String) {
        val state = _uiState.value
        val details = state.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "Details are still loading.") }
            return
        }
        val target =
            StreamLookupTarget(
                mediaType = requestedMediaType,
                lookupId = videoId.trim(),
            )
        openStreamSelectorWithTarget(target, blankIdMessage = "This episode does not have a stream lookup id.")
    }

    private fun openStreamSelectorWithTarget(
        target: StreamLookupTarget,
        blankIdMessage: String,
    ) {
        if (target.lookupId.isBlank()) {
            _uiState.update { it.copy(statusMessage = blankIdMessage) }
            return
        }

        val current = _uiState.value.streamSelector
        if (
            current.lookupId == target.lookupId &&
            current.mediaType == target.mediaType &&
            current.providers.isNotEmpty()
        ) {
            _uiState.update {
                it.copy(
                    streamSelector = current.copy(visible = true),
                    statusMessage = "",
                )
            }
            return
        }

        streamLoadJob?.cancel()
        _uiState.update {
            it.copy(
                streamSelector =
                    StreamSelectorUiState(
                        visible = true,
                        mediaType = target.mediaType,
                        lookupId = target.lookupId,
                        isLoading = true,
                    ),
                statusMessage = "Fetching streams...",
            )
        }

        streamLoadJob =
            viewModelScope.launch {
                runCatching {
                    addonStreamsService.loadStreams(
                        mediaType = target.mediaType,
                        lookupId = target.lookupId,
                        onProvidersResolved = { providers ->
                            _uiState.update { previous ->
                                if (!previous.streamSelector.matchesTarget(target)) return@update previous
                                previous.copy(
                                    streamSelector =
                                        previous.streamSelector.copy(
                                            visible = true,
                                            mediaType = target.mediaType,
                                            lookupId = target.lookupId,
                                            providers = providers.map(StreamProviderDescriptor::toLoadingUiState),
                                            isLoading = providers.isNotEmpty(),
                                        ),
                                    statusMessage =
                                        if (providers.isEmpty()) {
                                            "No stream providers are available for this title."
                                        } else {
                                            "Fetching streams..."
                                        },
                                )
                            }
                        },
                        onProviderResult = { result ->
                            _uiState.update { previous ->
                                if (!previous.streamSelector.matchesTarget(target)) return@update previous
                                val updatedProviders = previous.streamSelector.providers.applyProviderResult(result)
                                val stillLoading = updatedProviders.any { provider -> provider.isLoading }

                                previous.copy(
                                    streamSelector =
                                        previous.streamSelector.copy(
                                            providers = updatedProviders,
                                            isLoading = stillLoading,
                                        ),
                                    statusMessage = buildStreamStatusMessage(updatedProviders, isLoading = stillLoading),
                                )
                            }
                        },
                    )
                }.onSuccess { results ->
                    _uiState.update { previous ->
                        if (!previous.streamSelector.matchesTarget(target)) return@update previous
                        val finalizedProviders =
                            previous.streamSelector.providers
                                .finalizeFrom(results)
                                .ifEmpty { results.map { result -> result.toUiState() } }

                        previous.copy(
                            streamSelector =
                                previous.streamSelector.copy(
                                    visible = true,
                                    mediaType = target.mediaType,
                                    lookupId = target.lookupId,
                                    providers = finalizedProviders,
                                    isLoading = false,
                                ),
                            statusMessage = buildStreamStatusMessage(finalizedProviders, isLoading = false),
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    _uiState.update { previous ->
                        previous.copy(
                            streamSelector =
                                previous.streamSelector.copy(
                                    providers =
                                        previous.streamSelector.providers.map { provider ->
                                            provider.copy(isLoading = false)
                                        },
                                    isLoading = false,
                                ),
                            statusMessage = error.message ?: "Failed to fetch streams.",
                        )
                    }
                }
            }
    }

    fun onDismissStreamSelector() {
        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(visible = false),
                statusMessage = "",
            )
        }
    }

    fun onProviderSelected(providerId: String?) {
        _uiState.update { state ->
            state.copy(
                streamSelector =
                    state.streamSelector.copy(
                        selectedProviderId = providerId?.trim()?.takeIf { it.isNotBlank() },
                    )
            )
        }
    }

    fun onRetryProvider(providerId: String) {
        val normalizedProviderId = providerId.trim()
        if (normalizedProviderId.isBlank()) return

        val selectorState = _uiState.value.streamSelector
        val mediaType = selectorState.mediaType ?: return
        val lookupId = selectorState.lookupId ?: return

        _uiState.update { state ->
            val providers =
                state.streamSelector.providers.map { provider ->
                    if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                        provider.copy(
                            isLoading = true,
                            errorMessage = null,
                        )
                    } else {
                        provider
                    }
                }
            state.copy(
                streamSelector = state.streamSelector.copy(providers = providers),
                statusMessage = "Retrying ${providers.firstOrNull { it.providerId.equals(normalizedProviderId, true) }?.providerName ?: "provider"}...",
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    addonStreamsService.loadProviderStreams(
                        mediaType = mediaType,
                        lookupId = lookupId,
                        providerId = normalizedProviderId,
                    )
                }
            }.onSuccess { result ->
                _uiState.update { state ->
                    val providers =
                        state.streamSelector.providers.map { provider ->
                            if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                                val updated = result?.toUiState()
                                if (updated != null) {
                                    updated
                                } else {
                                    provider.copy(
                                        isLoading = false,
                                        errorMessage = "Provider no longer available.",
                                        streams = emptyList(),
                                        attemptedUrl = null,
                                    )
                                }
                            } else {
                                provider
                            }
                        }

                    val updatedProvider =
                        providers.firstOrNull { provider ->
                            provider.providerId.equals(normalizedProviderId, ignoreCase = true)
                        }
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage =
                            when {
                                updatedProvider == null -> "Provider no longer available."
                                updatedProvider.errorMessage != null -> updatedProvider.errorMessage
                                updatedProvider.streams.isEmpty() -> "No streams returned from ${updatedProvider.providerName}."
                                else -> "Updated ${updatedProvider.providerName}."
                            },
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update { state ->
                    val providers =
                        state.streamSelector.providers.map { provider ->
                            if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                                provider.copy(
                                    isLoading = false,
                                    errorMessage = error.message ?: "Failed to reload provider.",
                                )
                            } else {
                                provider
                            }
                        }
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage = error.message ?: "Failed to reload provider.",
                    )
                }
            }
        }
    }

    fun onStreamSelected(stream: AddonStream) {
        val playbackUrl = stream.playbackUrl
        if (playbackUrl.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "Selected stream has no playable URL.") }
            return
        }

        val title =
            stream.name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: _uiState.value.details?.title
                ?: "Player"

        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(visible = false),
                statusMessage = "",
            )
        }

        val initialDetails = _uiState.value.details
        val lookupId = _uiState.value.streamSelector.lookupId

        viewModelScope.launch {
            val details = initialDetails
                ?: return@launch

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }

            val resolvedMediaType = enriched.mediaType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
            val normalizedLookupId =
                normalizeNuvioMediaId(
                    lookupId
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: enriched.id,
                )

            val season = if (resolvedMediaType == MetadataLabMediaType.SERIES) normalizedLookupId.season else null
            val episode = if (resolvedMediaType == MetadataLabMediaType.SERIES) normalizedLookupId.episode else null

            val yearInt = enriched.year?.trim()?.toIntOrNull()
            val tmdbId = extractTmdbIdOrNull(enriched.id) ?: extractTmdbIdOrNull(lookupId)

            val identity =
                PlaybackIdentity(
                    imdbId = enriched.imdbId,
                    tmdbId = tmdbId,
                    contentType = resolvedMediaType,
                    season = season,
                    episode = episode,
                    title = title,
                    year = yearInt,
                    showTitle = if (resolvedMediaType == MetadataLabMediaType.SERIES) enriched.title else null,
                    showYear = if (resolvedMediaType == MetadataLabMediaType.SERIES) yearInt else null,
                )

            _navigationEvents.tryEmit(
                DetailsNavigationEvent.OpenPlayer(
                    playbackUrl = playbackUrl,
                    title = title,
                    identity = identity,
                )
            )
        }
    }

    private fun extractTmdbIdOrNull(rawId: String?): Int? {
        val value = rawId?.trim().orEmpty()
        if (value.isBlank()) return null
        val match = TMDB_ID_REGEX.find(value) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    fun toggleWatchlist() {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        viewModelScope.launch {
            val source =
                withContext(Dispatchers.IO) {
                    preferredWatchProvider(watchHistoryService.authState())
                }
            if (source == WatchProvider.LOCAL) {
                _uiState.update { it.copy(statusMessage = "Connect Trakt or Simkl to use watchlist.") }
                return@launch
            }

            val desired = !_uiState.value.isInWatchlist
            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.tv.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    watchHistoryService.setInWatchlist(request, desired, source)
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> false
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isInWatchlist = if (success) desired else it.isInWatchlist
                )
            }
        }
    }

    fun toggleWatched() {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        if (mediaType == MetadataLabMediaType.SERIES) {
            _uiState.update { it.copy(statusMessage = "Marking an entire series as watched isn't supported yet. Mark episodes from the episode list.") }
            return
        }
        viewModelScope.launch {
            val source =
                withContext(Dispatchers.IO) {
                    preferredWatchProvider(watchHistoryService.authState())
                }

            val desired = !_uiState.value.isWatched
            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.tv.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    if (desired) {
                        watchHistoryService.markWatched(request, source)
                    } else {
                        watchHistoryService.unmarkWatched(request, source)
                    }
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> true
                }
            _uiState.update {
                val nextIsWatched = if (success) desired else it.isWatched
                val nextCta =
                    when {
                        it.watchCta.kind == WatchCtaKind.CONTINUE -> it.watchCta
                        nextIsWatched ->
                            WatchCta(
                                kind = WatchCtaKind.REWATCH,
                                label = "Rewatch",
                                icon = WatchCtaIcon.REPLAY,
                                remainingMinutes = null,
                                lastWatchedAtEpochMs = System.currentTimeMillis(),
                            )
                        else ->
                            WatchCta(
                                kind = WatchCtaKind.WATCH,
                                label = "Watch now",
                                icon = WatchCtaIcon.PLAY,
                                remainingMinutes = parseRuntimeMinutes(it.details?.runtime),
                                lastWatchedAtEpochMs = null,
                            )
                    }
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isWatched = nextIsWatched,
                    watchCta = nextCta,
                )
            }
        }
    }

    fun setRating(rating: Int?) {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        viewModelScope.launch {
            val source =
                withContext(Dispatchers.IO) {
                    preferredWatchProvider(watchHistoryService.authState())
                }
            if (source == WatchProvider.LOCAL) {
                _uiState.update { it.copy(statusMessage = "Connect Trakt or Simkl to rate.") }
                return@launch
            }
            if (source == WatchProvider.SIMKL && rating == null) {
                _uiState.update { it.copy(statusMessage = "Removing ratings is not supported for Simkl yet.") }
                return@launch
            }

            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.tv.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    watchHistoryService.setRating(request, rating, source)
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> false
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isRated = if (success) rating != null else it.isRated,
                    userRating = if (success) rating else it.userRating
                )
            }
        }
    }

    private suspend fun ensureImdbId(details: MediaDetails): MediaDetails {
        val fromId = details.id.trim().takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        val existing = fromId ?: fromField
        if (existing != null) {
            return if (fromField == existing) details else details.copy(imdbId = existing)
        }

        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
        val resolved = tmdbImdbIdResolver.resolveImdbId(details.id, mediaType) ?: return details
        return details.copy(imdbId = resolved)
    }

    private data class ProviderState(
        val isWatched: Boolean,
        val watchedAtEpochMs: Long?,
        val isInWatchlist: Boolean,
        val isRated: Boolean,
        val userRating: Int?
    )

    private suspend fun resolveWatchCta(
        details: MediaDetails?,
        providerState: ProviderState,
        nowMs: Long,
    ): Pair<WatchCta, String?> {
        if (details == null) return Pair(WatchCta(), null)

        val isSeries = requestedMediaType == MetadataLabMediaType.SERIES
        val expectedType = requestedMediaType

        val continueEntry = resolveContinueWatchingEntry(details, expectedType, nowMs)
        val canContinue =
            continueEntry != null &&
                !continueEntry.isUpNextPlaceholder &&
                continueEntry.progressPercent > CTA_CONTINUE_MIN_PROGRESS_PERCENT &&
                continueEntry.progressPercent < CTA_CONTINUE_COMPLETION_PERCENT

        if (canContinue) {
            val label =
                if (isSeries) {
                    val season = continueEntry.season
                    val episode = continueEntry.episode
                    if (season != null && episode != null) {
                        "Continue (S$season E$episode)"
                    } else {
                        "Continue"
                    }
                } else {
                    val progress = continueEntry.progressPercent
                    "Resume from ${progress.roundToInt()}%"
                }

            val remainingMinutes =
                parseRuntimeMinutes(details.runtime)?.let { runtimeMinutes ->
                    val progress = continueEntry.progressPercent
                    val remaining = runtimeMinutes.toDouble() * (1.0 - (progress / 100.0))
                    remaining.roundToInt().coerceAtLeast(0)
                }

            val continueVideoId =
                if (isSeries && continueEntry.season != null && continueEntry.episode != null) {
                    buildEpisodeLookupId(
                        details = details,
                        season = continueEntry.season,
                        episode = continueEntry.episode,
                    )
                } else {
                    null
                }

            return Pair(
                WatchCta(
                    kind = WatchCtaKind.CONTINUE,
                    label = label,
                    icon = WatchCtaIcon.PLAY,
                    remainingMinutes = remainingMinutes,
                    lastWatchedAtEpochMs = null,
                ),
                continueVideoId,
            )
        }

        if (!isSeries && providerState.isWatched) {
            return Pair(
                WatchCta(
                    kind = WatchCtaKind.REWATCH,
                    label = "Rewatch",
                    icon = WatchCtaIcon.REPLAY,
                    remainingMinutes = null,
                    lastWatchedAtEpochMs = providerState.watchedAtEpochMs,
                ),
                null,
            )
        }

        return Pair(
            WatchCta(
                kind = WatchCtaKind.WATCH,
                label = "Watch now",
                icon = WatchCtaIcon.PLAY,
                remainingMinutes = parseRuntimeMinutes(details.runtime),
                lastWatchedAtEpochMs = null,
            ),
            null,
        )
    }

    private suspend fun resolveContinueWatchingEntry(
        details: MediaDetails,
        expectedType: MetadataLabMediaType,
        nowMs: Long,
    ): ContinueWatchingEntry? {
        val source = preferredWatchProvider(watchHistoryService.authState())

        val normalizedTargetId = normalizeNuvioMediaId(details.id).contentId.lowercase(Locale.US)
        val normalizedImdbId =
            details.imdbId?.let { imdb ->
                normalizeNuvioMediaId(imdb).contentId.lowercase(Locale.US)
            }

        fun matchesItemContent(itemContentId: String): Boolean {
            return matchesContentId(itemContentId, normalizedTargetId) ||
                (normalizedImdbId != null && matchesContentId(itemContentId, normalizedImdbId))
        }

        val cached = watchHistoryService.getCachedContinueWatching(limit = 50, nowMs = nowMs, source = source)
        val snapshot =
            if (cached.entries.isNotEmpty()) {
                cached
            } else {
                watchHistoryService.listContinueWatching(limit = 50, nowMs = nowMs, source = source)
            }

        return snapshot.entries
            .asSequence()
            .filter { entry ->
                matchesItemContent(entry.contentId) && matchesMediaType(expectedType, entry.contentType)
            }
            .maxByOrNull { it.lastUpdatedEpochMs }
    }

    private suspend fun resolveProviderState(details: MediaDetails?): ProviderState {
        val authState = watchHistoryService.authState()
        val source = preferredWatchProvider(authState)
        val normalizedTargetId = normalizeNuvioMediaId(details?.id ?: itemId).contentId.lowercase(Locale.US)
        val normalizedImdbId =
            details?.imdbId?.let { imdb ->
                normalizeNuvioMediaId(imdb).contentId.lowercase(Locale.US)
            }
        val expectedType = requestedMediaType

        fun matchesItemContent(itemContentId: String): Boolean {
            return matchesContentId(itemContentId, normalizedTargetId) ||
                (normalizedImdbId != null && matchesContentId(itemContentId, normalizedImdbId))
        }

        return when (source) {
            WatchProvider.LOCAL -> {
                val local = watchHistoryService.listLocalHistory(limit = 250)
                val watchedEntry =
                    local.entries
                        .asSequence()
                        .filter { entry ->
                            matchesItemContent(entry.contentId) && matchesMediaType(expectedType, entry.contentType)
                        }
                        .maxByOrNull { it.watchedAtEpochMs }

                val watched = watchedEntry != null
                ProviderState(
                    isWatched = watched,
                    watchedAtEpochMs = watchedEntry?.watchedAtEpochMs,
                    isInWatchlist = false,
                    isRated = false,
                    userRating = null
                )
            }

            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> {
                val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 250, source = source)
                val snapshot =
                    if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                        cached
                    } else {
                        watchHistoryService.listProviderLibrary(limitPerFolder = 250, source = source)
                    }

                val watchedItem =
                    snapshot.items.firstOrNull { item ->
                        item.provider == source &&
                            source.isWatchedFolder(item.folderId) &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val watchedAtEpochMs = watchedItem?.addedAtEpochMs
                val watched = watchedItem != null

                val watchlistFolderId =
                    when (source) {
                        WatchProvider.TRAKT -> "watchlist"
                        WatchProvider.SIMKL -> "plantowatch"
                        WatchProvider.LOCAL -> ""
                    }
                val inWatchlist =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == watchlistFolderId &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val isRated =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == "ratings" &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                ProviderState(
                    isWatched = watched,
                    watchedAtEpochMs = watchedAtEpochMs,
                    isInWatchlist = inWatchlist,
                    isRated = isRated,
                    userRating = null
                )
            }
        }
    }

    private fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
        return expected == null || expected == actual
    }

    // resolveWatchedState removed in favor of resolveProviderState.

    private fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
        return normalizeNuvioMediaId(candidate).contentId.lowercase(Locale.US) == targetNormalizedId
    }

    companion object {
        private const val CTA_CONTINUE_MIN_PROGRESS_PERCENT = 2.0
        private const val CTA_CONTINUE_COMPLETION_PERCENT = 85.0

        private val TMDB_ID_REGEX = Regex("\\btmdb:(?:movie:|show:|tv:)?(\\d+)")

        fun factory(context: Context, itemId: String, mediaType: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val httpClient = AppHttp.client(appContext)
                    val tmdbImdbIdResolver =
                        TmdbImdbIdResolver(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            httpClient = httpClient
                        )
                    val tmdbEnrichmentRepository =
                        TmdbEnrichmentRepository(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            httpClient = httpClient
                        )
                    val addonStreamsService =
                        AddonStreamsService(
                            context = appContext,
                            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                            httpClient = httpClient,
                        )

                    val aiSettingsStore = AiInsightsSettingsStore(appContext)
                    val aiRepository =
                        AiInsightsRepository(
                            openRouterClient = OpenRouterClient(httpClient = httpClient, xTitle = "Crispy Rewrite Android"),
                            settingsStore = aiSettingsStore,
                            cacheStore = AiInsightsCacheStore(appContext),
                        )
                    return DetailsViewModel(
                        itemId = itemId,
                        mediaType = mediaType,
                        watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext),
                        tmdbImdbIdResolver = tmdbImdbIdResolver,
                        tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                        aiSettingsStore = aiSettingsStore,
                        aiRepository = aiRepository,
                        addonStreamsService = addonStreamsService,
                    ) as T
                }
            }
        }
    }
}

private fun preferredWatchProvider(authState: WatchProviderAuthState): WatchProvider {
    return when {
        authState.traktAuthenticated -> WatchProvider.TRAKT
        authState.simklAuthenticated -> WatchProvider.SIMKL
        else -> WatchProvider.LOCAL
    }
}

private data class StreamLookupTarget(
    val mediaType: MetadataLabMediaType,
    val lookupId: String,
)

private fun resolveStreamLookupTarget(
    details: MediaDetails,
    selectedSeason: Int?,
    seasonEpisodes: List<MediaVideo>,
    fallbackMediaType: MetadataLabMediaType,
): StreamLookupTarget {
    val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: fallbackMediaType
    val lookupId =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> details.imdbId?.trim()?.takeIf { it.isNotBlank() } ?: details.id.trim()
            MetadataLabMediaType.SERIES -> {
                val fromLoadedEpisodes = seasonEpisodes.firstOrNull { it.id.trim().isNotBlank() }?.id?.trim()
                if (fromLoadedEpisodes != null) {
                    fromLoadedEpisodes
                } else {
                    val season = selectedSeason ?: seasonEpisodes.firstOrNull()?.season ?: 1
                    val base = details.imdbId?.trim()?.takeIf { it.isNotBlank() } ?: details.id.trim()
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

private fun ProviderStreamsResult.toUiState(): StreamProviderUiState {
    return StreamProviderUiState(
        providerId = providerId,
        providerName = providerName,
        isLoading = false,
        errorMessage = errorMessage,
        streams = streams,
        attemptedUrl = attemptedUrl,
    )
}

private fun StreamProviderDescriptor.toLoadingUiState(): StreamProviderUiState {
    return StreamProviderUiState(
        providerId = providerId,
        providerName = providerName,
        isLoading = true,
    )
}

private fun List<StreamProviderUiState>.applyProviderResult(result: ProviderStreamsResult): List<StreamProviderUiState> {
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

private fun List<StreamProviderUiState>.finalizeFrom(results: List<ProviderStreamsResult>): List<StreamProviderUiState> {
    if (isEmpty()) return emptyList()
    val byProviderId = results.associateBy { result -> result.providerId.lowercase(Locale.US) }

    return map { provider ->
        byProviderId[provider.providerId.lowercase(Locale.US)]?.toUiState() ?: provider.copy(isLoading = false)
    }
}

private fun StreamSelectorUiState.matchesTarget(target: StreamLookupTarget): Boolean {
    return mediaType == target.mediaType && lookupId == target.lookupId
}

private fun buildStreamStatusMessage(
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

private fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
    return when (this?.lowercase(Locale.US)) {
        "movie" -> MetadataLabMediaType.MOVIE
        "series", "show", "tv" -> MetadataLabMediaType.SERIES
        else -> null
    }
}

private fun WatchProvider.isWatchedFolder(folderId: String): Boolean {
    val normalized = folderId.trim().lowercase(Locale.US)
    return when (this) {
        WatchProvider.LOCAL -> false
        WatchProvider.TRAKT -> normalized == "watched"
        WatchProvider.SIMKL -> normalized.startsWith("completed")
    }
}
