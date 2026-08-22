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
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.metadata.toMediaDetails
import com.crispy.tv.metadata.toMediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.playback.PlayerStreamLookupTarget
import com.crispy.tv.playback.applyProviderResult
import com.crispy.tv.playback.buildPlayerSubtitle
import com.crispy.tv.playback.finalizeFrom
import com.crispy.tv.playback.findEpisodeForLookupId
import com.crispy.tv.playback.matchesTarget
import com.crispy.tv.playback.resolveStreamLookupTarget
import com.crispy.tv.playback.resolveStreamLookupTargetFromIdentity
import com.crispy.tv.playback.toUiState
import com.crispy.tv.home.HomeRefreshBus
import com.crispy.tv.home.HomeRefreshEvent
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.NativePlaybackError
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.nativeengine.playback.NativeVideoLayout
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackExternalSubtitle
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.TorrentResolver
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.AddonSubtitle
import com.crispy.tv.streams.ProviderStreamsResult
import com.crispy.tv.streams.StreamResolver
import com.crispy.tv.streams.StreamSelectorUiState
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
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
    AUDIO,
    SUBTITLES,
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
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val streamSelector: StreamSelectorUiState = StreamSelectorUiState(),
    val currentPlaybackUrl: String? = null,
    val audioTracks: List<NativeTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitleTracks: List<NativeTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val subtitleDelayMs: Int = 0,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    val addonSubtitles: List<AddonSubtitle> = emptyList(),
    val addonSubtitlesLoading: Boolean = false,
    val addonSubtitlesError: String? = null,
    val selectedAddonSubtitleId: String? = null,
)

class PlayerSessionViewModel(
    appContext: Context,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    identity: PlaybackIdentity?,
    resumePositionMs: Long = 0L,
    chosenStreamStableKey: String? = null,
    chosenProviderId: String? = null,
    restorePlaybackIntent: Intent,
) : ViewModel() {
    private val appContext = appContext.applicationContext
    private val resumePositionMs = resumePositionMs
    private val chosenStreamStableKey = chosenStreamStableKey?.trim()?.takeIf { it.isNotBlank() }
    private val chosenProviderId = chosenProviderId?.trim()?.takeIf { it.isNotBlank() }
    private val supabase = SupabaseServicesProvider.accountClient(this.appContext)
    private val backendClient: CrispyBackendClient = BackendServicesProvider.backendClient(this.appContext)
    private val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(this.appContext)
    private val streamResolver: StreamResolver = PlaybackDependencies.streamResolverFactory(this.appContext)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackMetrics = PlaybackMetricsHolder()
    private val playbackController: PlaybackController = PlaybackDependencies.playbackControllerFactory(this.appContext)
    private val audioFocusManager = PlaybackDependencies.getAudioFocusManager(this.appContext)
    private val torrentResolver: TorrentResolver = PlaybackDependencies.getTorrentResolver(this.appContext)
    private val playbackSettingsRepository: PlaybackSettingsRepository =
        PlaybackSettingsRepositoryProvider.get(this.appContext)
    private val subtitleRepository: SubtitleRepository = SubtitleRepository(streamResolver)
    private var activeSubtitleLookupId: String? = null
    private var activeSubtitleMediaType: MetadataLabMediaType? = null
    private val mediaSessionManager =
        PlayerMediaSessionManager(
            context = this.appContext,
            playbackController = playbackController,
            restorePlaybackIntent = restorePlaybackIntent,
        )

    private val rawPlaybackId = buildPlaybackRawId(identity = identity)
    private val seasonEpisodesCache = mutableMapOf<Int, List<MediaVideo>>()
    private var activePlaybackSource = PlaybackSource(url = "")
    private var activeIdentity: PlaybackIdentity? = identity
    private var activeSubtitle: String? = subtitle?.trim()?.ifBlank { null }
    private var activeArtworkUrl: String? = artworkUrl?.trim()?.ifBlank { null }
    private var lastHandledErrorToken: Long? = null
    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs = 0L
    private var seekSettleUntilElapsedMs = 0L
    private var seekSettleJob: Job? = null
    private var pendingInitialSeekMs: Long? = null
    private var streamSelectorSession = 0L
    private var streamSelectorJob: Job? = null

    private val initialDetails =
        buildFallbackDetails(
            rawId = rawPlaybackId,
            title = title,
            artworkUrl = artworkUrl,
            identity = identity,
        )
    private val initialEngine: NativePlaybackEngine =
        resolveInitialEngine(playbackSettingsRepository.settings.value.playbackEnginePreference)
    private val initialSelectedSeason = identity?.season
    private val initialSeasonEpisodes = emptyList<MediaVideo>()

    private val _uiState =
        MutableStateFlow(
            PlayerUiState(
                title = title.ifBlank { "Player" },
                subtitle = activeSubtitle,
                artworkUrl = activeArtworkUrl,
                backdropUrl = initialDetails?.backdropUrl,
                details = initialDetails,
                activeIdentity = activeIdentity,
                seasons = emptyList(),
                selectedSeason = initialSelectedSeason,
                seasonEpisodes = initialSeasonEpisodes,
                currentPlaybackUrl = null,
                activeEngine = initialEngine,
                resizeMode = playbackSettingsRepository.settings.value.resizeMode,
            )
        )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        audioFocusManager.registerSource("main") { setPlaying(false) }
        mediaSessionManager.updateMetadata(
            title = uiState.value.title,
            subtitle = uiState.value.subtitle,
            artworkUrl = uiState.value.artworkUrl,
        )
        if (resumePositionMs > 0L) {
            pendingInitialSeekMs = resumePositionMs
        }
        viewModelScope.launch {
            loadInitialMetadata()
        }
        viewModelScope.launch {
            pollPlaybackState()
        }
        viewModelScope.launch {
            subtitleRepository.addonSubtitles.collect { subtitles ->
                _uiState.update { it.copy(addonSubtitles = subtitles) }
            }
        }
        viewModelScope.launch {
            subtitleRepository.isLoading.collect { loading ->
                _uiState.update { it.copy(addonSubtitlesLoading = loading) }
            }
        }
        viewModelScope.launch {
            subtitleRepository.error.collect { error ->
                _uiState.update { it.copy(addonSubtitlesError = error) }
            }
        }
        viewModelScope.launch {
            startPlayback()
        }
    }

    fun bindExoPlayerView(playerView: PlayerView) {
        playbackController.bindExoPlayerView(playerView)
    }

    fun createMpvSurfaceView(context: Context): SurfaceView = playbackController.createMpvSurfaceView(context)

    fun attachMpvSurface(surfaceView: SurfaceView) {
        playbackController.attachMpvSurface(surfaceView)
    }

    fun setPlaying(isPlaying: Boolean) {
        playbackController.setPlaying(isPlaying)
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun onForegroundStart() {
        val snapshot = playbackController.snapshot()
        if (snapshot.isBuffering || snapshot.state == NativePlaybackState.PAUSED) {
            return
        }
        if (!snapshot.isPlaying && snapshot.state != NativePlaybackState.ERROR && snapshot.state != NativePlaybackState.ENDED) {
            playbackController.setPlaying(true)
            syncPlaybackSnapshot(playbackController.snapshot())
        }
    }

    fun onBackgroundStop() {
        val snapshot = playbackController.snapshot()
        if (snapshot.isPlaying) {
            playbackController.setPlaying(false)
            syncPlaybackSnapshot(playbackController.snapshot())
        }
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
        syncPlaybackSnapshot(playbackController.snapshot())
        scheduleProgressSyncAfterSeek()
    }

    fun setResizeMode(mode: PlayerResizeMode) {
        playbackSettingsRepository.setResizeMode(mode)
        _uiState.update { it.copy(resizeMode = mode) }
    }

    fun applyResizeMode(mode: PlayerResizeMode) {
        playbackController.applyResizeMode(mode)
    }

    fun selectAudioTrack(trackId: String?) {
        playbackController.selectAudioTrack(trackId)
        trackId?.let { playbackSettingsRepository.setDefaultAudioLanguage(languageFromTrack(trackId) ?: return@let) }
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun selectSubtitleTrack(trackId: String?) {
        playbackController.selectSubtitleTrack(trackId)
        trackId?.let { playbackSettingsRepository.setDefaultSubtitleLanguage(languageFromTrack(trackId) ?: return@let) }
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun setExternalSubtitle(url: String, language: String? = null, name: String? = null) {
        playbackController.setExternalSubtitle(
            PlaybackExternalSubtitle(url = url, language = language, name = name),
        )
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        playbackController.setSubtitleDelayMs(delayMs)
        _uiState.update { it.copy(subtitleDelayMs = delayMs) }
    }

    fun fetchAddonSubtitles() {
        val lookupId = activeSubtitleLookupId
        val mediaType = activeSubtitleMediaType
        if (lookupId == null || mediaType == null) {
            Log.d(TAG, "fetchAddonSubtitles skipped: activeSubtitleLookupId=$lookupId mediaType=$mediaType")
            return
        }
        Log.d(TAG, "fetchAddonSubtitles lookupId=$lookupId mediaType=$mediaType")
        subtitleRepository.fetchAddonSubtitles(mediaType, lookupId)
    }

    fun selectAddonSubtitle(subtitle: AddonSubtitle) {
        clearSelectedAddonSubtitle()
        playbackController.setExternalSubtitle(
            PlaybackExternalSubtitle(url = subtitle.url, language = subtitle.language, name = subtitle.display),
        )
        _uiState.update { it.copy(selectedAddonSubtitleId = subtitle.id) }
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    fun clearSelectedAddonSubtitle() {
        if (uiState.value.selectedAddonSubtitleId != null) {
            _uiState.update { it.copy(selectedAddonSubtitleId = null) }
        }
    }

    private fun languageFromTrack(trackId: String): String? {
        val audio = uiState.value.audioTracks.firstOrNull { it.id == trackId }
        val sub = uiState.value.subtitleTracks.firstOrNull { it.id == trackId }
        return audio?.language ?: sub?.language
    }

    fun showInfo() {
        streamSelectorSession++
        _uiState.update { state ->
            state.copy(
                activeSurface = PlayerSurface.INFO,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
    }

    fun showAudioTracks() {
        streamSelectorSession++
        _uiState.update { state ->
            state.copy(
                activeSurface = PlayerSurface.AUDIO,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
    }

    fun showSubtitles() {
        streamSelectorSession++
        _uiState.update { state ->
            state.copy(
                activeSurface = PlayerSurface.SUBTITLES,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
        fetchAddonSubtitles()
    }

    fun closeActiveSurface() {
        streamSelectorJob?.cancel()
        streamSelectorSession++
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

    private suspend fun startPlayback() {
        val identity = activeIdentity ?: run {
            _uiState.update { it.copy(statusMessage = "Unable to resolve playback identity.") }
            return
        }
        val target = resolveStreamLookupTargetFromIdentity(identity)
        if (target.lookupId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Unable to resolve stream lookup id for this title.") }
            return
        }

        activeSubtitleLookupId = target.lookupId
        activeSubtitleMediaType = target.mediaType
        val session = streamSelectorSession

        val results =
            runCatching {
                streamResolver.resolve(
                    target = target,
                    onProvidersResolved = {
                        _uiState.update { previous ->
                            if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                            previous.copy(
                                streamSelector =
                                    previous.streamSelector.copy(
                                        visible = true,
                                        mediaType = target.mediaType,
                                        lookupId = target.lookupId,
                                        providers = emptyList(),
                                        isLoading = true,
                                    ),
                                statusMessage = "",
                            )
                        }
                    },
                    onProviderResult = { result ->
                        _uiState.update { previous ->
                            if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                            val updatedProviders = previous.streamSelector.providers.applyProviderResult(result)
                            previous.copy(
                                streamSelector = previous.streamSelector.copy(
                                    providers = updatedProviders,
                                ),
                                statusMessage = "",
                            )
                        }
                    },
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(statusMessage = error.message ?: "Failed to fetch streams.") }
                return
            }

        _uiState.update { previous ->
            if (!previous.streamSelector.matchesTarget(target)) return@update previous
            previous.copy(streamSelector = previous.streamSelector.copy(isLoading = false))
        }

        val chosen =
            chosenStreamStableKey?.let { key ->
                results.firstNotNullOfOrNull { provider ->
                    provider.streams.firstOrNull { stream ->
                        stream.stableKey == key &&
                            (chosenProviderId == null || stream.providerId.equals(chosenProviderId, ignoreCase = true))
                    }
                }
            }
        when {
            chosen != null -> playResolvedStream(chosen, target)
            playbackSettingsRepository.settings.value.autoSelectStream -> {
                val top = results.firstNotNullOfOrNull { it.streams.firstOrNull() }
                if (top != null) {
                    playResolvedStream(top, target)
                } else {
                    openStreamSelector(target = target, headerEpisode = null)
                }
            }
            else -> openStreamSelector(target = target, headerEpisode = null)
        }
    }

    private suspend fun resolvePlaybackSource(
        stream: AddonStream,
        lookupId: String?,
    ): PlaybackSource? =
        try {
            val source = stream.toPlaybackSource(torrentResolver, lookupId)
            if (source == null) {
                _uiState.update { it.copy(isBuffering = false, statusMessage = "Selected stream has no playable source.") }
            }
            source
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to resolve playback source", error)
            _uiState.update { state ->
                state.copy(
                    isBuffering = false,
                    statusMessage = error.message?.trim()?.takeIf { it.isNotBlank() }
                        ?: "Failed to prepare the selected stream.",
                )
            }
            null
        }

    private suspend fun playResolvedStream(stream: AddonStream, target: PlayerStreamLookupTarget) {
        val source = resolvePlaybackSource(stream, target.lookupId) ?: return
        activePlaybackSource = source
        activeSubtitleLookupId = target.lookupId
        activeSubtitleMediaType = target.mediaType
        pendingInitialSeekMs = resumePositionMs.takeIf { it > 0L }
        _uiState.update { state ->
            state.copy(
                currentPlaybackUrl = source.url,
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "",
                errorMessage = null,
                videoLayout = null,
                activeSurface = PlayerSurface.NONE,
                streamSelector = state.streamSelector.copy(visible = false),
            )
        }
        streamSelectorJob?.cancel()
        streamSelectorSession++
        requestPlayback(engine = uiState.value.activeEngine)
    }

    fun showStreams() {
        val identity = activeIdentity ?: run {
            _uiState.update { it.copy(statusMessage = "Playback identity is unavailable.") }
            return
        }
        val target = resolveStreamLookupTargetFromIdentity(identity)
        if (target.lookupId.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Unable to resolve stream lookup id for this title.") }
            return
        }
        openStreamSelector(target = target, headerEpisode = null)
    }

    fun showStreamsForEpisode(videoId: String) {
        val identity = activeIdentity ?: run {
            _uiState.update { it.copy(statusMessage = "Playback identity is unavailable.") }
            return
        }
        val episode =
            uiState.value.seasonEpisodes.firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
                ?: seasonEpisodesCache.values.asSequence().flatten().firstOrNull { it.id.equals(videoId.trim(), ignoreCase = true) }
        val target =
            PlayerStreamLookupTarget(
                mediaType = identity.contentType,
                lookupId = episode?.lookupId?.trim().orEmpty(),
            )
        val headerEpisode =
            episode ?: findEpisodeForLookupId(
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
        val session = streamSelectorSession

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
                statusMessage = "",
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    streamResolver.loadProviderStreams(
                        mediaType = mediaType,
                        lookupId = lookupId,
                        providerId = normalizedProviderId,
                    )
                }
            }.onSuccess { result ->
                if (session != streamSelectorSession) return@onSuccess
                if (result == null) {
                    _uiState.update { it.copy(statusMessage = "Provider is unavailable.") }
                    return@onSuccess
                }
                _uiState.update { state ->
                    val providers = state.streamSelector.providers.applyProviderResult(result)
                    state.copy(
                        streamSelector = state.streamSelector.copy(providers = providers),
                        statusMessage = "",
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (session != streamSelectorSession) return@onFailure
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
        if (!stream.hasPlayableSource) {
            _uiState.update { it.copy(statusMessage = "Selected stream has no playable source.") }
            return
        }

        val state = uiState.value
        val details = state.details ?: return
        val selectedEpisode =
            state.streamSelector.headerEpisode
                ?: findEpisodeForLookupId(
                    lookupId = state.streamSelector.lookupId.orEmpty(),
                    currentEpisodes = state.seasonEpisodes,
                    cachedEpisodes = seasonEpisodesCache.values,
                )
        val lookupId =
            state.streamSelector.lookupId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.lookupId?.trim()?.takeIf { it.isNotBlank() }
                ?: resolveStreamLookupTarget(
                    details = details,
                    selectedSeason = uiState.value.selectedSeason,
                    seasonEpisodes = uiState.value.seasonEpisodes,
                    fallbackMediaType = activeIdentity?.contentType ?: MetadataLabMediaType.MOVIE,
                ).lookupId
        val parsedLookupId = com.crispy.tv.playback.parseLookupId(lookupId)
        val nextMediaType = state.streamSelector.mediaType ?: activeIdentity?.contentType ?: MetadataLabMediaType.MOVIE
        activeSubtitleLookupId = lookupId
        activeSubtitleMediaType = nextMediaType
        val nextEpisode =
            selectedEpisode
        val isEpisodic = nextMediaType != MetadataLabMediaType.MOVIE
        val parentMediaType =
            when (nextMediaType) {
                MetadataLabMediaType.MOVIE -> null
                MetadataLabMediaType.SERIES -> "show"
                MetadataLabMediaType.ANIME -> "anime"
            }

        val nextSeason = if (isEpisodic) nextEpisode?.season ?: parsedLookupId.season else null
        val nextEpisodeNumber = if (isEpisodic) nextEpisode?.episode ?: parsedLookupId.episode else null
        val nextTitle = nextEpisode?.title?.trim()?.takeIf { it.isNotBlank() } ?: details.title.trim().ifBlank { "Player" }
        val nextSubtitle = buildPlayerSubtitle(nextMediaType, details, nextTitle, nextSeason, nextEpisodeNumber)
        val nextIdentity =
            PlaybackIdentity(
                itemId = details.itemId,
                contentType = nextMediaType,
                season = nextSeason,
                episode = nextEpisodeNumber,
                title = nextTitle,
                year = details.year?.trim()?.toIntOrNull(),
                showTitle = if (isEpisodic) details.title else null,
                showYear = if (isEpisodic) details.year?.trim()?.toIntOrNull() else null,
                parentMediaType = details.parentMediaType ?: parentMediaType,
                absoluteEpisodeNumber = nextEpisode?.absoluteEpisodeNumber ?: details.absoluteEpisodeNumber,
            )

        val sameEpisode =
            nextIdentity.contentType == activeIdentity?.contentType &&
                nextIdentity.season == activeIdentity?.season &&
                nextIdentity.episode == activeIdentity?.episode
        val resumePositionMs = if (sameEpisode) uiState.value.positionMs else 0L

        viewModelScope.launch {
            val source = resolvePlaybackSource(stream, state.streamSelector.lookupId) ?: return@launch
            switchPlayback(
                source = source,
                identity = nextIdentity,
                title = nextTitle,
                subtitle = nextSubtitle,
                artworkUrl = activeArtworkUrl,
                resumePositionMs = resumePositionMs,
            )
        }
    }

    fun retryPlayback() {
        _uiState.update { state ->
            state.copy(
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "",
                errorMessage = null,
                videoLayout = null,
            )
        }
        requestPlayback(engine = uiState.value.activeEngine)
    }

    private suspend fun loadInitialMetadata() {
        val rawId = rawPlaybackId ?: return
        val snapshotDetails = _uiState.value.details
        val itemId =
            activeIdentity?.itemId?.trim()?.takeIf { it.isNotBlank() }
                ?: snapshotDetails?.itemId?.trim()?.takeIf { it.isNotBlank() }
        val session = withContext(Dispatchers.IO) { runCatching { supabase.ensureValidSession() }.getOrNull() }
        val backendDetail =
            if (session != null && itemId != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        backendClient.getMetadataItemDetail(accessToken = session.accessToken, itemId = itemId)
                    }.getOrNull()
                }
            } else {
                null
            }

        val backendDetails = backendDetail?.toMediaDetails()
        val fetchedDetails = backendDetails ?: return

        _uiState.update { state ->
            val selectedSeason =
                state.selectedSeason
                    ?: activeIdentity?.season
            state.copy(
                details = fetchedDetails,
                backdropUrl = fetchedDetails.backdropUrl,
                artworkUrl = state.artworkUrl ?: fetchedDetails.backdropUrl ?: fetchedDetails.posterUrl,
                selectedSeason = selectedSeason,
                seasonEpisodes = emptyList(),
                episodesIsLoading = true,
            )
        }

        val seasonToLoad = _uiState.value.selectedSeason
        if (
            !fetchedDetails.itemType.equals("movie", ignoreCase = true) && seasonToLoad != null
        ) {
            loadEpisodesForSeason(seasonToLoad, force = true)
        }
    }

    private fun loadEpisodesForSeason(
        season: Int,
        force: Boolean = false,
    ) {
        val details = _uiState.value.details ?: return
        if (details.itemType.equals("movie", ignoreCase = true)) return
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
            val session =
                runCatching {
                    withContext(Dispatchers.IO) {
                        supabase.ensureValidSession()
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

            if (session == null) {
                _uiState.update { current ->
                    if (current.selectedSeason != season) {
                        current
                    } else {
                        current.copy(
                            episodesIsLoading = false,
                            episodesStatusMessage = "Sign in to load episodes.",
                        )
                    }
                }
                return@launch
            }

            val response =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val seriesItemId = activeIdentity?.seriesItemId?.trim()?.takeIf { it.isNotBlank() }
                            ?: details.itemId?.trim()?.takeIf { it.isNotBlank() }
                            ?: return@withContext null
                        backendClient.getSeriesEpisodes(
                            accessToken = session.accessToken,
                            seriesItemId = seriesItemId,
                            season = season,
                        )
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
                } ?: run {
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

            val videos = response.items.mapNotNull { it.toMediaVideo() }

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

        streamSelectorJob?.cancel()
        val session = ++streamSelectorSession

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
                statusMessage = "",
            )
        }

        streamSelectorJob = viewModelScope.launch {
            runCatching {
                streamResolver.resolve(
                    target = target,
                    onProvidersResolved = {
                        _uiState.update { previous ->
                            if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                            previous.copy(
                                streamSelector =
                                    previous.streamSelector.copy(
                                        visible = previous.streamSelector.visible,
                                        mediaType = target.mediaType,
                                        lookupId = target.lookupId,
                                        providers = emptyList(),
                                        isLoading = true,
                                    ),
                                statusMessage = "",
                            )
                        }
                    },
                    onProviderResult = { result ->
                        _uiState.update { previous ->
                            if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                            val updatedProviders = previous.streamSelector.providers.applyProviderResult(result)
                            previous.copy(
                                streamSelector = previous.streamSelector.copy(
                                    providers = updatedProviders,
                                ),
                                statusMessage = "",
                            )
                        }
                    },
                )
            }.onSuccess { results ->
                _uiState.update { previous ->
                    if (session != streamSelectorSession || !previous.streamSelector.matchesTarget(target)) return@update previous
                    val finalizedProviders =
                        previous.streamSelector.providers
                            .finalizeFrom(results)
                    previous.copy(
                        streamSelector = previous.streamSelector.copy(
                            visible = previous.streamSelector.visible,
                            mediaType = target.mediaType,
                            lookupId = target.lookupId,
                            providers = finalizedProviders,
                            isLoading = false,
                        ),
                        statusMessage = "",
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (session != streamSelectorSession) return@onFailure
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
        resumePositionMs: Long,
    ) {
        val previousIdentity = activeIdentity
        if (previousIdentity != null && previousIdentity != identity) {
            reportPlaybackStopped(previousIdentity)
        }
        // Fresh playback session: clear reporting state so the next settled poll emits
        // a new playback_started event rather than reusing the previous item's flags.
        hasReportedPlaybackStart = false
        hasReportedPlaybackStop = false
        lastProgressSyncAtElapsedMs = 0L
        seekSettleJob?.cancel()
        seekSettleUntilElapsedMs = 0L

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
                currentPlaybackUrl = source.url,
                activeSurface = PlayerSurface.NONE,
                streamSelector = state.streamSelector.copy(visible = false),
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "",
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
            val shouldUpdateTracks =
                snapshot.audioTracks != state.audioTracks ||
                    snapshot.subtitleTracks != state.subtitleTracks ||
                    snapshot.selectedAudioTrackId != state.selectedAudioTrackId ||
                    snapshot.selectedSubtitleTrackId != state.selectedSubtitleTrackId
            val shouldUpdateSubtitleDelay = snapshot.subtitleDelayMs != state.subtitleDelayMs

            if (!(shouldUpdatePosition || shouldUpdateDuration || shouldUpdatePlaybackState || shouldUpdateTracks || shouldUpdateSubtitleDelay)) {
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
                    audioTracks = snapshot.audioTracks,
                    selectedAudioTrackId = snapshot.selectedAudioTrackId,
                    subtitleTracks = snapshot.subtitleTracks,
                    selectedSubtitleTrackId = snapshot.selectedSubtitleTrackId,
                    subtitleDelayMs = snapshot.subtitleDelayMs,
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
        applyPersistedPlaybackSettings()
        syncPlaybackSnapshot(playbackController.snapshot())
    }

    private fun resolveInitialEngine(preference: NativePlaybackEnginePreference): NativePlaybackEngine =
        if (preference == NativePlaybackEnginePreference.Libmpv) {
            NativePlaybackEngine.MPV
        } else {
            NativePlaybackEngine.EXO
        }

    private fun applyPersistedPlaybackSettings() {
        val settings = playbackSettingsRepository.settings.value
        playbackController.setPlaybackSpeed(settings.playbackSpeed)
        playbackController.setMuted(settings.muted)
    }

    private suspend fun pollPlaybackState() {
        while (coroutineContext.isActive) {
            val snapshot = playbackController.snapshot()

            val fallbackHandled = maybeHandlePlaybackError(snapshot.error, snapshot.engine)
            if (fallbackHandled) {
                publishMediaSessionFromUiState()
                delay(500)
                continue
            }

            applyPendingInitialSeekIfNeeded(snapshot)

            playbackMetrics.positionMs = snapshot.positionMs
            playbackMetrics.durationMs = snapshot.durationMs

            onPlaybackMetrics(snapshot)

            publishMediaSessionFromUiState()
            if (uiState.value.isPlaying) {
                audioFocusManager.acquire("main")
            } else {
                audioFocusManager.release("main")
            }
            syncWatchHistory(
                positionMs = uiState.value.positionMs,
                durationMs = uiState.value.durationMs,
                isPlaying = uiState.value.isPlaying,
            )

            delay(500)
        }
    }

    private fun publishMediaSessionFromUiState() {
        val uiStateSnapshot = uiState.value
        val errorMessage = uiStateSnapshot.errorMessage
        if (errorMessage != null) {
            mediaSessionManager.updatePlaybackError(
                title = uiStateSnapshot.title,
                subtitle = uiStateSnapshot.subtitle,
                artworkUrl = uiStateSnapshot.artworkUrl,
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
                errorMessage = errorMessage,
            )
        } else {
            mediaSessionManager.updatePlayback(
                title = uiStateSnapshot.title,
                subtitle = uiStateSnapshot.subtitle,
                artworkUrl = uiStateSnapshot.artworkUrl,
                isPlaying = uiStateSnapshot.isPlaying,
                isBuffering = uiStateSnapshot.isBuffering,
                positionMs = uiStateSnapshot.positionMs,
                durationMs = uiStateSnapshot.durationMs,
                bufferedPositionMs = uiStateSnapshot.positionMs,
            )
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
        scheduleProgressSyncAfterSeek()
    }

    private fun maybeHandlePlaybackError(
        error: NativePlaybackError?,
        engine: NativePlaybackEngine,
    ): Boolean {
        if (error == null || lastHandledErrorToken == error.token) {
            return false
        }

        lastHandledErrorToken = error.token
        val playbackEnginePreference = playbackSettingsRepository.settings.value.playbackEnginePreference
        val shouldFallback =
            error.codecLikely &&
                engine == NativePlaybackEngine.EXO &&
                playbackEnginePreference != NativePlaybackEnginePreference.ExoPlayer
        if (!shouldFallback) {
            return false
        }

        Log.w(TAG, "ExoPlayer error, retrying on MPV fallback. message=${error.message}")
        _uiState.update { state ->
            state.copy(
                activeEngine = NativePlaybackEngine.MPV,
                isBuffering = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                stableDurationMs = 0L,
                statusMessage = "",
                errorMessage = null,
                videoLayout = null,
            )
        }
        requestPlayback(engine = NativePlaybackEngine.MPV)
        // The engine switch is a brand new playback session: drop the stale start/stop
        // flags and let the settled MPV position emit a fresh playback_started event.
        hasReportedPlaybackStart = false
        hasReportedPlaybackStop = false
        lastProgressSyncAtElapsedMs = 0L
        seekSettleJob?.cancel()
        seekSettleUntilElapsedMs = 0L
        scheduleProgressSyncAfterSeek()
        return true
    }

    private fun syncPlaybackSnapshot(snapshot: NativePlaybackSnapshot) {
        if (maybeHandlePlaybackError(snapshot.error, snapshot.engine)) {
            publishMediaSessionFromUiState()
            return
        }
        onPlaybackMetrics(snapshot)
        publishMediaSessionFromUiState()
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

        // While the player settles after a seek/load/engine switch the engine reports a
        // transient 0. Skip reporting until the settle window passes, then resume.
        if (SystemClock.elapsedRealtime() < seekSettleUntilElapsedMs) {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!hasReportedPlaybackStart && isPlaying && positionMs >= MIN_PROGRESS_POSITION_MS) {
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

    private fun scheduleProgressSyncAfterSeek() {
        seekSettleJob?.cancel()
        seekSettleUntilElapsedMs = SystemClock.elapsedRealtime() + PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS
        seekSettleJob = backgroundScope.launch {
            delay(PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS)
            // Clearing the settle window also forces an immediate progress push on the
            // next poll instead of waiting out the full persist interval.
            seekSettleUntilElapsedMs = 0L
            lastProgressSyncAtElapsedMs = 0L
        }
    }

    private fun reportPlaybackStopped(playbackIdentity: PlaybackIdentity): Job {
        if (hasReportedPlaybackStop) {
            return CompletableDeferred(Unit)
        }
        hasReportedPlaybackStop = true

        val lastDurationMs = playbackMetrics.durationMs
        if (lastDurationMs <= 0L) {
            return CompletableDeferred(Unit)
        }

        return backgroundScope.launch {
            watchHistoryService.onPlaybackStopped(
                identity = playbackIdentity,
                positionMs = playbackMetrics.positionMs,
                durationMs = lastDurationMs,
            )
            HomeRefreshBus.emit(HomeRefreshEvent.PlaybackEnded)
        }
    }

    override fun onCleared() {
        val stopJob = activeIdentity?.let(::reportPlaybackStopped)
        if (stopJob != null) {
            runBlocking { stopJob.join() }
        }
        backgroundScope.cancel()
        audioFocusManager.release("main")
        audioFocusManager.unregisterSource("main")
        mediaSessionManager.release()
        playbackController.release()
        torrentResolver.stopAndClear()
        super.onCleared()
    }

    companion object {
        fun factory(
            appContext: Context,
            title: String,
            subtitle: String?,
            artworkUrl: String?,
            identity: PlaybackIdentity?,
            resumePositionMs: Long = 0L,
            chosenStreamStableKey: String? = null,
            chosenProviderId: String? = null,
            restorePlaybackIntent: Intent,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerSessionViewModel(
                        appContext = appContext,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        identity = identity,
                        resumePositionMs = resumePositionMs,
                        chosenStreamStableKey = chosenStreamStableKey,
                        chosenProviderId = chosenProviderId,
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
    val contentId = identity?.itemId?.trim()?.takeIf { it.isNotBlank() } ?: rawId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedTitle = title.trim().ifBlank { return null }
    val mediaType =
        when (identity?.contentType) {
            MetadataLabMediaType.SERIES -> "show"
            MetadataLabMediaType.ANIME -> "anime"
            else -> "movie"
        }
    val normalizedArtworkUrl = artworkUrl?.trim()?.ifBlank { null }
    return MediaDetails(
        id = contentId,
        itemId = identity?.itemId,
        imdbId = null,
        itemType = mediaType,
        title = identity?.showTitle?.takeIf { mediaType == "show" } ?: normalizedTitle,
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
        seasonNumber = identity?.season,
        episodeNumber = identity?.episode,
        addonId = null,
        parentMediaType = identity?.parentMediaType,
        absoluteEpisodeNumber = identity?.absoluteEpisodeNumber,
    )
}

private class PlaybackMetricsHolder {
    var positionMs: Long = 0L
    var durationMs: Long = 0L
}

private const val TAG = "PlayerSessionViewModel"
private const val PROGRESS_SYNC_INTERVAL_MS = 60_000L
private const val PLAYER_SEEK_PROGRESS_SYNC_DEBOUNCE_MS = 700L
private const val MIN_PROGRESS_POSITION_MS = 1000L
