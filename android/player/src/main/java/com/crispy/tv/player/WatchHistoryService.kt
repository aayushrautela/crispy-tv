package com.crispy.tv.player

data class WatchHistoryEntry(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long
)

data class WatchHistoryRequest(
    val mediaKey: String? = null,
    val contentType: MetadataLabMediaType,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
)

data class WatchHistoryResult(
    val statusMessage: String,
    val entries: List<WatchHistoryEntry> = emptyList(),
    val accepted: Boolean = false,
    val syncedToTrakt: Boolean = false,
    val syncedToSimkl: Boolean = false
)

data class CanonicalContinueWatchingItem(
    val id: String,
    val mediaKey: String,
    val localKey: String = mediaKey,
    val mediaType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val progressPercent: Double,
    val lastUpdatedEpochMs: Long,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val addonId: String? = null,
    val subtitle: String? = null,
    val dismissible: Boolean = false,
    val absoluteEpisodeNumber: Int? = null,
) {
    val type: String
    get() = when (mediaType.lowercase()) {
        "show", "tv", "series", "episode" -> MetadataLabMediaType.SERIES.label
        "anime" -> MetadataLabMediaType.ANIME.label
        else -> MetadataLabMediaType.MOVIE.label
    }

    val watchedAtEpochMs: Long
    get() = lastUpdatedEpochMs
}

data class CanonicalContinueWatchingResult(
    val statusMessage: String,
    val entries: List<CanonicalContinueWatchingItem> = emptyList(),
    val isError: Boolean = false,
)

data class CanonicalWatchStateSnapshot(
    val isWatched: Boolean,
    val watchedAtEpochMs: Long?,
    val isInWatchlist: Boolean,
    val isRated: Boolean,
    val userRating: Int?,
    val watchedEpisodeKeys: Set<String> = emptySet(),
)

data class WatchedEpisodeRecord(
    val contentId: String,
    val season: Int,
    val episode: Int,
    val watchedAtEpochMs: Long,
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


data class PlaybackIdentity(
    val mediaKey: String?,
    val tmdbId: Int? = null,
    val contentType: MetadataLabMediaType,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String,
    val year: Int? = null,
    val showTitle: String? = null,
    val showYear: Int? = null,
    val parentMediaType: String? = null,
    val absoluteEpisodeNumber: Int? = null,
)

data class WatchProgressSnapshot(
    val currentTimeSeconds: Double,
    val durationSeconds: Double,
    val lastUpdatedEpochMs: Long
) {
    val progressPercent: Double
        get() = if (durationSeconds <= 0.0) 0.0 else (currentTimeSeconds / durationSeconds) * 100.0
}

interface WatchHistoryService {
    suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult

    suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult

    suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watchlist unavailable.")
    }

    suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Rating unavailable.")
    }

    suspend fun removeFromPlayback(playbackId: String): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Playback removal unavailable.")
    }

    suspend fun getCanonicalContinueWatching(
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis(),
    ): CanonicalContinueWatchingResult {
        return CanonicalContinueWatchingResult(statusMessage = "Canonical continue watching unavailable.", isError = true)
    }

    suspend fun listWatchedEpisodeRecords(): List<WatchedEpisodeRecord> {
        return emptyList()
    }

    suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        return null
    }

    suspend fun getTitleWatchState(
        mediaKey: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? {
        return null
    }

    suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        return ProviderCommentResult(statusMessage = "Provider comments unavailable.")
    }

    suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        return null
    }

    suspend fun setTitleInWatchlist(
        mediaKey: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watchlist unavailable.")
    }

    suspend fun setTitleRating(
        mediaKey: String,
        rating: Int?,
    ): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Rating unavailable.")
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

}

object UnavailableWatchHistoryService : WatchHistoryService {
    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun removeFromPlayback(playbackId: String): WatchHistoryResult {
        return WatchHistoryResult(statusMessage = "Watch history service unavailable.")
    }

    override suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        return ProviderCommentResult(statusMessage = "Watch history service unavailable.")
    }

}
