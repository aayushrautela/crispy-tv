package com.crispy.tv.details

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.BuildConfig
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.TmdbImdbIdResolver
import com.crispy.tv.metadata.omdb.OmdbDetails
import com.crispy.tv.metadata.omdb.OmdbRepository
import com.crispy.tv.metadata.omdb.OmdbRepositoryProvider
import com.crispy.tv.metadata.tmdb.TmdbEnrichment
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.playerui.PlayerEpisodeSnapshot
import com.crispy.tv.playerui.PlayerLaunchSnapshot
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.settings.AiInsightsSettingsStore
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import com.crispy.tv.metadata.mergeEnhancements
import com.crispy.tv.metadata.primaryTmdbId
import com.crispy.tv.metadata.providerBaseLookupId
import com.crispy.tv.metadata.seasonNumbers
import com.crispy.tv.metadata.toMediaDetails
import com.crispy.tv.metadata.toMediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.metadata.tmdbLookupId
import com.crispy.tv.playback.StreamLookupTarget
import com.crispy.tv.playback.buildEpisodeLookupId
import com.crispy.tv.playback.buildPlayerSubtitle
import com.crispy.tv.playback.buildStreamStatusMessage
import com.crispy.tv.playback.findEpisodeForLookupId
import com.crispy.tv.playback.resolveStreamLookupTarget
import com.crispy.tv.playback.matchesTarget
import com.crispy.tv.playback.toUiState
import com.crispy.tv.playback.toLoadingUiState
import com.crispy.tv.playback.applyProviderResult
import com.crispy.tv.playback.finalizeFrom
import com.crispy.tv.watchhistory.addEpisodeKey
import com.crispy.tv.watchhistory.episodeWatchKeyCandidates
import com.crispy.tv.watchhistory.isWatchedFolder
import com.crispy.tv.watchhistory.matchesContentId
import com.crispy.tv.watchhistory.matchesMediaType
import com.crispy.tv.watchhistory.preferredWatchProvider
import com.crispy.tv.watchhistory.toEpisodeWatchKey
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

class DetailsViewModel internal constructor(
    private val itemId: String,
    private val mediaType: String,
    private val supabaseAccountClient: SupabaseAccountClient,
    private val backendClient: CrispyBackendClient,
    private val watchHistoryService: WatchHistoryService,
    private val tmdbImdbIdResolver: TmdbImdbIdResolver,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
    private val omdbRepository: OmdbRepository,
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
    private var omdbJob: Job? = null
    private val seasonEpisodesCache = mutableMapOf<Int, List<MediaVideo>>()
    private var resolvedTmdbId: Int? = null
    private val episodeWatchStateResolver = EpisodeWatchStateResolver(watchHistoryService)
    private val watchCtaResolver = WatchCtaResolver(watchHistoryService, requestedMediaType, tmdbImdbIdResolver)
    private var pendingEpisodeNavigation: PendingEpisodeNavigation? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            val aiSnapshot = aiSettingsStore.loadSnapshot()

            episodesJob?.cancel()
            omdbJob?.cancel()
            seasonEpisodesCache.clear()
            resolvedTmdbId = null
            episodeWatchStateResolver.clearCache()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    tmdbIsLoading = true,
                    statusMessage = "Loading...",
                    omdbIsLoading = false,
                    omdbDetails = null,
                    aiMode = aiSnapshot.settings.mode,
                    aiConfigured = aiSnapshot.openRouterKey.isNotBlank(),
                    aiIsLoading = false,
                    aiInsights = null,
                    aiStoryVisible = false,
                    watchCta = WatchCta(),
                    continueVideoId = null,
                    seasons = emptyList(),
                    seasonEpisodes = emptyList(),
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                    streamSelector = StreamSelectorUiState(),
                )
            }

            val session =
                withContext(Dispatchers.IO) {
                    runCatching { supabaseAccountClient.ensureValidSession() }.getOrNull()
                }

            val backendDetail =
                withContext(Dispatchers.IO) {
                    session?.let {
                        runCatching {
                            backendClient.getMetadataTitleDetail(accessToken = it.accessToken, id = itemId)
                        }.getOrNull()
                    }
                }

            val backendDetails = backendDetail?.toMediaDetails()
            var mergedDetails = backendDetails
            if (backendDetails != null) {
                mergedDetails =
                    withContext(Dispatchers.IO) {
                        if (backendDetails.imdbId == null) watchCtaResolver.ensureImdbId(backendDetails) else backendDetails
                    }
            }

            val tmdbLookupId = mergedDetails?.tmdbLookupId()
            val tmdbResult =
                withContext(Dispatchers.IO) {
                    tmdbLookupId?.let {
                        tmdbEnrichmentRepository.load(rawId = it, mediaTypeHint = requestedMediaType)
                    }
                }

            val tmdbEnrichment = tmdbResult?.enrichment
            val tmdbFallbackDetails = tmdbResult?.fallbackDetails
            mergedDetails =
                when {
                    mergedDetails != null && tmdbFallbackDetails != null -> mergedDetails.mergeEnhancements(
                        enhancement = tmdbFallbackDetails,
                        resolvedImdbId = tmdbEnrichment?.imdbId,
                    )
                    mergedDetails != null -> mergedDetails
                    else -> tmdbFallbackDetails
                }

            resolvedTmdbId = mergedDetails?.primaryTmdbId() ?: tmdbEnrichment?.tmdbId

            val enrichedDetails =
                withContext(Dispatchers.IO) {
                    mergedDetails?.let { details ->
                        if (details.imdbId == null) watchCtaResolver.ensureImdbId(details) else details
                    }
                }

            val providerState =
                withContext(Dispatchers.IO) {
                    watchCtaResolver.resolveProviderState(enrichedDetails, itemId)
                }

            val (watchCta, continueVideoId) =
                withContext(Dispatchers.IO) {
                    watchCtaResolver.resolveWatchCta(enrichedDetails, providerState, nowMs)
                }

            _uiState.update { state ->
                val details = enrichedDetails
                val isSeries = details?.mediaType?.trim()?.equals("series", ignoreCase = true) == true
                val backendSeasons = backendDetail?.seasonNumbers().orEmpty()
                val fallbackSeasonCount = (tmdbEnrichment?.titleDetails as? TmdbTvDetails)?.numberOfSeasons ?: 0
                val seasonCount = backendDetail?.item?.seasonCount ?: fallbackSeasonCount
                val seasons =
                    when {
                        !isSeries -> emptyList()
                        backendSeasons.isNotEmpty() -> backendSeasons
                        seasonCount > 0 -> (1..seasonCount).toList()
                        else -> emptyList()
                    }
                val pendingSeason = pendingEpisodeNavigation?.season
                val selectedSeason =
                    when {
                        pendingSeason != null && pendingSeason in seasons -> pendingSeason
                        state.selectedSeason != null && state.selectedSeason in seasons -> state.selectedSeason
                        seasons.isNotEmpty() -> seasons.first()
                        else -> null
                    }

                val statusMessage =
                    when {
                        details != null -> ""
                        session == null -> "Sign in to load details."
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
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }

            loadOmdbRatings(enrichedDetails)

            val seasonToLoad = _uiState.value.selectedSeasonOrFirst
            if (enrichedDetails?.mediaType?.trim()?.equals("series", ignoreCase = true) == true && seasonToLoad != null) {
                loadEpisodesForSeason(seasonToLoad, force = true)
            }

            val tmdbId = resolvedTmdbId ?: tmdbEnrichment?.tmdbId
            val detailsForAi = enrichedDetails
            val aiMode = aiSnapshot.settings.mode
            val aiConfigured = aiSnapshot.openRouterKey.isNotBlank()
            val aiLocale = Locale.getDefault()

            if (tmdbId != null && detailsForAi != null && aiMode != AiInsightsMode.OFF) {
                val cached = withContext(Dispatchers.IO) { aiRepository.loadCached(tmdbId, requestedMediaType, aiLocale) }
                if (cached != null) {
                    _uiState.update { it.copy(aiInsights = cached) }
                } else if (aiMode == AiInsightsMode.ALWAYS && aiConfigured) {
                    startAiGeneration(
                        tmdbId = tmdbId,
                        locale = aiLocale,
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
        val tmdbId = resolvedTmdbId ?: state.tmdbEnrichment?.tmdbId ?: details?.primaryTmdbId()
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
            locale = Locale.getDefault(),
            showStory = true,
            announce = true,
        )
    }

    fun dismissAiInsightsStory() {
        _uiState.update { it.copy(aiStoryVisible = false) }
    }

    private fun startAiGeneration(
        tmdbId: Int,
        locale: Locale,
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
                            locale = locale,
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
                episodeWatchStates = emptyMap(),
                episodesIsLoading = cached == null,
                episodesStatusMessage = "",
            )
        }
        if (cached == null) {
            loadEpisodesForSeason(season)
        } else {
            loadEpisodeWatchStatesForSeason(season, cached)
        }
    }

    fun requestEpisodeNavigation(
        initialSeason: Int?,
        initialEpisode: Int?,
        autoOpenEpisode: Boolean,
    ) {
        if (initialSeason == null && initialEpisode == null) return
        pendingEpisodeNavigation =
            PendingEpisodeNavigation(
                season = initialSeason,
                episode = initialEpisode,
                autoOpenEpisode = autoOpenEpisode,
            )

        val currentState = _uiState.value
        val targetSeason = initialSeason ?: currentState.selectedSeasonOrFirst ?: return
        if (targetSeason != currentState.selectedSeasonOrFirst && targetSeason in currentState.seasons) {
            onSeasonSelected(targetSeason)
            return
        }
        maybeConsumePendingEpisodeNavigation(currentState.seasonEpisodes)
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

        val headerEpisode = findEpisodeForLookupId(target.lookupId, state.seasonEpisodes)

        openStreamSelectorWithTarget(
            target = target,
            headerEpisode = headerEpisode,
            blankIdMessage = "Unable to resolve stream lookup id for this title.",
        )
    }

    private fun loadEpisodesForSeason(
        season: Int,
        force: Boolean = false,
    ) {
        val state = _uiState.value
        val details = state.details ?: return
        if (details.mediaType.trim().equals("series", ignoreCase = true).not()) return

        val cached = if (!force) seasonEpisodesCache[season] else null
        if (cached != null) {
            _uiState.update {
                it.copy(
                    seasonEpisodes = cached,
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }
            loadEpisodeWatchStatesForSeason(season, cached)
            return
        }

        episodesJob?.cancel()
        _uiState.update {
            it.copy(
                episodesIsLoading = true,
                episodesStatusMessage = "",
                seasonEpisodes = emptyList(),
                episodeWatchStates = emptyMap(),
            )
        }

        episodesJob =
            viewModelScope.launch {
                val session =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            supabaseAccountClient.ensureValidSession()
                        }
                    }.getOrElse {
                        _uiState.update { current ->
                            if (current.selectedSeasonOrFirst != season) current
                            else current.copy(
                                episodesIsLoading = false,
                                episodesStatusMessage = "Failed to refresh session.",
                            )
                        }
                        return@launch
                    }

                if (session == null) {
                    _uiState.update { current ->
                        if (current.selectedSeasonOrFirst != season) current
                        else current.copy(
                            episodesIsLoading = false,
                            episodesStatusMessage = "Sign in to load episodes.",
                        )
                    }
                    return@launch
                }

                val episodeResponse =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            backendClient.listMetadataEpisodes(
                                accessToken = session.accessToken,
                                id = details.id,
                                seasonNumber = season,
                            )
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
                    episodeResponse.episodes
                        .mapNotNull { ep ->
                            ep.toMediaVideo()
                        }
                        .sortedWith(
                            compareBy<MediaVideo>({ it.episode ?: Int.MAX_VALUE }, { it.title.lowercase(Locale.US) }, { it.id })
                        )

                seasonEpisodesCache[season] = videos
                val episodeWatchStates = withContext(Dispatchers.IO) { episodeWatchStateResolver.resolve(details, videos, resolvedTmdbId) }

                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else current.copy(
                        seasons =
                            when {
                                episodeResponse.includedSeasonNumbers.isNotEmpty() -> episodeResponse.includedSeasonNumbers.sorted()
                                else -> current.seasons
                            },
                        seasonEpisodes = videos,
                        episodeWatchStates = episodeWatchStates,
                        episodesIsLoading = false,
                        episodesStatusMessage = if (videos.isEmpty()) "No episodes found." else "",
                    )
                }
                maybeConsumePendingEpisodeNavigation(videos)
            }
    }

    private fun loadEpisodeWatchStatesForSeason(
        season: Int,
        videos: List<MediaVideo>,
    ) {
        episodesJob?.cancel()
        episodesJob =
            viewModelScope.launch {
                val details = _uiState.value.details ?: return@launch
                val episodeWatchStates = withContext(Dispatchers.IO) { episodeWatchStateResolver.resolve(details, videos, resolvedTmdbId) }
                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else current.copy(episodeWatchStates = episodeWatchStates)
                }
                maybeConsumePendingEpisodeNavigation(videos)
            }
    }

    private suspend fun resolveEpisodeWatchStates(
        details: MediaDetails,
        videos: List<MediaVideo>,
    ): Map<String, EpisodeWatchState> {
        val tmdbId = details.primaryTmdbId() ?: resolvedTmdbId
        if (tmdbId != null) {
            resolvedTmdbId = tmdbId
        }
        return episodeWatchStateResolver.resolve(details, videos, tmdbId)
    }

    fun onOpenStreamSelectorForEpisode(videoId: String) {
        val state = _uiState.value
        val details = state.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "Details are still loading.") }
            return
        }
        val episode =
            state.seasonEpisodes.firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
                ?: seasonEpisodesCache.values.asSequence().flatten().firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
        val target =
            StreamLookupTarget(
                mediaType = requestedMediaType,
                lookupId = episode?.lookupId?.trim().orEmpty(),
            )
        openStreamSelectorWithTarget(
            target = target,
            headerEpisode = episode,
            blankIdMessage = "This episode does not have a stream lookup id.",
        )
    }

    private fun openStreamSelectorWithTarget(
        target: StreamLookupTarget,
        headerEpisode: MediaVideo? = null,
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
                    streamSelector = current.copy(visible = true, headerEpisode = headerEpisode ?: current.headerEpisode),
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
                        headerEpisode = headerEpisode,
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

        val currentState = _uiState.value
        val selectedEpisodeTitle =
            currentState.streamSelector.headerEpisode
                ?.title
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(visible = false),
                statusMessage = "",
            )
        }

        val initialDetails = currentState.details

        viewModelScope.launch {
            val details = initialDetails
                ?: return@launch

            val enriched =
                withContext(Dispatchers.IO) {
                    watchCtaResolver.ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }

            val resolvedMediaType = enriched.mediaType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
            val targetEpisode =
                currentState.streamSelector.headerEpisode
                    ?: findEpisodeForLookupId(
                        lookupId = currentState.streamSelector.lookupId.orEmpty(),
                        currentEpisodes = currentState.seasonEpisodes,
                    )
            val resolvedLookupId =
                currentState.streamSelector.lookupId
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: targetEpisode?.lookupId?.trim()?.takeIf { it.isNotBlank() }
                    ?: resolveStreamLookupTarget(
                        details = enriched,
                        selectedSeason = currentState.selectedSeasonOrFirst,
                        seasonEpisodes = currentState.seasonEpisodes,
                        fallbackMediaType = requestedMediaType,
                    ).lookupId
            val normalizedLookupId =
                normalizeNuvioMediaId(
                    resolvedLookupId,
                )

            val season =
                if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                    targetEpisode?.season ?: normalizedLookupId.season
                } else {
                    null
                }
            val episode =
                if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                    targetEpisode?.episode ?: normalizedLookupId.episode
                } else {
                    null
                }

            val mediaTitle = enriched.title.trim().ifBlank { null } ?: details.title.trim().ifBlank { null }
            val title = selectedEpisodeTitle ?: targetEpisode?.title?.trim()?.takeIf { it.isNotBlank() } ?: mediaTitle ?: "Player"
            val yearInt = enriched.year?.trim()?.toIntOrNull()
            val tmdbId =
                when (resolvedMediaType) {
                    MetadataLabMediaType.SERIES -> enriched.showTmdbId ?: enriched.tmdbId ?: targetEpisode?.showTmdbId ?: resolvedTmdbId
                    MetadataLabMediaType.MOVIE -> enriched.tmdbId ?: targetEpisode?.tmdbId ?: resolvedTmdbId
                }

            val identity =
                PlaybackIdentity(
                    contentId = enriched.id,
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
            val artworkUrl = enriched.backdropUrl?.trim()?.ifBlank { null } ?: enriched.posterUrl?.trim()?.ifBlank { null }
            val subtitle = buildPlayerSubtitle(
                mediaType = resolvedMediaType,
                details = enriched,
                playerTitle = title,
                season = season,
                episode = episode,
            )

            _navigationEvents.tryEmit(
                DetailsNavigationEvent.OpenPlayer(
                    playbackUrl = playbackUrl,
                    playbackHeaders = stream.requestHeaders,
                    title = title,
                    identity = identity,
                    subtitle = subtitle,
                    artworkUrl = artworkUrl,
                    launchSnapshot =
                        PlayerLaunchSnapshot(
                            contentId = enriched.id,
                            imdbId = enriched.imdbId,
                            tmdbId = enriched.tmdbId,
                            showTmdbId = enriched.showTmdbId,
                            seasonNumber = season,
                            episodeNumber = episode,
                            mediaType = enriched.mediaType,
                            title = enriched.title,
                            posterUrl = enriched.posterUrl,
                            backdropUrl = enriched.backdropUrl,
                            description = enriched.description,
                            genres = enriched.genres,
                            year = enriched.year,
                            runtime = enriched.runtime,
                            certification = enriched.certification,
                            rating = enriched.rating,
                            cast = enriched.cast,
                            seasons = currentState.seasons,
                            selectedSeason = currentState.selectedSeasonOrFirst,
                            seasonEpisodes =
                                currentState.seasonEpisodes.map { episodeItem ->
                                    PlayerEpisodeSnapshot(
                                        id = episodeItem.id,
                                        title = episodeItem.title,
                                        season = episodeItem.season,
                                        episode = episodeItem.episode,
                                        released = episodeItem.released,
                                        overview = episodeItem.overview,
                                        thumbnailUrl = episodeItem.thumbnailUrl,
                                        lookupId = episodeItem.lookupId,
                                        tmdbId = episodeItem.tmdbId,
                                        showTmdbId = episodeItem.showTmdbId,
                                    )
                                },
                            currentEpisodeId = targetEpisode?.id,
                        ),
                )
            )
        }
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
                    watchCtaResolver.ensureImdbId(details)
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
                    watchCtaResolver.ensureImdbId(details)
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

    fun toggleEpisodeWatched(video: MediaVideo) {
        val details = uiState.value.details ?: return
        val season = video.season
        val episode = video.episode
        if (season == null || episode == null) {
            _uiState.update { it.copy(statusMessage = "Episode metadata is incomplete.") }
            return
        }

        viewModelScope.launch {
            val source =
                withContext(Dispatchers.IO) {
                    preferredWatchProvider(watchHistoryService.authState())
                }
            val desired = !(_uiState.value.episodeWatchStates[video.id]?.isWatched ?: false)

            val enriched =
                withContext(Dispatchers.IO) {
                    watchCtaResolver.ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(statusMessage = "Couldn't resolve an IMDb id for this show (required for Simkl).")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    statusMessage =
                        if (desired) {
                            "Marking ${video.title} as watched..."
                        } else {
                            "Marking ${video.title} as unwatched..."
                        },
                )
            }

            val request =
                WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = MetadataLabMediaType.SERIES,
                    title = enriched.title,
                    season = season,
                    episode = episode,
                    remoteImdbId = enriched.imdbId,
                )
            val result =
                withContext(Dispatchers.IO) {
                    if (desired) {
                        watchHistoryService.markWatched(request, source)
                    } else {
                        watchHistoryService.unmarkWatched(request, source)
                    }
                }

            episodeWatchStateResolver.clearCache()
            val refreshedEpisodes = _uiState.value.seasonEpisodes
            val refreshedWatchStates = withContext(Dispatchers.IO) { resolveEpisodeWatchStates(enriched, refreshedEpisodes) }
            _uiState.update {
                it.copy(
                    details = enriched,
                    episodeWatchStates = refreshedWatchStates,
                    statusMessage = result.statusMessage,
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
                    watchCtaResolver.ensureImdbId(details)
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

    private fun loadOmdbRatings(details: MediaDetails?) {
        omdbJob?.cancel()

        val imdbId =
            details
                ?.imdbId
                ?.trim()
                ?.takeIf { it.startsWith("tt", ignoreCase = true) }
                ?.lowercase(Locale.US)

        if (imdbId == null || !omdbRepository.isConfigured) {
            _uiState.update { it.copy(omdbIsLoading = false, omdbDetails = null) }
            return
        }

        omdbJob =
            viewModelScope.launch {
                _uiState.update { it.copy(omdbIsLoading = true, omdbDetails = null) }

                val omdbDetails = withContext(Dispatchers.IO) { omdbRepository.load(imdbId) }

                _uiState.update { state ->
                    val currentImdbId = state.details?.imdbId?.trim()?.lowercase(Locale.US)
                    if (currentImdbId != imdbId) {
                        state
                    } else {
                        state.copy(omdbIsLoading = false, omdbDetails = omdbDetails)
                    }
                }
            }
    }

private fun maybeConsumePendingEpisodeNavigation(videos: List<MediaVideo>) {
    val pending = pendingEpisodeNavigation ?: return
    val selectedSeason = _uiState.value.selectedSeasonOrFirst
    if (pending.season != null && pending.season != selectedSeason) return
    pendingEpisodeNavigation = null

    if (!pending.autoOpenEpisode || pending.episode == null) return

    val target = videos.firstOrNull { video -> video.episode == pending.episode }
    target?.let { video -> onOpenStreamSelectorForEpisode(video.id) }
}

    companion object {
        fun factory(context: Context, itemId: String, mediaType: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val httpClient = AppHttp.client(appContext)
                    val supabaseAccountClient = SupabaseServicesProvider.accountClient(appContext)
                    val backendClient = BackendServicesProvider.backendClient(appContext)
                    val tmdbImdbIdResolver = TmdbServicesProvider.imdbIdResolver(appContext)
                    val tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext)
                    val omdbRepository = OmdbRepositoryProvider.get(appContext)
                    val addonStreamsService =
                        AddonStreamsService(
                            context = appContext,
                            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                            httpClient = httpClient,
                        )

                    val aiSettingsStore = AiInsightsSettingsStore(appContext)
                    val aiRepository = AiInsightsRepository.create(appContext, httpClient)
                    return DetailsViewModel(
                        itemId = itemId,
                        mediaType = mediaType,
                        supabaseAccountClient = supabaseAccountClient,
                        backendClient = backendClient,
                        watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext),
                        tmdbImdbIdResolver = tmdbImdbIdResolver,
                        tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                        omdbRepository = omdbRepository,
                        aiSettingsStore = aiSettingsStore,
                        aiRepository = aiRepository,
                        addonStreamsService = addonStreamsService,
                    ) as T
                }
            }
        }
    }
}

private data class PendingEpisodeNavigation(
    val season: Int?,
    val episode: Int?,
    val autoOpenEpisode: Boolean,
)
