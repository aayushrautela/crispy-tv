package com.crispy.rewrite.player

enum class WatchProvider {
    LOCAL,
    TRAKT,
    SIMKL
}

data class WatchProviderSession(
    val accessToken: String,
    val refreshToken: String? = null,
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

data class WatchHistoryLabResult(
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
    val providerPlaybackId: String? = null
)

data class ContinueWatchingLabResult(
    val statusMessage: String,
    val entries: List<ContinueWatchingEntry> = emptyList()
)

data class ProviderLibraryFolder(
    val id: String,
    val label: String,
    val provider: WatchProvider,
    val itemCount: Int
)

data class ProviderLibraryItem(
    val provider: WatchProvider,
    val folderId: String,
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
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

interface WatchHistoryLabService {
    fun connectProvider(
        provider: WatchProvider,
        accessToken: String,
        refreshToken: String? = null,
        expiresAtEpochMs: Long? = null,
        userHandle: String? = null
    ) {
    }

    fun disconnectProvider(provider: WatchProvider) {
    }

    fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        val trakt = traktAccessToken.trim()
        if (trakt.isBlank()) {
            disconnectProvider(WatchProvider.TRAKT)
        } else {
            connectProvider(provider = WatchProvider.TRAKT, accessToken = trakt)
        }

        val simkl = simklAccessToken.trim()
        if (simkl.isBlank()) {
            disconnectProvider(WatchProvider.SIMKL)
        } else {
            connectProvider(provider = WatchProvider.SIMKL, accessToken = simkl)
        }
    }

    fun authState(): WatchProviderAuthState

    suspend fun listLocalHistory(limit: Int = 100): WatchHistoryLabResult

    suspend fun exportLocalHistory(): List<WatchHistoryEntry>

    suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult

    suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult

    suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult

    suspend fun listContinueWatching(limit: Int = 20, nowMs: Long = System.currentTimeMillis()): ContinueWatchingLabResult {
        return ContinueWatchingLabResult(statusMessage = "Continue watching unavailable.")
    }

    suspend fun listProviderLibrary(limitPerFolder: Int = 200): ProviderLibrarySnapshot {
        return ProviderLibrarySnapshot(statusMessage = "Provider library unavailable.")
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
}

object DefaultWatchHistoryLabService : WatchHistoryLabService {
    override fun connectProvider(
        provider: WatchProvider,
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMs: Long?,
        userHandle: String?
    ) {
    }

    override fun disconnectProvider(provider: WatchProvider) {
    }

    override fun authState(): WatchProviderAuthState {
        return WatchProviderAuthState()
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryLabResult {
        return WatchHistoryLabResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return emptyList()
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult {
        return WatchHistoryLabResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        return WatchHistoryLabResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        return WatchHistoryLabResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun listContinueWatching(limit: Int, nowMs: Long): ContinueWatchingLabResult {
        return ContinueWatchingLabResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int): ProviderLibrarySnapshot {
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
