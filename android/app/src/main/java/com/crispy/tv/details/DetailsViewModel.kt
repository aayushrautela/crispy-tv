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
import com.crispy.tv.ratings.formatRating
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

@Immutable
data class EpisodeWatchState(
    val progressPercent: Double = 0.0,
    val isWatched: Boolean = false,
)

@Immutable
data class DetailsUiState(
    val itemId: String,
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val tmdbIsLoading: Boolean = false,
    val tmdbEnrichment: TmdbEnrichment? = null,
    val omdbIsLoading: Boolean = false,
    val omdbDetails: OmdbDetails? = null,
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
    val episodeWatchStates: Map<String, EpisodeWatchState> = emptyMap(),
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
) {
    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

@Immutable
data class StreamProviderUiState(
    val providerId: String,
    val providerName: String,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val streams: List<AddonStream> = emptyList(),
    val attemptedUrl: String? = null,
)

@Immutable
data class StreamSelectorUiState(
    val visible: Boolean = false,
    val mediaType: MetadataLabMediaType? = null,
    val lookupId: String? = null,
    val headerEpisode: MediaVideo? = null,
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
        val playbackHeaders: Map<String, String> = emptyMap(),
        val title: String,
        val identity: PlaybackIdentity,
        val subtitle: String?,
        val artworkUrl: String?,
        val launchSnapshot: PlayerLaunchSnapshot?,
    ) : DetailsNavigationEvent
}

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
    private var cachedEpisodeWatchKeys: Set<String>? = null
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
            cachedEpisodeWatchKeys = null

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
                        if (backendDetails.imdbId == null) ensureImdbId(backendDetails) else backendDetails
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
                val episodeWatchStates = withContext(Dispatchers.IO) { resolveEpisodeWatchStates(details, videos) }

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
                val episodeWatchStates = withContext(Dispatchers.IO) { resolveEpisodeWatchStates(details, videos) }
                _uiState.update { current ->
                    if (current.selectedSeasonOrFirst != season) current
                    else current.copy(episodeWatchStates = episodeWatchStates)
                }
                maybeConsumePendingEpisodeNavigation(videos)
            }
    }

    private fun buildEpisodeLookupId(
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

    private fun findEpisodeForLookupId(
        lookupId: String,
        currentEpisodes: List<MediaVideo>,
    ): MediaVideo? {
        val normalizedLookupId = lookupId.trim()
        if (normalizedLookupId.isBlank()) return null

        return sequenceOf(currentEpisodes.asSequence(), seasonEpisodesCache.values.asSequence().flatten())
            .flatten()
            .firstOrNull { episode ->
                episode.lookupId?.equals(normalizedLookupId, ignoreCase = true) == true ||
                    episode.id.equals(normalizedLookupId, ignoreCase = true)
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
                    ensureImdbId(details)
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

    private fun buildPlayerSubtitle(
        mediaType: MetadataLabMediaType,
        details: MediaDetails,
        playerTitle: String,
        season: Int?,
        episode: Int?,
    ): String? {
        return when (mediaType) {
            MetadataLabMediaType.SERIES -> {
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
                    ensureImdbId(details)
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

            cachedEpisodeWatchKeys = null
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
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        if (fromField != null) return details.copy(imdbId = fromField)

        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
        val lookupId = details.tmdbLookupId() ?: return details
        val resolved = tmdbImdbIdResolver.resolveImdbId(lookupId, mediaType) ?: return details
        return details.copy(imdbId = resolved)
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
            val continueSeason = continueEntry.season
            val continueEpisode = continueEntry.episode
            val label =
                if (isSeries) {
                    if (continueSeason != null && continueEpisode != null) {
                        "Continue (S$continueSeason E$continueEpisode)"
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
                if (isSeries && continueSeason != null && continueEpisode != null) {
                    buildEpisodeLookupId(
                        details = details,
                        season = continueSeason,
                        episode = continueEpisode,
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
    val targetId = details.id.trim().lowercase(Locale.US)
    if (targetId.isBlank()) return null

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
            matchesContentId(entry.contentId, targetId) && matchesMediaType(expectedType, entry.contentType)
        }
        .maxByOrNull { it.lastUpdatedEpochMs }
}

private suspend fun resolveProviderState(details: MediaDetails?): ProviderState {
    val authState = watchHistoryService.authState()
    val source = preferredWatchProvider(authState)
    val targetId = (details?.id ?: itemId).trim().lowercase(Locale.US)
    val expectedType = requestedMediaType
    if (targetId.isBlank()) {
        return ProviderState(
            isWatched = false,
            watchedAtEpochMs = null,
            isInWatchlist = false,
            isRated = false,
            userRating = null,
        )
    }

    return when (source) {
        WatchProvider.LOCAL -> {
            val local = watchHistoryService.listLocalHistory(limit = 250)
            val watchedEntry =
                local.entries
                    .asSequence()
                    .filter { entry ->
                        matchesContentId(entry.contentId, targetId) && matchesMediaType(expectedType, entry.contentType)
                    }
                    .maxByOrNull { it.watchedAtEpochMs }

                val watched = watchedEntry != null
                ProviderState(
                    isWatched = watched,
                    watchedAtEpochMs = watchedEntry?.watchedAtEpochMs,
                    isInWatchlist = false,
                    isRated = false,
                    userRating = null,
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
                        matchesContentId(item.contentId, targetId) &&
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
                        matchesContentId(item.contentId, targetId) &&
                        matchesMediaType(expectedType, item.contentType)
                }

            val isRated =
                snapshot.items.any { item ->
                    item.provider == source &&
                        item.folderId == "ratings" &&
                        matchesContentId(item.contentId, targetId) &&
                        matchesMediaType(expectedType, item.contentType)
                }

            ProviderState(
                isWatched = watched,
                watchedAtEpochMs = watchedAtEpochMs,
                isInWatchlist = inWatchlist,
                isRated = isRated,
                userRating = null,
            )
        }
    }

}

private fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
    return expected == null || expected == actual
}

// resolveWatchedState removed in favor of resolveProviderState.

private fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
    return candidate.trim().lowercase(Locale.US) == targetNormalizedId
}

private suspend fun resolveEpisodeWatchStates(
    details: MediaDetails,
    videos: List<MediaVideo>,
): Map<String, EpisodeWatchState> {
    if (videos.isEmpty()) return emptyMap()

    val watchedKeys = resolveEpisodeWatchKeys()
    val yearInt = details.year?.trim()?.toIntOrNull()
    return videos.associate { video ->
        val season = video.season
        val episode = video.episode
        if (season == null || episode == null) {
            video.id to EpisodeWatchState()
        } else {
            val watchedByHistory =
                episodeWatchKeyCandidates(details, season, episode).any { key -> watchedKeys.contains(key) }
            val localProgress =
                watchHistoryService.getLocalWatchProgress(
                    PlaybackIdentity(
                        contentId = details.id,
                        imdbId = details.imdbId,
                        tmdbId = resolvedTmdbId,
                        contentType = MetadataLabMediaType.SERIES,
                        season = season,
                        episode = episode,
                        title = video.title,
                        year = yearInt,
                        showTitle = details.title,
                        showYear = yearInt,
                    )
                )
            val progressPercent = localProgress?.progressPercent ?: 0.0
            val isWatched = watchedByHistory || progressPercent >= CTA_CONTINUE_COMPLETION_PERCENT
            video.id to
                EpisodeWatchState(
                    progressPercent = if (isWatched) maxOf(progressPercent, 100.0) else progressPercent,
                    isWatched = isWatched,
                )
        }
    }
}

private suspend fun resolveEpisodeWatchKeys(): Set<String> {
    cachedEpisodeWatchKeys?.let { return it }

    val localHistoryKeys =
        watchHistoryService
            .listLocalHistory(limit = 1000)
            .entries
            .mapNotNull { entry ->
                val season = entry.season ?: return@mapNotNull null
                val episode = entry.episode ?: return@mapNotNull null
                addEpisodeKey(entry.contentId, season, episode)
            }

    val source = preferredWatchProvider(watchHistoryService.authState())
    val providerHistoryKeys =
        if (source == WatchProvider.LOCAL) {
            emptyList()
        } else {
            val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 1000, source = source)
            val snapshot =
                if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                    cached
                } else {
                    watchHistoryService.listProviderLibrary(limitPerFolder = 1000, source = source)
                }
            snapshot.items.mapNotNull { item -> item.toEpisodeWatchKey(source) }
        }

    val combined = LinkedHashSet<String>(localHistoryKeys.size + providerHistoryKeys.size)
    combined += localHistoryKeys
    combined += providerHistoryKeys
    return combined.also { cachedEpisodeWatchKeys = it }
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

private fun episodeWatchKeyCandidates(
    details: MediaDetails,
    season: Int,
    episode: Int,
): Set<String> {
    return buildSet {
        addEpisodeKey(details.id, season, episode)?.let(::add)
    }
}

private fun addEpisodeKey(
    contentId: String,
    season: Int,
    episode: Int,
): String? {
    val normalized = contentId.trim().takeIf { it.isNotBlank() } ?: return null
    return "${normalized.lowercase(Locale.US)}:$season:$episode"
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

private fun preferredWatchProvider(authState: WatchProviderAuthState): WatchProvider {
    return when {
        authState.traktAuthenticated -> WatchProvider.TRAKT
        authState.simklAuthenticated -> WatchProvider.SIMKL
        else -> WatchProvider.LOCAL
    }
}

private data class PendingEpisodeNavigation(
    val season: Int?,
    val episode: Int?,
    val autoOpenEpisode: Boolean,
)

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
            MetadataLabMediaType.MOVIE ->
                details.imdbId?.trim()?.takeIf { it.isNotBlank() }
                    ?: details.tmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
                    ?: ""
            MetadataLabMediaType.SERIES -> {
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

private fun CrispyBackendClient.MetadataTitleDetailResponse.toMediaDetails(): MediaDetails {
    return item.toMediaDetails().copy(
        videos = emptyList(),
    )
}

private fun CrispyBackendClient.MetadataTitleDetailResponse.seasonNumbers(): List<Int> {
    val seasonNumbers = seasons.map { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
    if (seasonNumbers.isNotEmpty()) return seasonNumbers
    val seasonCount = item.seasonCount ?: return emptyList()
    return if (seasonCount > 0) (1..seasonCount).toList() else emptyList()
}

private fun CrispyBackendClient.MetadataView.toMediaDetails(): MediaDetails {
    return MediaDetails(
        id = id,
        imdbId = externalIds.imdb,
        mediaType = normalizedCatalogMediaType(),
        title = title?.trim()?.takeIf { it.isNotBlank() } ?: subtitle?.trim()?.takeIf { it.isNotBlank() } ?: id,
        posterUrl = images.posterUrl,
        backdropUrl = images.backdropUrl,
        logoUrl = images.logoUrl,
        description = summary ?: overview,
        genres = genres,
        year = releaseYear?.toString() ?: releaseDate?.take(4),
        runtime = runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" },
        certification = certification,
        rating = formatRating(rating),
        cast = emptyList(),
        directors = emptyList(),
        creators = emptyList(),
        videos = emptyList(),
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        addonId = "backend",
    )
}

private fun CrispyBackendClient.MetadataEpisodeView.toMediaVideo(): MediaVideo? {
    val canonicalId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val season = seasonNumber
    val episode = episodeNumber
    val titleText =
        title?.trim()?.takeIf { it.isNotBlank() }
            ?: when {
                episode != null -> "Episode $episode"
                else -> canonicalId
            }
    val showLookupBase =
        showExternalIds.imdb?.trim()?.takeIf { it.isNotBlank() }
            ?: showTmdbId?.takeIf { it > 0 }?.let { "tmdb:$it" }
    val lookupId =
        if (season != null && episode != null && showLookupBase != null) {
            "${normalizeNuvioMediaId(showLookupBase).contentId}:$season:$episode"
        } else {
            null
        }
    return MediaVideo(
        id = canonicalId,
        title = titleText,
        season = season,
        episode = episode,
        released = airDate,
        overview = summary,
        thumbnailUrl = images.stillUrl ?: images.posterUrl,
        lookupId = lookupId,
        tmdbId = tmdbId,
        showTmdbId = showTmdbId,
    )
}

private fun CrispyBackendClient.MetadataView.normalizedCatalogMediaType(): String {
    return if (mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)) {
        "series"
    } else {
        "movie"
    }
}

private fun MediaDetails.providerBaseLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: (showTmdbId ?: tmdbId)?.takeIf { it > 0 }?.let { "tmdb:$it" }
}

private fun MediaDetails.tmdbLookupId(): String? {
    return imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: primaryTmdbId()?.takeIf { it > 0 }?.let { "tmdb:$it" }
}

private fun MediaDetails.primaryTmdbId(): Int? {
    return showTmdbId ?: tmdbId
}

private fun MediaDetails.mergeEnhancements(
    enhancement: MediaDetails,
    resolvedImdbId: String?,
): MediaDetails {
    return copy(
        imdbId = imdbId ?: resolvedImdbId ?: enhancement.imdbId,
        posterUrl = posterUrl ?: enhancement.posterUrl,
        backdropUrl = backdropUrl ?: enhancement.backdropUrl,
        logoUrl = logoUrl ?: enhancement.logoUrl,
        description = description ?: enhancement.description,
        genres = if (genres.isNotEmpty()) genres else enhancement.genres,
        year = year ?: enhancement.year,
        runtime = runtime ?: enhancement.runtime,
        certification = certification ?: enhancement.certification,
        rating = rating ?: enhancement.rating,
        cast = if (cast.isNotEmpty()) cast else enhancement.cast,
        directors = if (directors.isNotEmpty()) directors else enhancement.directors,
        creators = if (creators.isNotEmpty()) creators else enhancement.creators,
        tmdbId = tmdbId ?: enhancement.tmdbId,
        showTmdbId = showTmdbId ?: enhancement.showTmdbId,
    )
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

private fun ProviderLibraryItem.toEpisodeWatchKey(source: WatchProvider): String? {
    if (provider != source || !source.isWatchedFolder(folderId)) return null
    val seasonNumber = season ?: return null
    val episodeNumber = episode ?: return null
    return addEpisodeKey(contentId, seasonNumber, episodeNumber)
}
