package com.crispy.tv.player

enum class WatchProvider {
    LOCAL,
    TRAKT,
    SIMKL
}

data class WatchProviderSession(
    val accessToken: String,
    val expiresAtEpochMs: Long? = null,
    val userHandle: String? = null,
    val connectedAtEpochMs: Long = System.currentTimeMillis()
)

data class WatchProviderAuthState(
    val traktAuthenticated: Boolean = false,
    val simklAuthenticated: Boolean = false,
    val traktSession: WatchProviderSession? = null,
    val simklSession: WatchProviderSession? = null
)

data class WatchHistoryEntry(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long
)

data class WatchHistoryRequest(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val remoteImdbId: String? = null
)

data class WatchHistoryResult(
    val statusMessage: String,
    val entries: List<WatchHistoryEntry> = emptyList(),
    val authState: WatchProviderAuthState = WatchProviderAuthState(),
    val syncedToTrakt: Boolean = false,
    val syncedToSimkl: Boolean = false
)

data class ContinueWatchingEntry(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val progressPercent: Double,
    val lastUpdatedEpochMs: Long,
    val provider: WatchProvider,
    val providerPlaybackId: String? = null,
    val isUpNextPlaceholder: Boolean = false
)

data class ContinueWatchingResult(
    val statusMessage: String,
    val entries: List<ContinueWatchingEntry> = emptyList(),
    val isError: Boolean = false,
)

data class WatchedEpisodeRecord(
    val contentId: String,
    val season: Int,
    val episode: Int,
    val watchedAtEpochMs: Long,
)

data class ProviderLibraryFolder(
    val id: String,
    val label: String,
    val provider: WatchProvider,
    val itemCount: Int
)

data class ProviderExternalIds(
    val tmdb: Int? = null,
    val imdb: String? = null,
    val tvdb: Int? = null,
)

data class ProviderLibraryItem(
    val provider: WatchProvider,
    val folderId: String,
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val externalIds: ProviderExternalIds? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val addedAtEpochMs: Long = System.currentTimeMillis()
)

data class ProviderLibrarySnapshot(
    val statusMessage: String,
    val folders: List<ProviderLibraryFolder> = emptyList(),
    val items: List<ProviderLibraryItem> = emptyList()
)

enum class ProviderCommentScope {
    MOVIE,
    SHOW,
    SEASON,
    EPISODE
}

data class ProviderCommentQuery(
    val scope: ProviderCommentScope,
    val imdbId: String,
    val tmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val page: Int = 1,
    val limit: Int = 10
)

data class ProviderComment(
    val id: String,
    val username: String,
    val text: String,
    val spoiler: Boolean,
    val createdAtEpochMs: Long,
    val likes: Int
)

data class ProviderCommentResult(
    val statusMessage: String,
    val comments: List<ProviderComment> = emptyList()
)

data class ProviderAuthStartResult(
    val authorizationUrl: String,
    val statusMessage: String
)

data class ProviderAuthActionResult(
    val success: Boolean,
    val statusMessage: String,
    val authState: WatchProviderAuthState = WatchProviderAuthState()
)

data class ProviderSessionBackendResult(
    val session: WatchProviderSession? = null,
    val errorMessage: String? = null,
)

data class ProviderSessionDisconnectResult(
    val success: Boolean,
    val errorMessage: String? = null,
)

interface ProviderSessionBackend {
    fun isConfigured(): Boolean

    suspend fun exchangeProviderSession(
        provider: WatchProvider,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): ProviderSessionBackendResult

    suspend fun resolveProviderSession(
        provider: WatchProvider,
        forceRefresh: Boolean = false,
    ): ProviderSessionBackendResult

    suspend fun disconnectProviderSession(provider: WatchProvider): ProviderSessionDisconnectResult
}

data class PlaybackIdentity(
    val contentId: String? = null,
    val imdbId: String?,
    val tmdbId: Int? = null,
    val contentType: MetadataLabMediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val year: Int? = null,
    val showTitle: String? = null,
    val showYear: Int? = null
)

data class WatchProgressSnapshot(
    val currentTimeSeconds: Double,
    val durationSeconds: Double,
    val lastUpdatedEpochMs: Long
) {
    val progressPercent: Double
        get() = if (durationSeconds <= 0.0) 0.0 else (currentTimeSeconds / durationSeconds) * 100.0
}

data class WatchProgressSyncResult(
    val statusMessage: String,
    val updatedCount: Int = 0
)

interface WatchHistoryService {
    fun clearCachedProviderAuthState() {
    }

    suspend fun disconnectProvider(provider: WatchProvider): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Provider disconnect unavailable.")
    }

    fun authState(): WatchProviderAuthState

    suspend fun refreshProviderAuthState(forceRefresh: Boolean = false): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = true, statusMessage = "", authState = authState())
    }

    suspend fun listLocalHistory(limit: Int = 100): WatchHistoryResult

    suspend fun exportLocalHistory(): List<WatchHistoryEntry>

    suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryResult

    suspend fun markWatched(
        request: WatchHistoryRequest,
        source: WatchProvider? = null
    ): WatchHistoryResult

    suspend fun unmarkWatched(
        request: WatchHistoryRequest,
        source: WatchProvider? = null
    ): WatchHistoryResult

    suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
        source: WatchProvider? = null
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watchlist unavailable.")
    }

    suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
        source: WatchProvider? = null
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Rating unavailable.")
    }

    suspend fun removeFromPlayback(
        playbackId: String,
        source: WatchProvider? = null
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Playback removal unavailable.")
    }

    suspend fun listContinueWatching(
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis(),
        source: WatchProvider? = null
    ): ContinueWatchingResult {
        return ContinueWatchingResult(statusMessage = "Continue watching unavailable.", isError = true)
    }

    suspend fun getCachedContinueWatching(
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis(),
        source: WatchProvider? = null
    ): ContinueWatchingResult {
        return ContinueWatchingResult(statusMessage = "Cached continue watching unavailable.", isError = true)
    }

    suspend fun listWatchedEpisodeRecords(
        source: WatchProvider? = null,
    ): List<WatchedEpisodeRecord> {
        return emptyList()
    }

    suspend fun listProviderLibrary(
        limitPerFolder: Int = 200,
        source: WatchProvider? = null
    ): ProviderLibrarySnapshot {
        return ProviderLibrarySnapshot(statusMessage = "Provider library unavailable.")
    }

    suspend fun getCachedProviderLibrary(
        limitPerFolder: Int = 200,
        source: WatchProvider? = null
    ): ProviderLibrarySnapshot {
        return ProviderLibrarySnapshot(statusMessage = "Cached provider library unavailable.")
    }

    suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        return ProviderCommentResult(statusMessage = "Provider comments unavailable.")
    }

    suspend fun beginTraktOAuth(): ProviderAuthStartResult? {
        return null
    }

    suspend fun completeTraktOAuth(callbackUri: String): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Trakt OAuth unavailable.")
    }

    suspend fun beginSimklOAuth(): ProviderAuthStartResult? {
        return null
    }

    suspend fun completeSimklOAuth(callbackUri: String): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Simkl OAuth unavailable.")
    }

    suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        return null
    }

    suspend fun removeLocalWatchProgress(identity: PlaybackIdentity): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Local watch progress removal unavailable.")
    }

    suspend fun onPlaybackStarted(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
    }

    suspend fun onPlaybackProgress(identity: PlaybackIdentity, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
    }

    suspend fun onPlaybackStopped(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
    }

    suspend fun fetchAndMergeTraktProgress(): WatchProgressSyncResult {
        return WatchProgressSyncResult(statusMessage = "Trakt progress merge unavailable.")
    }

    suspend fun fetchAndMergeSimklProgress(): WatchProgressSyncResult {
        return WatchProgressSyncResult(statusMessage = "Simkl progress merge unavailable.")
    }

    suspend fun syncAllTraktProgress(): WatchProgressSyncResult {
        return WatchProgressSyncResult(statusMessage = "Trakt progress sync unavailable.")
    }

    suspend fun syncAllSimklProgress(): WatchProgressSyncResult {
        return WatchProgressSyncResult(statusMessage = "Simkl progress sync unavailable.")
    }
}

object UnavailableWatchHistoryService : WatchHistoryService {
    override fun clearCachedProviderAuthState() {
    }

    override suspend fun disconnectProvider(provider: WatchProvider): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Watch history service unavailable.")
    }

    override fun authState(): WatchProviderAuthState {
        return WatchProviderAuthState()
    }

    override suspend fun refreshProviderAuthState(forceRefresh: Boolean): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Watch history service unavailable.")
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return emptyList()
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun markWatched(request: WatchHistoryRequest, source: WatchProvider?): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest, source: WatchProvider?): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun removeFromPlayback(playbackId: String, source: WatchProvider?): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun listContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?
    ): ContinueWatchingResult {
        return ContinueWatchingResult(statusMessage = "Watch history service unavailable.", isError = true)
    }

    override suspend fun listProviderLibrary(
        limitPerFolder: Int,
        source: WatchProvider?
    ): ProviderLibrarySnapshot {
        return ProviderLibrarySnapshot(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        return ProviderCommentResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun beginTraktOAuth(): ProviderAuthStartResult? {
        return null
    }

    override suspend fun completeTraktOAuth(callbackUri: String): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Watch history service unavailable.")
    }

    override suspend fun beginSimklOAuth(): ProviderAuthStartResult? {
        return null
    }

    override suspend fun completeSimklOAuth(callbackUri: String): ProviderAuthActionResult {
        return ProviderAuthActionResult(success = false, statusMessage = "Watch history service unavailable.")
    }
}
