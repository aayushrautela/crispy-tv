package com.crispy.rewrite.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.domain.player.PlayerAction
import com.crispy.rewrite.domain.player.PlayerIntent
import com.crispy.rewrite.domain.player.PlayerState
import com.crispy.rewrite.domain.player.initialPlayerState
import com.crispy.rewrite.domain.player.reducePlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlaybackEngine {
    EXO,
    VLC
}

data class PlaybackLabUiState(
    val sampleVideoUrl: String = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4",
    val magnetInput: String = "",
    val statusMessage: String = "Ready. Play sample video or paste a magnet.",
    val playbackUrl: String? = null,
    val playbackRequestVersion: Long = 0,
    val activeEngine: PlaybackEngine = PlaybackEngine.EXO,
    val playerState: PlayerState = initialPlayerState(
        sessionId = "playback-lab",
        nowMs = System.currentTimeMillis()
    ),
    val isPreparingSamplePlayback: Boolean = false,
    val isPreparingTorrentPlayback: Boolean = false,
    val isBuffering: Boolean = false,
    val metadataInputId: String = "tmdb:1399:1:2",
    val metadataPreferredAddonId: String = "",
    val metadataMediaType: MetadataLabMediaType = MetadataLabMediaType.SERIES,
    val metadataStatusMessage: String = "Metadata idle. Resolve to apply Nuvio rules.",
    val isResolvingMetadata: Boolean = false,
    val metadataResolution: MetadataLabResolution? = null,
    val catalogMediaType: MetadataLabMediaType = MetadataLabMediaType.SERIES,
    val catalogInputId: String = "",
    val catalogSearchQuery: String = "game of thrones",
    val catalogPreferredAddonId: String = "",
    val catalogStatusMessage: String = "Catalog/search idle. Load a page or run query.",
    val isLoadingCatalog: Boolean = false,
    val catalogItems: List<CatalogLabItem> = emptyList(),
    val catalogAvailableCatalogs: List<CatalogLabCatalog> = emptyList(),
    val catalogAttemptedUrls: List<String> = emptyList(),
    val watchContentType: MetadataLabMediaType = MetadataLabMediaType.SERIES,
    val watchContentId: String = "tt0944947",
    val watchRemoteImdbId: String = "tt0944947",
    val watchTitle: String = "",
    val watchSeasonInput: String = "1",
    val watchEpisodeInput: String = "1",
    val watchTraktToken: String = "",
    val watchSimklToken: String = "",
    val watchStatusMessage: String = "Watch history idle.",
    val isUpdatingWatchHistory: Boolean = false,
    val watchEntries: List<WatchHistoryEntry> = emptyList(),
    val watchAuthState: WatchProviderAuthState = WatchProviderAuthState(),
    val supabaseEmail: String = "",
    val supabasePassword: String = "",
    val supabasePin: String = "",
    val supabaseSyncCode: String = "",
    val supabaseStatusMessage: String = "Supabase sync idle.",
    val isUpdatingSupabase: Boolean = false,
    val supabaseAuthState: SupabaseSyncAuthState = SupabaseSyncAuthState()
)

class PlaybackLabViewModel(
    private val metadataResolver: MetadataLabResolver = DefaultMetadataLabResolver,
    private val catalogSearchService: CatalogSearchLabService = DefaultCatalogSearchLabService,
    private val watchHistoryService: WatchHistoryService = UnavailableWatchHistoryService,
    private val supabaseSyncService: SupabaseSyncLabService = DefaultSupabaseSyncLabService
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackLabUiState())
    val uiState: StateFlow<PlaybackLabUiState> = _uiState.asStateFlow()

    companion object {
        fun factory(
            metadataResolver: MetadataLabResolver,
            catalogSearchService: CatalogSearchLabService,
            watchHistoryService: WatchHistoryService,
            supabaseSyncService: SupabaseSyncLabService
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlaybackLabViewModel(
                        metadataResolver = metadataResolver,
                        catalogSearchService = catalogSearchService,
                        watchHistoryService = watchHistoryService,
                        supabaseSyncService = supabaseSyncService
                    ) as T
                }
            }
        }
    }

    init {
        _uiState.update {
            it.copy(
                watchAuthState = watchHistoryService.authState(),
                supabaseAuthState = supabaseSyncService.authState()
            )
        }
        onRefreshWatchHistoryRequested()
        onInitializeSupabaseRequested()
    }

    fun onPlaySampleRequested() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.OpenHttp(engine = it.activeEngine.toReducerEngine()),
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Loading sample video.",
                playbackUrl = it.sampleVideoUrl,
                playbackRequestVersion = it.playbackRequestVersion + 1,
                activeEngine = nextPlayerState.engine.toPlaybackEngine(),
                playerState = nextPlayerState,
                isPreparingSamplePlayback = true,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onStartTorrentRequested(): String? {
        val magnet = _uiState.value.magnetInput.trim()
        if (!magnet.startsWith("magnet:?")) {
            _uiState.update {
                it.copy(
                    statusMessage = "Invalid magnet URI. It must start with magnet:?",
                    isPreparingTorrentPlayback = false
                )
            }
            return null
        }

        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.OpenTorrent(engine = it.activeEngine.toReducerEngine()),
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Starting torrent session.",
                activeEngine = nextPlayerState.engine.toPlaybackEngine(),
                playerState = nextPlayerState,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = true,
                isBuffering = false
            )
        }
        return magnet
    }

    fun onTorrentStreamResolved(streamUrl: String) {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.TorrentStreamResolved,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Torrent stream resolved, loading player.",
                playbackUrl = streamUrl,
                playbackRequestVersion = it.playbackRequestVersion + 1,
                playerState = nextPlayerState,
                isPreparingTorrentPlayback = false,
                isBuffering = true
            )
        }
    }

    fun onNativeReady() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeReady,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = "Playback ready (${it.activeEngine.name}).",
                playerState = nextPlayerState,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onNativeBuffering() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeBuffering,
                nowMs = nowMs()
            )

            it.copy(
                statusMessage = if (it.playerState.intent == PlayerIntent.PAUSE) {
                    "Paused"
                } else {
                    "Buffering"
                },
                playerState = nextPlayerState,
                isBuffering = true
            )
        }
    }

    fun onNativeEnded() {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeEnded,
                nowMs = nowMs()
            )
            it.copy(
                statusMessage = "Playback ended.",
                playerState = nextPlayerState,
                isBuffering = false
            )
        }
    }

    fun onNativeCodecError(reason: String) {
        _uiState.update {
            val nextPlayerState = reducePlayerState(
                state = it.playerState,
                action = PlayerAction.NativeCodecError,
                nowMs = nowMs()
            )
            val nextEngine = nextPlayerState.engine.toPlaybackEngine()
            val shouldRetry = it.playbackUrl != null && nextEngine != it.activeEngine

            it.copy(
                statusMessage = if (shouldRetry) {
                    "Codec issue detected. Falling back to ${nextEngine.name}."
                } else {
                    "Playback error: $reason"
                },
                playerState = nextPlayerState,
                activeEngine = nextEngine,
                playbackRequestVersion = if (shouldRetry) it.playbackRequestVersion + 1 else it.playbackRequestVersion,
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onPlaybackLaunchFailed(reason: String) {
        _uiState.update {
            it.copy(
                statusMessage = "Playback launch failed: $reason",
                isPreparingSamplePlayback = false,
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onTorrentPlaybackFailed(reason: String) {
        _uiState.update {
            it.copy(
                statusMessage = "Torrent playback failed: $reason",
                isPreparingTorrentPlayback = false,
                isBuffering = false
            )
        }
    }

    fun onMagnetChanged(value: String) {
        _uiState.update { it.copy(magnetInput = value) }
    }

    fun onEngineSelected(engine: PlaybackEngine) {
        _uiState.update {
            val targetEngine = engine.toReducerEngine()
            val alreadySelected = it.activeEngine == engine && it.playerState.engine.equals(targetEngine, ignoreCase = true)
            if (alreadySelected) {
                return@update it
            }

            val shouldReplayCurrent = it.playbackUrl != null
            it.copy(
                activeEngine = engine,
                playerState = it.playerState.copy(
                    engine = targetEngine,
                    updatedAtMs = nowMs()
                ),
                playbackRequestVersion = if (shouldReplayCurrent) it.playbackRequestVersion + 1 else it.playbackRequestVersion,
                statusMessage = if (shouldReplayCurrent) {
                    "Switched engine to ${engine.name}. Restarting current stream."
                } else {
                    "Selected ${engine.name} engine."
                }
            )
        }
    }

    fun onMetadataInputChanged(value: String) {
        _uiState.update { it.copy(metadataInputId = value) }
    }

    fun onMetadataPreferredAddonChanged(value: String) {
        _uiState.update { it.copy(metadataPreferredAddonId = value) }
    }

    fun onMetadataMediaTypeSelected(mediaType: MetadataLabMediaType) {
        _uiState.update { it.copy(metadataMediaType = mediaType) }
    }

    fun onCatalogMediaTypeSelected(mediaType: MetadataLabMediaType) {
        _uiState.update { it.copy(catalogMediaType = mediaType) }
    }

    fun onCatalogIdChanged(value: String) {
        _uiState.update { it.copy(catalogInputId = value) }
    }

    fun onCatalogSearchQueryChanged(value: String) {
        _uiState.update { it.copy(catalogSearchQuery = value) }
    }

    fun onCatalogPreferredAddonChanged(value: String) {
        _uiState.update { it.copy(catalogPreferredAddonId = value) }
    }

    fun onWatchContentTypeSelected(mediaType: MetadataLabMediaType) {
        _uiState.update { it.copy(watchContentType = mediaType) }
    }

    fun onWatchContentIdChanged(value: String) {
        _uiState.update { it.copy(watchContentId = value) }
    }

    fun onWatchRemoteImdbIdChanged(value: String) {
        _uiState.update { it.copy(watchRemoteImdbId = value) }
    }

    fun onWatchTitleChanged(value: String) {
        _uiState.update { it.copy(watchTitle = value) }
    }

    fun onWatchSeasonChanged(value: String) {
        _uiState.update { it.copy(watchSeasonInput = value) }
    }

    fun onWatchEpisodeChanged(value: String) {
        _uiState.update { it.copy(watchEpisodeInput = value) }
    }

    fun onWatchTraktTokenChanged(value: String) {
        _uiState.update { it.copy(watchTraktToken = value) }
    }

    fun onWatchSimklTokenChanged(value: String) {
        _uiState.update { it.copy(watchSimklToken = value) }
    }

    fun onSaveWatchTokensRequested() {
        val snapshot = _uiState.value
        watchHistoryService.updateAuthTokens(
            traktAccessToken = snapshot.watchTraktToken,
            simklAccessToken = snapshot.watchSimklToken
        )
        _uiState.update {
            it.copy(
                watchAuthState = watchHistoryService.authState(),
                watchStatusMessage = "Saved Trakt/Simkl tokens for sync."
            )
        }
    }

    fun onRefreshWatchHistoryRequested() {
        _uiState.update {
            it.copy(
                isUpdatingWatchHistory = true,
                watchStatusMessage = "Loading local watch history..."
            )
        }

        viewModelScope.launch {
            runCatching {
                watchHistoryService.listLocalHistory()
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = result.statusMessage,
                        watchEntries = result.entries,
                        watchAuthState = result.authState
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = "Failed to load watch history: ${error.message ?: "unknown error"}",
                        watchEntries = emptyList(),
                        watchAuthState = watchHistoryService.authState()
                    )
                }
            }
        }
    }

    fun onMarkWatchedRequested() {
        val snapshot = _uiState.value
        val request = buildWatchRequest(snapshot) ?: return

        _uiState.update {
            it.copy(
                isUpdatingWatchHistory = true,
                watchStatusMessage = "Marking watched and syncing providers..."
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    watchHistoryService.markWatched(request)
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = result.statusMessage,
                        watchEntries = result.entries,
                        watchAuthState = result.authState
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = "Mark watched failed: ${error.message ?: "unknown error"}",
                        watchAuthState = watchHistoryService.authState()
                    )
                }
            }
        }
    }

    fun onUnmarkWatchedRequested() {
        val snapshot = _uiState.value
        val request = buildWatchRequest(snapshot) ?: return

        _uiState.update {
            it.copy(
                isUpdatingWatchHistory = true,
                watchStatusMessage = "Removing watched state and syncing providers..."
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    watchHistoryService.unmarkWatched(request)
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = result.statusMessage,
                        watchEntries = result.entries,
                        watchAuthState = result.authState
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = "Unmark watched failed: ${error.message ?: "unknown error"}",
                        watchAuthState = watchHistoryService.authState()
                    )
                }
            }
        }
    }

    fun onSupabaseEmailChanged(value: String) {
        _uiState.update { it.copy(supabaseEmail = value) }
    }

    fun onSupabasePasswordChanged(value: String) {
        _uiState.update { it.copy(supabasePassword = value) }
    }

    fun onSupabasePinChanged(value: String) {
        _uiState.update { it.copy(supabasePin = value) }
    }

    fun onSupabaseSyncCodeChanged(value: String) {
        _uiState.update { it.copy(supabaseSyncCode = value) }
    }

    fun onInitializeSupabaseRequested() {
        runSupabaseAction(
            workingMessage = "Initializing Supabase sync...",
            refreshWatchHistory = true
        ) {
            supabaseSyncService.initialize()
        }
    }

    fun onSupabaseSignUpRequested() {
        val snapshot = _uiState.value
        runSupabaseAction("Creating Supabase account...") {
            supabaseSyncService.signUpWithEmail(
                email = snapshot.supabaseEmail,
                password = snapshot.supabasePassword
            )
        }
    }

    fun onSupabaseSignInRequested() {
        val snapshot = _uiState.value
        runSupabaseAction("Signing in to Supabase...") {
            supabaseSyncService.signInWithEmail(
                email = snapshot.supabaseEmail,
                password = snapshot.supabasePassword
            )
        }
    }

    fun onSupabaseSignOutRequested() {
        runSupabaseAction("Signing out from Supabase...") {
            supabaseSyncService.signOut()
        }
    }

    fun onSupabasePushRequested() {
        runSupabaseAction("Pushing local data to Supabase...") {
            supabaseSyncService.pushAllLocalData()
        }
    }

    fun onSupabasePullRequested() {
        runSupabaseAction(
            workingMessage = "Pulling cloud data from Supabase...",
            refreshWatchHistory = true
        ) {
            supabaseSyncService.pullAllToLocal()
        }
    }

    fun onSupabaseSyncNowRequested() {
        runSupabaseAction(
            workingMessage = "Running Supabase sync now...",
            refreshWatchHistory = true
        ) {
            supabaseSyncService.syncNow()
        }
    }

    fun onSupabaseGenerateCodeRequested() {
        val pin = _uiState.value.supabasePin.trim()
        if (pin.isEmpty()) {
            _uiState.update {
                it.copy(
                    supabaseStatusMessage = "PIN is required to generate a sync code.",
                    isUpdatingSupabase = false
                )
            }
            return
        }

        runSupabaseAction("Generating Supabase sync code...") {
            supabaseSyncService.generateSyncCode(pin)
        }
    }

    fun onSupabaseClaimCodeRequested() {
        val snapshot = _uiState.value
        val code = snapshot.supabaseSyncCode.trim()
        val pin = snapshot.supabasePin.trim()
        if (code.isEmpty() || pin.isEmpty()) {
            _uiState.update {
                it.copy(
                    supabaseStatusMessage = "Sync code and PIN are required to claim.",
                    isUpdatingSupabase = false
                )
            }
            return
        }

        runSupabaseAction(
            workingMessage = "Claiming Supabase sync code...",
            refreshWatchHistory = true
        ) {
            supabaseSyncService.claimSyncCode(code = code, pin = pin)
        }
    }

    fun onLoadCatalogRequested() {
        val snapshot = _uiState.value
        val catalogId = snapshot.catalogInputId.trim()

        val preferredAddonId = snapshot.catalogPreferredAddonId.trim().ifBlank { null }
        _uiState.update {
            it.copy(
                isLoadingCatalog = true,
                catalogStatusMessage =
                    if (catalogId.isEmpty()) {
                        "Loading addon catalog list..."
                    } else {
                        "Loading catalog page..."
                    }
            )
        }

        viewModelScope.launch {
            runCatching {
                catalogSearchService.fetchCatalogPage(
                    CatalogPageRequest(
                        mediaType = snapshot.catalogMediaType,
                        catalogId = catalogId,
                        preferredAddonId = preferredAddonId
                    )
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        catalogStatusMessage = result.statusMessage,
                        catalogItems = result.items,
                        catalogAvailableCatalogs = result.catalogs,
                        catalogAttemptedUrls = result.attemptedUrls
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        catalogStatusMessage = "Catalog load failed: ${error.message ?: "unknown error"}",
                        catalogItems = emptyList(),
                        catalogAttemptedUrls = emptyList()
                    )
                }
            }
        }
    }

    fun onSearchCatalogRequested() {
        val snapshot = _uiState.value
        val query = snapshot.catalogSearchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update {
                it.copy(
                    catalogStatusMessage = "Search query is required.",
                    isLoadingCatalog = false
                )
            }
            return
        }

        val preferredAddonId = snapshot.catalogPreferredAddonId.trim().ifBlank { null }
        _uiState.update {
            it.copy(
                isLoadingCatalog = true,
                catalogStatusMessage = "Searching addons..."
            )
        }

        viewModelScope.launch {
            runCatching {
                catalogSearchService.search(
                    CatalogSearchRequest(
                        mediaType = snapshot.catalogMediaType,
                        query = query,
                        preferredAddonId = preferredAddonId
                    )
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        catalogStatusMessage = result.statusMessage,
                        catalogItems = result.items,
                        catalogAvailableCatalogs = result.catalogs,
                        catalogAttemptedUrls = result.attemptedUrls
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        catalogStatusMessage = "Search failed: ${error.message ?: "unknown error"}",
                        catalogItems = emptyList(),
                        catalogAttemptedUrls = emptyList()
                    )
                }
            }
        }
    }

    fun onResolveMetadataRequested() {
        val snapshot = _uiState.value
        val preferredAddonId = snapshot.metadataPreferredAddonId.trim().ifBlank { null }
        val request = MetadataLabRequest(
            rawId = snapshot.metadataInputId,
            mediaType = snapshot.metadataMediaType,
            preferredAddonId = preferredAddonId
        )

        if (request.rawId.trim().isEmpty()) {
            _uiState.update {
                it.copy(
                    metadataStatusMessage = "Metadata ID is required.",
                    isResolvingMetadata = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isResolvingMetadata = true,
                metadataStatusMessage = "Resolving addon-first metadata..."
            )
        }

        viewModelScope.launch {
            runCatching {
                metadataResolver.resolve(request)
            }.onSuccess { resolution ->
                _uiState.update {
                    it.copy(
                        metadataResolution = resolution,
                        isResolvingMetadata = false,
                        metadataStatusMessage =
                            "Metadata resolved. Sources=${resolution.sources.joinToString()}"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isResolvingMetadata = false,
                        metadataStatusMessage = "Metadata resolution failed: ${error.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun runSupabaseAction(
        workingMessage: String,
        refreshWatchHistory: Boolean = false,
        action: suspend () -> SupabaseSyncLabResult
    ) {
        _uiState.update {
            it.copy(
                isUpdatingSupabase = true,
                supabaseStatusMessage = workingMessage
            )
        }

        viewModelScope.launch {
            runCatching {
                action()
            }.onSuccess { result ->
                _uiState.update { current ->
                    current.copy(
                        isUpdatingSupabase = false,
                        supabaseStatusMessage = result.statusMessage,
                        supabaseAuthState = result.authState,
                        supabaseSyncCode = result.syncCode ?: current.supabaseSyncCode
                    )
                }
                if (refreshWatchHistory) {
                    onRefreshWatchHistoryRequested()
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdatingSupabase = false,
                        supabaseStatusMessage = "Supabase action failed: ${error.message ?: "unknown error"}",
                        supabaseAuthState = supabaseSyncService.authState()
                    )
                }
            }
        }
    }

    private fun buildWatchRequest(snapshot: PlaybackLabUiState): WatchHistoryRequest? {
        val contentId = snapshot.watchContentId.trim()
        if (contentId.isEmpty()) {
            _uiState.update {
                it.copy(
                    isUpdatingWatchHistory = false,
                    watchStatusMessage = "Watch content ID is required."
                )
            }
            return null
        }

        val season = snapshot.watchSeasonInput.trim().toIntOrNull()
        val episode = snapshot.watchEpisodeInput.trim().toIntOrNull()
        if (snapshot.watchContentType == MetadataLabMediaType.SERIES) {
            if (season == null || season <= 0 || episode == null || episode <= 0) {
                _uiState.update {
                    it.copy(
                        isUpdatingWatchHistory = false,
                        watchStatusMessage = "Series watch updates require positive season and episode numbers."
                    )
                }
                return null
            }
        }

        return WatchHistoryRequest(
            contentId = contentId,
            contentType = snapshot.watchContentType,
            title = snapshot.watchTitle,
            season = season,
            episode = episode,
            remoteImdbId = snapshot.watchRemoteImdbId.trim().ifBlank { null }
        )
    }
}

private fun String.toPlaybackEngine(): PlaybackEngine {
    return if (equals("vlc", ignoreCase = true)) PlaybackEngine.VLC else PlaybackEngine.EXO
}

private fun PlaybackEngine.toReducerEngine(): String {
    return when (this) {
        PlaybackEngine.EXO -> "exo"
        PlaybackEngine.VLC -> "vlc"
    }
}
