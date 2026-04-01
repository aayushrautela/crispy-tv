package com.crispy.tv.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.playerui.PlayerEpisodeSnapshot
import com.crispy.tv.playerui.PlayerLaunchSnapshot
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.StreamProviderDescriptor
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.playback.StreamLookupTarget
import com.crispy.tv.playback.buildPlayerSubtitle
import com.crispy.tv.playback.buildStreamStatusMessage
import com.crispy.tv.playback.findEpisodeForLookupId
import com.crispy.tv.playback.resolveStreamLookupTarget
import com.crispy.tv.playback.matchesTarget
import com.crispy.tv.playback.toUiState
import com.crispy.tv.playback.toLoadingUiState
import com.crispy.tv.playback.applyProviderResult
import com.crispy.tv.playback.finalizeFrom
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

class DetailsViewModel internal constructor(
    private val itemId: String,
    private val mediaType: String,
    private val detailsUseCases: DetailsUseCases,
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
    private var pendingEpisodeNavigation: PendingEpisodeNavigation? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()

            episodesJob?.cancel()
            seasonEpisodesCache.clear()
            detailsUseCases.clearEpisodeWatchStateCache()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    titleDetail = null,
                    omdbContent = null,
                    statusMessage = "Loading...",
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

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.loadScreen(
                        itemId = itemId,
                        requestedMediaType = requestedMediaType,
                        nowMs = nowMs,
                    )
                }

            val enrichedDetails = result.details

            _uiState.update { state ->
                val pendingHighlightEpisodeId = pendingEpisodeNavigation?.highlightEpisodeId
                val pendingSeason =
                    pendingHighlightEpisodeId?.let { highlightEpisodeId ->
                        result.details?.videos?.firstOrNull { video ->
                            video.id.equals(highlightEpisodeId, ignoreCase = true)
                        }?.season
                    }
                val selectedSeason =
                    when {
                        pendingSeason != null && pendingSeason in result.seasons -> pendingSeason
                        state.selectedSeason != null && state.selectedSeason in result.seasons -> state.selectedSeason
                        result.seasons.isNotEmpty() -> result.seasons.first()
                        else -> null
                    }

                state.copy(
                    isLoading = false,
                    details = result.details,
                    titleDetail = result.titleDetail,
                    omdbContent = result.omdbContent,
                    statusMessage = result.statusMessage,
                    isWatched = result.providerState.isWatched,
                    isInWatchlist = result.providerState.isInWatchlist,
                    isRated = result.providerState.isRated,
                    userRating = result.providerState.userRating,
                    watchCta = result.watchCta,
                    continueVideoId = result.continueVideoId,
                    seasons = result.seasons,
                    selectedSeason = selectedSeason,
                    seasonEpisodes = emptyList(),
                    episodeWatchStates = emptyMap(),
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }

            val seasonToLoad = _uiState.value.selectedSeasonOrFirst
            if (
                enrichedDetails?.mediaType
                    ?.toMetadataLabMediaTypeOrNull()
                    ?.let { it != MetadataLabMediaType.MOVIE }
                    == true && seasonToLoad != null
            ) {
                loadEpisodesForSeason(seasonToLoad, force = true)
            }

            val detailsForAi = enrichedDetails
            val aiLocale = Locale.getDefault()
            val contentId = detailsForAi?.id?.trim()

            if (!contentId.isNullOrBlank()) {
                val cached =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadCachedAiInsights(contentId, aiLocale)
                    }
                if (cached != null) {
                    _uiState.update { it.copy(aiInsights = cached) }
                }
            }
        }
    }

    fun onAiInsightsClick() {
        val state = uiState.value
        val details = state.details
        val contentId = details?.id?.trim()
        if (details == null || contentId.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "AI insights aren't available for this title yet.") }
            return
        }

        val cachedOrLoaded = state.aiInsights
        if (cachedOrLoaded != null) {
            _uiState.update { it.copy(aiStoryVisible = true) }
            return
        }

        if (state.aiIsLoading) return

        startAiGeneration(
            contentId = contentId,
            locale = Locale.getDefault(),
            showStory = true,
            announce = true,
        )
    }

    fun dismissAiInsightsStory() {
        _uiState.update { it.copy(aiStoryVisible = false) }
    }

    private fun startAiGeneration(
        contentId: String,
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
                        detailsUseCases.generateAiInsights(
                            contentId = contentId,
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
        highlightEpisodeId: String?,
        autoOpenEpisode: Boolean,
    ) {
        val normalizedHighlightEpisodeId = highlightEpisodeId?.trim()?.ifBlank { null } ?: return
        pendingEpisodeNavigation =
            PendingEpisodeNavigation(
                highlightEpisodeId = normalizedHighlightEpisodeId,
                autoOpenEpisode = autoOpenEpisode,
            )

        val currentState = _uiState.value
        val targetSeason =
            currentState.seasonEpisodes.firstOrNull { episode ->
                episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
            }?.season
                ?: seasonEpisodesCache.values.asSequence().flatten().firstOrNull { episode ->
                    episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
                }?.season
                ?: currentState.details?.videos?.firstOrNull { episode ->
                    episode.id.equals(normalizedHighlightEpisodeId, ignoreCase = true)
                }?.season
                ?: currentState.selectedSeasonOrFirst
                ?: return
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
        if (details.mediaType.toMetadataLabMediaTypeOrNull()?.let { it != MetadataLabMediaType.MOVIE } != true) return

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
                val result =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.loadSeasonEpisodes(
                            itemId = itemId,
                            season = season,
                            details = details,
                        )
                    }

                if (result.errorMessage != null) {
                    _uiState.update { current ->
                        if (current.selectedSeasonOrFirst != season) current
                        else current.copy(
                            episodesIsLoading = false,
                            episodesStatusMessage = result.errorMessage,
                        )
                    }
                    return@launch
                }

                seasonEpisodesCache[season] = result.videos
                result.effectiveSeasonNumber
                    ?.takeIf { it != season }
                    ?.let { effectiveSeason ->
                        seasonEpisodesCache[effectiveSeason] = result.videos
                    }

                _uiState.update { current ->
                    val resolvedSeason = result.effectiveSeasonNumber ?: season
                    if (current.selectedSeasonOrFirst != season && current.selectedSeasonOrFirst != resolvedSeason) current
                    else current.copy(
                        selectedSeason = resolvedSeason,
                        seasons =
                            when {
                                result.includedSeasonNumbers.isNotEmpty() -> result.includedSeasonNumbers
                                else -> current.seasons
                            },
                        seasonEpisodes = result.videos,
                        episodeWatchStates = result.episodeWatchStates,
                        episodesIsLoading = false,
                        episodesStatusMessage = if (result.videos.isEmpty()) "No episodes found." else "",
                    )
                }
                maybeConsumePendingEpisodeNavigation(result.videos)
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
                val episodeWatchStates =
                    withContext(Dispatchers.IO) {
                        detailsUseCases.resolveEpisodeWatchStates(details, videos)
                    }
                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else current.copy(episodeWatchStates = episodeWatchStates)
                }
                maybeConsumePendingEpisodeNavigation(videos)
            }
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
                    detailsUseCases.loadStreams(
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
                    detailsUseCases.loadProviderStreams(
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
                    detailsUseCases.ensureImdbId(details, requestedMediaType)
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

            val isEpisodic = resolvedMediaType != MetadataLabMediaType.MOVIE
            val parentMediaType =
                when (resolvedMediaType) {
                    MetadataLabMediaType.MOVIE -> null
                    MetadataLabMediaType.SERIES -> "show"
                    MetadataLabMediaType.ANIME -> "anime"
                }
            val season = if (isEpisodic) targetEpisode?.season ?: normalizedLookupId.season else null
            val episode = if (isEpisodic) targetEpisode?.episode ?: normalizedLookupId.episode else null

            val mediaTitle = enriched.title.trim().ifBlank { null } ?: details.title.trim().ifBlank { null }
            val title = selectedEpisodeTitle ?: targetEpisode?.title?.trim()?.takeIf { it.isNotBlank() } ?: mediaTitle ?: "Player"
            val yearInt = enriched.year?.trim()?.toIntOrNull()
            val identity =
                PlaybackIdentity(
                    contentId = enriched.id,
                    imdbId = enriched.imdbId,
                    tmdbId = if (resolvedMediaType == MetadataLabMediaType.MOVIE) enriched.tmdbId ?: targetEpisode?.tmdbId else null,
                    contentType = resolvedMediaType,
                    season = season,
                    episode = episode,
                    title = title,
                    year = yearInt,
                    showTitle = if (isEpisodic) enriched.title else null,
                    showYear = if (isEpisodic) yearInt else null,
                    provider = targetEpisode?.provider ?: enriched.provider,
                    providerId = targetEpisode?.providerId ?: enriched.providerId,
                    parentMediaType = enriched.parentMediaType ?: parentMediaType,
                    parentProvider = targetEpisode?.parentProvider ?: enriched.parentProvider ?: enriched.provider,
                    parentProviderId = targetEpisode?.parentProviderId ?: enriched.parentProviderId ?: enriched.providerId,
                    absoluteEpisodeNumber = targetEpisode?.absoluteEpisodeNumber ?: enriched.absoluteEpisodeNumber,
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
                            seasonNumber = season,
                            episodeNumber = episode,
                            mediaType = enriched.mediaType,
                            provider = enriched.provider,
                            providerId = enriched.providerId,
                            parentMediaType = enriched.parentMediaType ?: parentMediaType,
                            parentProvider = enriched.parentProvider ?: enriched.provider,
                            parentProviderId = enriched.parentProviderId ?: enriched.providerId,
                            absoluteEpisodeNumber = targetEpisode?.absoluteEpisodeNumber ?: enriched.absoluteEpisodeNumber,
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
                                        provider = episodeItem.provider,
                                        providerId = episodeItem.providerId,
                                        parentProvider = episodeItem.parentProvider,
                                        parentProviderId = episodeItem.parentProviderId,
                                        absoluteEpisodeNumber = episodeItem.absoluteEpisodeNumber,
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
        viewModelScope.launch {
            val desired = !_uiState.value.isInWatchlist
            _uiState.update { it.copy(isMutating = true) }

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.updateWatchlist(details, desired)
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    details = result.details,
                    isInWatchlist = if (result.success) desired else it.isInWatchlist
                )
            }
        }
    }

    fun toggleWatched() {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        if (mediaType != MetadataLabMediaType.MOVIE) {
            _uiState.update { it.copy(statusMessage = "Marking an entire episodic title as watched isn't supported yet. Mark episodes from the episode list.") }
            return
        }
        viewModelScope.launch {
            val desired = !_uiState.value.isWatched
            _uiState.update { it.copy(isMutating = true) }

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.updateWatched(details, desired)
                }
            _uiState.update {
                val nextIsWatched = if (result.success) desired else it.isWatched
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
                    details = result.details,
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
            val desired = !(_uiState.value.episodeWatchStates[video.id]?.isWatched ?: false)

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

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.updateEpisodeWatched(details, video, desired)
                }

            detailsUseCases.clearEpisodeWatchStateCache()
            val refreshedEpisodes = _uiState.value.seasonEpisodes
            val refreshedWatchStates =
                withContext(Dispatchers.IO) {
                    detailsUseCases.resolveEpisodeWatchStates(result.details, refreshedEpisodes)
                }
            _uiState.update {
                it.copy(
                    details = result.details,
                    episodeWatchStates = refreshedWatchStates,
                    statusMessage = result.statusMessage,
                )
            }
        }
    }

    fun setRating(rating: Int?) {
        val details = uiState.value.details ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true) }

            val result =
                withContext(Dispatchers.IO) {
                    detailsUseCases.updateRating(details, rating)
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    details = result.details,
                    statusMessage = result.statusMessage,
                    isRated = if (result.success) rating != null else it.isRated,
                    userRating = if (result.success) rating else it.userRating
                )
            }
        }
    }

    private fun maybeConsumePendingEpisodeNavigation(videos: List<MediaVideo>) {
        val pending = pendingEpisodeNavigation ?: return
        val target = videos.firstOrNull { video ->
            video.id.equals(pending.highlightEpisodeId, ignoreCase = true)
        } ?: return
        pendingEpisodeNavigation = null

        if (!pending.autoOpenEpisode) return

        target?.let { video -> onOpenStreamSelectorForEpisode(video.id) }
    }

    companion object {
        internal fun factory(
            itemId: String,
            mediaType: String,
            detailsUseCases: DetailsUseCases,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DetailsViewModel(
                        itemId = itemId,
                        mediaType = mediaType,
                        detailsUseCases = detailsUseCases,
                    ) as T
                }
            }
        }
    }
}

private data class PendingEpisodeNavigation(
    val highlightEpisodeId: String,
    val autoOpenEpisode: Boolean,
)
