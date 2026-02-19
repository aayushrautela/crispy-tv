package com.crispy.rewrite.player

data class WatchProviderAuthState(
    val traktAuthenticated: Boolean = false,
    val simklAuthenticated: Boolean = false
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

interface WatchHistoryLabService {
    fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String)

    fun authState(): WatchProviderAuthState

    suspend fun listLocalHistory(limit: Int = 100): WatchHistoryLabResult

    suspend fun exportLocalHistory(): List<WatchHistoryEntry>

    suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult

    suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult

    suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult
}

object DefaultWatchHistoryLabService : WatchHistoryLabService {
    override fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        // No-op default.
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
}
