package com.crispy.tv.playerui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.PlayerView
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.details.StreamSelectorUiState
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackError
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeVideoLayout
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.AddonStreamsService
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamProviderDescriptor
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlayerSurface {
    NONE,
    INFO,
    STREAMS,
}

@Immutable
data class PlayerUiState(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val backdropUrl: String? = null,
    val activeEngine: NativePlaybackEngine = NativePlaybackEngine.EXO,
    val isBuffering: Boolean = true,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val stableDurationMs: Long = 0L,
    val statusMessage: String = "Preparing playback...",
    val errorMessage: String? = null,
    val videoLayout: NativeVideoLayout? = null,
    val details: MediaDetails? = null,
    val activeIdentity: PlaybackIdentity? = null,
    val activeSurface: PlayerSurface = PlayerSurface.NONE,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val seasonEpisodes: List<MediaVideo> = emptyList(),
    val currentEpisodeId: String? = null,
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
    val currentPlaybackUrl: String? = null,
)

class PlayerSessionViewModel(
    appContext: Context,
    playbackSource: PlaybackSource,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    identity: PlaybackIdentity?,
    launchSnapshot: PlayerLaunchSnapshot?,
    restorePlaybackIntent: Intent,
) : ViewModel() {
    private val appContext = appContext.applicationContext
    private val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(this.appContext)
    private val addonStreamsService: AddonStreamsService = PlaybackDependencies.addonStreamsServiceFactory(this.appContext)
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository =
        PlaybackDependencies.tmdbEnrichmentRepositoryFactory(this.appContext)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackMetrics = PlaybackMetricsHolder()
    private val playbackController: PlaybackController = PlaybackDependencies.playbackControllerFactory(this.appContext)
    private val mediaSessionManager =
        PlayerMediaSessionManager(
            context = this.appContext,
            playbackController = playbackController,
            restorePlaybackIntent = restorePlaybackIntent,
        )

    private val rawPlaybackId = buildPlaybackRawId(identity = identity, snapshot = launchSnapshot)
    private val seasonEpisodesCache = mutableMapOf<Int, List<MediaVideo>>()
    private var activePlaybackSource = playbackSource
    private var activeIdentity: PlaybackIdentity? = identity
    private var activeSubtitle: String? = subtitle?.trim()?.ifBlank { null }
    private var activeArtworkUrl: String? = artworkUrl?.trim()?.ifBlank { null }
    private var currentTmdbId: Int? = identity?.tmdbId
    private var lastHandledErrorToken: Long? = null
    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs = 0L
    private var pendingInitialSeekMs: Long? = null

    private val initialDetails =
        launchSnapshot?.toMediaDetails() ?: buildFallbackDetails(
            rawId = rawPlaybackId,
            title = title,
            artworkUrl = artworkUrl,
            identity = identity,
        )
    private val initialSelectedSeason =
        launchSnapshot?.selectedSeason
            ?: identity?.season
            ?: launchSnapshot?.seasonEpisodes?.firstOrNull()?.season
    private val initialSeasonEpisodes =
        launchSnapshot?.seasonEpisodes
            ?.map(PlayerEpisodeSnapshot::toMediaVideo)
            .orEmpty()

    private val _uiState =
        MutableStateFlow(
            PlayerUiState(
                title = title.ifBlank { "Player" },
                subtitle = activeSubtitle,
                artworkUrl = activeArtworkUrl,
                backdropUrl = initialDetails?.backdropUrl,
                details = initialDetails,
                activeIdentity = activeIdentity,
                seasons = launchSnapshot?.seasons.orEmpty(),
                selectedSeason = initialSelectedSeason,
                seasonEpisodes = initialSeasonEpisodes,
                currentEpisodeId = launchSnapshot?.currentEpisodeId,
                currentPlaybackUrl = playbackSource.url,
            )
        )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        initialSelectedSeason?.takeIf { initialSeasonEpisodes.isNotEmpty() }?.let { season ->
            seasonEpisodesCache[season] = initialSeasonEpisodes
        }
        mediaSessionManager.updateMetadata(
            title = uiState.value.title,
            subtitle = uiState.value.subtitle,
            artworkUrl = uiState.value.artworkUrl,
        )
        requestPlayback(engine = uiState.value.activeEngine)
        viewModelScope.launch {
            loadInitialMetadata()
        }
        viewModelScope.launch {
            pollPlaybackState()
        }
    }

    fun bindExoPlayerView(playerView: PlayerView) {
        playbackController.bindExoPlayerView(playerView)
    }

    fun createVlcSurfaceView(context: Context): SurfaceView = playbackController.createVlcSurfaceView(context)

    fun attachVlcSurface(surfaceView: SurfaceView) {
        playbackController.attachVlcSurface(surfaceView)
    }

    fun setPlaying(isPlaying: Boolean) {
        playbackController.setPlaying(isPlaying)
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun showInfo() {
        _uiState.update { state ->
            state.copy(
                activeSurface = PlayerSurface.INFO,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
    }

    fun closeActiveSurface() {
        _uiState.update { state ->
            state.copy(
                activeSurface = PlayerSurface.NONE,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
    }

    fun onSeasonSelected(season: Int) {
        val cached = seasonEpisodesCache[season]
        _uiState.update { state ->
            state.copy(
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

    fun showStreams() {
        val details = uiState.value.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "Title details are still loading.") }
            return
        }

        val target =
            resolveStreamLookupTarget(
                details = details,
                selectedSeason = uiState.value.selectedSeason,
                seasonEpisodes = uiState.value.seasonEpisodes,
                fallbackMediaType = activeIdentity?.contentType ?: MetadataLabMediaType.MOVIE,
            )
        val headerEpisode =
            findEpisodeForLookupId(
                lookupId = target.lookupId,
                currentEpisodes = uiState.value.seasonEpisodes,
                cachedEpisodes = seasonEpisodesCache.values,
            )
        openStreamSelector(target = target, headerEpisode = headerEpisode)
    }

    fun showStreamsForEpisode(videoId: String) {
        val details = uiState.value.details
        if (details == null) {
            _uiState.update { it.copy(statusMessage = "Title details are still loading.") }
            return
        }
        val target =
            PlayerStreamLookupTarget(
                mediaType = activeIdentity?.contentType ?: MetadataLabMediaType.SERIES,
                lookupId = videoId.trim(),
            )
        val headerEpisode =
            findEpisodeForLookupId(
                lookupId = target.lookupId,
                currentEpisodes = uiState.value.seasonEpisodes,
                cachedEpisodes = seasonEpisodesCache.values,
            )
        openStreamSelector(target = target, headerEpisode = headerEpisode)
    }

    fun onProviderSelected(providerId: String?) {
        _uiState.update { state ->
            state.copy(
                streamSelector = state.streamSelector.copy(
                    selectedProviderId = providerId?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    fun onRetryProvider(providerId: String) {
        val normalizedProviderId = providerId.trim()
        if (normalizedProviderId.isBlank()) return

        val selectorState = uiState.value.streamSelector
        val mediaType = selectorState.mediaType ?: return
        val lookupId = selectorState.lookupId ?: return

        _uiState.update { state ->
            val providers =
                state.streamSelector.providers.map { provider ->
                    if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                        provider.copy(isLoading = true, errorMessage = null)
                    } else {
                        provider
                    }
                }
            state.copy(
                streamSelector = state.streamSelector.copy(providers = providers),
                statusMessage = "Retrying provider...",
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
                if (result == null) {
                    _uiState.update { it.copy(statusMessage = "Provider is unavailable.") }
                    return@onSuccess
                }
                _uiState.update { state ->
                    val providers = state.streamSelector.providers.applyProviderResult(result)
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage = buildStreamStatusMessage(providers, isLoading = false),
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _uiState.update { state ->
                    val providers =
                        state.streamSelector.providers.map { provider ->
                            if (provider.providerId.equals(normalizedProviderId, ignoreCase = true)) {
                                provider.copy(isLoading = false, errorMessage = error.message ?: "Failed to reload provider.")
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
        val playbackSource = stream.toPlaybackSource()
        if (playbackSource == null) {
            _uiState.update { it.copy(statusMessage = "Selected stream has no playable URL.") }
            return
        }

        val state = uiState.value
        val details = state.details ?: return
        val lookupId = state.streamSelector.lookupId ?: details.id
        val normalizedLookupId = com.crispy.tv.domain.metadata.normalizeNuvioMediaId(lookupId)
        val nextMediaType = state.streamSelector.mediaType ?: activeIdentity?.contentType ?: MetadataLabMediaType.MOVIE
        val nextEpisode =
            state.streamSelector.headerEpisode
                ?: findEpisodeForLookupId(
                    lookupId = lookupId,
                    currentEpisodes = state.seasonEpisodes,
                    cachedEpisodes = seasonEpisodesCache.values,
                )

        val nextSeason = if (nextMediaType == MetadataLabMediaType.SERIES) normalizedLookupId.season else null
        val nextEpisodeNumber = if (nextMediaType == MetadataLabMediaType.SERIES) normalizedLookupId.episode else null
        val nextTitle = nextEpisode?.title?.trim()?.takeIf { it.isNotBlank() } ?: details.title.trim().ifBlank { "Player" }
        val nextSubtitle = buildPlayerSubtitle(nextMediaType, details, nextTitle, nextSeason, nextEpisodeNumber)
        val nextIdentity =
            PlaybackIdentity(
                imdbId = details.imdbId,
                tmdbId = currentTmdbId ?: extractTmdbIdOrNull(details.id) ?: extractTmdbIdOrNull(lookupId),
                contentType = nextMediaType,
                season = nextSeason,
                episode = nextEpisodeNumber,
                title = nextTitle,
                year = details.year?.trim()?.toIntOrNull(),
                showTitle = if (nextMediaType == MetadataLabMediaType.SERIES) details.title else null,
                showYear = if (nextMediaType == MetadataLabMediaType.SERIES) details.year?.trim()?.toIntOrNull() else null,
            )

        val sameEpisode =
            nextIdentity.contentType == activeIdentity?.contentType &&
                nextIdentity.season == activeIdentity?.season &&
                nextIdentity.episode == activeIdentity?.episode
        val resumePositionMs = if (sameEpisode) uiState.value.positionMs else 0L

        switchPlayback(
            source = playbackSource,
            identity = nextIdentity,
            title = nextTitle,
            subtitle = nextSubtitle,
            artworkUrl = activeArtworkUrl,
            currentEpisodeId = nextEpisode?.id,
            resumePositionMs = resumePositionMs,
        )
    }

    fun retryPlayback() {
        _uiState.update { state ->
            state.copy(
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "Retrying playback...",
                errorMessage = null,
                videoLayout = null,
            )
        }
        requestPlayback(engine = uiState.value.activeEngine)
    }

    private suspend fun loadInitialMetadata() {
        val rawId = rawPlaybackId ?: return
        val mediaTypeHint = activeIdentity?.contentType

        val result =
            withContext(Dispatchers.IO) {
                tmdbEnrichmentRepository.load(
                    rawId = rawId,
                    mediaTypeHint = mediaTypeHint,
                )
            } ?: return

        currentTmdbId = result.enrichment.tmdbId
        val fetchedDetails = result.fallbackDetails
        val seasons =
            ((result.enrichment.titleDetails as? TmdbTvDetails)?.numberOfSeasons ?: 0)
                .takeIf { it > 0 }
                ?.let { 1..it }
                ?.toList()
                .orEmpty()

        _uiState.update { state ->
            val selectedSeason =
                state.selectedSeason
                    ?: activeIdentity?.season
                    ?: seasons.firstOrNull()
            state.copy(
                details = fetchedDetails,
                backdropUrl = fetchedDetails.backdropUrl,
                artworkUrl = state.artworkUrl ?: fetchedDetails.backdropUrl ?: fetchedDetails.posterUrl,
                seasons = if (seasons.isNotEmpty()) seasons else state.seasons,
                selectedSeason = selectedSeason,
            )
        }

        val seasonToLoad = _uiState.value.selectedSeason
        if (fetchedDetails.mediaType.trim().equals("series", ignoreCase = true) && seasonToLoad != null) {
            loadEpisodesForSeason(seasonToLoad, force = _uiState.value.seasonEpisodes.isEmpty())
        }
    }

    private fun loadEpisodesForSeason(
        season: Int,
        force: Boolean = false,
    ) {
        val details = _uiState.value.details ?: return
        if (!details.mediaType.trim().equals("series", ignoreCase = true)) return
        val tmdbId = currentTmdbId ?: return
        val cached = if (!force) seasonEpisodesCache[season] else null
        if (cached != null) {
            _uiState.update {
                it.copy(
                    seasonEpisodes = cached,
                    episodesIsLoading = false,
                    episodesStatusMessage = "",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                episodesIsLoading = true,
                episodesStatusMessage = "",
                seasonEpisodes = emptyList(),
            )
        }

        viewModelScope.launch {
            val episodes =
                runCatching {
                    withContext(Dispatchers.IO) {
                        tmdbEnrichmentRepository.loadSeasonEpisodes(tmdbId = tmdbId, seasonNumber = season)
                    }
                }.getOrElse {
                    _uiState.update { current ->
                        if (current.selectedSeason != season) {
                            current
                        } else {
                            current.copy(
                                episodesIsLoading = false,
                                episodesStatusMessage = "Failed to load episodes.",
                            )
                        }
                    }
                    return@launch
                }

            val videos =
                episodes.mapNotNull { episode ->
                    val lookupId =
                        buildEpisodeLookupId(
                            details = details,
                            season = episode.seasonNumber,
                            episode = episode.episodeNumber,
                        ) ?: return@mapNotNull null
                    MediaVideo(
                        id = lookupId,
                        title = episode.name,
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                        released = episode.airDate,
                        overview = episode.overview,
                        thumbnailUrl = episode.stillUrl,
                    )
                }

            seasonEpisodesCache[season] = videos
            _uiState.update { current ->
                if (current.selectedSeason != season) {
                    current
                } else {
                    current.copy(
                        seasonEpisodes = videos,
                        episodesIsLoading = false,
                        episodesStatusMessage = if (videos.isEmpty()) "No episodes found." else "",
                    )
                }
            }
        }
    }

    private fun openStreamSelector(
        target: PlayerStreamLookupTarget,
        headerEpisode: MediaVideo?,
    ) {
        if (target.lookupId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Unable to resolve stream lookup id for this title.") }
            return
        }

        val current = _uiState.value.streamSelector
        if (current.lookupId == target.lookupId && current.mediaType == target.mediaType && current.providers.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    activeSurface = PlayerSurface.STREAMS,
                    streamSelector = current.copy(visible = true, headerEpisode = headerEpisode ?: current.headerEpisode),
                    statusMessage = "",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                activeSurface = PlayerSurface.STREAMS,
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
                                streamSelector = previous.streamSelector.copy(
                                    providers = updatedProviders,
                                    isLoading = stillLoading,
                                ),
                                statusMessage = buildStreamStatusMessage(updatedProviders, stillLoading),
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
                            .ifEmpty { results.map(ProviderStreamsResult::toUiState) }
                    previous.copy(
                        streamSelector = previous.streamSelector.copy(
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
                        streamSelector = previous.streamSelector.copy(
                            providers = previous.streamSelector.providers.map { provider -> provider.copy(isLoading = false) },
                            isLoading = false,
                        ),
                        statusMessage = error.message ?: "Failed to fetch streams.",
                    )
                }
            }
        }
    }

    private fun switchPlayback(
        source: PlaybackSource,
        identity: PlaybackIdentity,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        currentEpisodeId: String?,
        resumePositionMs: Long,
    ) {
        val previousIdentity = activeIdentity
        if (previousIdentity != null && previousIdentity != identity) {
            reportPlaybackStopped(previousIdentity)
            hasReportedPlaybackStart = false
            hasReportedPlaybackStop = false
            lastProgressSyncAtElapsedMs = 0L
        }

        activePlaybackSource = source
        activeIdentity = identity
        activeSubtitle = subtitle?.trim()?.ifBlank { null }
        activeArtworkUrl = artworkUrl?.trim()?.ifBlank { null }
        pendingInitialSeekMs = resumePositionMs.takeIf { it > 0L }

        _uiState.update { state ->
            state.copy(
                title = title.ifBlank { "Player" },
                subtitle = activeSubtitle,
                artworkUrl = activeArtworkUrl,
                activeIdentity = identity,
                currentEpisodeId = currentEpisodeId,
                currentPlaybackUrl = source.url,
                activeSurface = PlayerSurface.NONE,
                streamSelector = state.streamSelector.copy(visible = false),
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = if (resumePositionMs > 0L) "Switching stream..." else "Loading selection...",
                errorMessage = null,
                videoLayout = null,
            )
        }

        mediaSessionManager.updateMetadata(
            title = title,
            subtitle = activeSubtitle,
            artworkUrl = activeArtworkUrl,
        )

        requestPlayback(engine = uiState.value.activeEngine)
    }

    private fun onPlaybackMetrics(snapshot: NativePlaybackSnapshot) {
        val sanitizedPositionMs = snapshot.positionMs.coerceAtLeast(0L)
        val sanitizedDurationMs = snapshot.durationMs.coerceAtLeast(0L)

        _uiState.update { state ->
            val nextStableDurationMs =
                if (sanitizedDurationMs > 0L) {
                    sanitizedDurationMs
                } else {
                    state.stableDurationMs
                }

            val shouldUpdatePosition = abs(sanitizedPositionMs - state.positionMs) >= 500L
            val shouldUpdateDuration =
                sanitizedDurationMs != state.durationMs || nextStableDurationMs != state.stableDurationMs
            val nextStatusMessage = statusMessage(snapshot)
            val nextErrorMessage = snapshot.error?.message
            val shouldUpdatePlaybackState =
                snapshot.engine != state.activeEngine ||
                    snapshot.isBuffering != state.isBuffering ||
                    snapshot.isPlaying != state.isPlaying ||
                    nextStatusMessage != state.statusMessage ||
                    nextErrorMessage != state.errorMessage ||
                    snapshot.videoLayout != state.videoLayout

            if (!(shouldUpdatePosition || shouldUpdateDuration || shouldUpdatePlaybackState)) {
                state
            } else {
                state.copy(
                    activeEngine = snapshot.engine,
                    isBuffering = snapshot.isBuffering,
                    isPlaying = snapshot.isPlaying,
                    positionMs = sanitizedPositionMs,
                    durationMs = sanitizedDurationMs,
                    stableDurationMs = nextStableDurationMs,
                    statusMessage = nextStatusMessage,
                    errorMessage = nextErrorMessage,
                    videoLayout = snapshot.videoLayout,
                )
            }
        }
    }

    private fun requestPlayback(engine: NativePlaybackEngine) {
        if (activePlaybackSource.url.isBlank()) {
            return
        }

        lastHandledErrorToken = null
        Log.d(TAG, "play request engine=$engine playbackUrlHash=${activePlaybackSource.url.hashCode()}")
        playbackController.play(activePlaybackSource, engine)
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    private suspend fun pollPlaybackState() {
        while (currentCoroutineContext().isActive) {
            val snapshot = playbackController.snapshot()

            if (maybeHandlePlaybackError(snapshot.error, snapshot.engine)) {
                delay(250)
                continue
            }

            applyPendingInitialSeekIfNeeded(snapshot)

            playbackMetrics.positionMs = snapshot.positionMs
            playbackMetrics.durationMs = snapshot.durationMs

            onPlaybackMetrics(snapshot)

            val uiStateSnapshot = uiState.value
            mediaSessionManager.updatePlayback(
                title = uiStateSnapshot.title,
                subtitle = uiStateSnapshot.subtitle,
                artworkUrl = uiStateSnapshot.artworkUrl,
                isPlaying = uiStateSnapshot.isPlaying,
                isBuffering = uiStateSnapshot.isBuffering,
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
            )

            syncWatchHistory(
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
                isPlaying = uiStateSnapshot.isPlaying,
            )

            delay(250)
        }
    }

    private fun applyPendingInitialSeekIfNeeded(snapshot: NativePlaybackSnapshot) {
        val targetPositionMs = pendingInitialSeekMs ?: return
        if (targetPositionMs <= 0L) {
            pendingInitialSeekMs = null
            return
        }
        if (snapshot.state == NativePlaybackState.IDLE || snapshot.state == NativePlaybackState.PREPARING) {
            return
        }
        if (snapshot.positionMs >= targetPositionMs - 1_000L) {
            pendingInitialSeekMs = null
            return
        }
        playbackController.seekTo(targetPositionMs)
        pendingInitialSeekMs = null
    }

    private fun maybeHandlePlaybackError(
        error: NativePlaybackError?,
        engine: NativePlaybackEngine,
    ): Boolean {
        if (error == null || lastHandledErrorToken == error.token) {
            return false
        }

        lastHandledErrorToken = error.token
        val shouldFallback = error.codecLikely && engine == NativePlaybackEngine.EXO
        if (!shouldFallback) {
            return false
        }

        Log.w(TAG, "Codec issue detected in EXO, retrying with VLC. message=${error.message}")
        _uiState.update { state ->
            state.copy(
                activeEngine = NativePlaybackEngine.VLC,
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "Codec issue detected, retrying with VLC...",
                errorMessage = null,
                videoLayout = null,
            )
        }
        requestPlayback(engine = NativePlaybackEngine.VLC)
        return true
    }

    private fun syncPlaybackSnapshot(snapshot: NativePlaybackSnapshot) {
        if (maybeHandlePlaybackError(snapshot.error, snapshot.engine)) {
            return
        }
        onPlaybackMetrics(snapshot)
    }

    private fun statusMessage(snapshot: NativePlaybackSnapshot): String {
        return when (snapshot.state) {
            NativePlaybackState.IDLE,
            NativePlaybackState.PREPARING -> "Preparing playback..."
            NativePlaybackState.BUFFERING -> "Buffering..."
            NativePlaybackState.PLAYING -> "Playing"
            NativePlaybackState.PAUSED -> "Paused"
            NativePlaybackState.ENDED -> "Playback ended."
            NativePlaybackState.ERROR -> snapshot.error?.message ?: "Playback error"
        }
    }

    private fun syncWatchHistory(
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val playbackIdentity = activeIdentity ?: return
        if (durationMs <= 0L) {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!hasReportedPlaybackStart && isPlaying) {
            hasReportedPlaybackStart = true
            hasReportedPlaybackStop = false
            lastProgressSyncAtElapsedMs = nowElapsedMs
            backgroundScope.launch {
                watchHistoryService.onPlaybackStarted(
                    identity = playbackIdentity,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
            return
        }

        if (!hasReportedPlaybackStart || nowElapsedMs - lastProgressSyncAtElapsedMs < PROGRESS_SYNC_INTERVAL_MS) {
            return
        }

        lastProgressSyncAtElapsedMs = nowElapsedMs
        backgroundScope.launch {
            watchHistoryService.onPlaybackProgress(
                identity = playbackIdentity,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
            )
        }
    }

    private fun reportPlaybackStopped(playbackIdentity: PlaybackIdentity) {
        if (hasReportedPlaybackStop) {
            return
        }
        hasReportedPlaybackStop = true

        val lastDurationMs = playbackMetrics.durationMs
        if (lastDurationMs <= 0L) {
            return
        }

        backgroundScope.launch {
            watchHistoryService.onPlaybackStopped(
                identity = playbackIdentity,
                positionMs = playbackMetrics.positionMs,
                durationMs = lastDurationMs,
            )
        }
    }

    override fun onCleared() {
        activeIdentity?.let(::reportPlaybackStopped)
        backgroundScope.cancel()
        mediaSessionManager.release()
        playbackController.release()
        super.onCleared()
    }

    companion object {
        fun factory(
            appContext: Context,
            playbackSource: PlaybackSource,
            title: String,
            subtitle: String?,
            artworkUrl: String?,
            identity: PlaybackIdentity?,
            launchSnapshot: PlayerLaunchSnapshot?,
            restorePlaybackIntent: Intent,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerSessionViewModel(
                        appContext = appContext,
                        playbackSource = playbackSource,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        identity = identity,
                        launchSnapshot = launchSnapshot,
                        restorePlaybackIntent = restorePlaybackIntent,
                    ) as T
                }
            }
        }
    }
}

private fun buildFallbackDetails(
    rawId: String?,
    title: String,
    artworkUrl: String?,
    identity: PlaybackIdentity?,
): MediaDetails? {
    val contentId = rawId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedTitle = title.trim().ifBlank { return null }
    val mediaType =
        when (identity?.contentType) {
            MetadataLabMediaType.SERIES -> "series"
            else -> "movie"
        }
    val normalizedArtworkUrl = artworkUrl?.trim()?.ifBlank { null }
    return MediaDetails(
        id = contentId,
        imdbId = identity?.imdbId,
        mediaType = mediaType,
        title = identity?.showTitle?.takeIf { mediaType == "series" } ?: normalizedTitle,
        posterUrl = normalizedArtworkUrl,
        backdropUrl = normalizedArtworkUrl,
        logoUrl = null,
        description = null,
        genres = emptyList(),
        year = identity?.year?.toString(),
        runtime = null,
        certification = null,
        rating = null,
        cast = emptyList(),
        directors = emptyList(),
        creators = emptyList(),
        videos = emptyList(),
        addonId = null,
    )
}

private class PlaybackMetricsHolder {
    var positionMs: Long = 0L
    var durationMs: Long = 0L
}

private const val TAG = "PlayerSessionViewModel"
private const val PROGRESS_SYNC_INTERVAL_MS = 5_000L
