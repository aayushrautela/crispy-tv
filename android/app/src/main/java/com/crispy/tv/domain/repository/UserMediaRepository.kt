package com.crispy.tv.domain.repository

import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot

interface UserMediaRepository {
    suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot?

    suspend fun getTitleWatchState(
        itemId: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot?

    suspend fun getCanonicalContinueWatching(
        limit: Int = 20,
        nowMs: Long = System.currentTimeMillis(),
    ): CanonicalContinueWatchingResult

    suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot?

    suspend fun markWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult

    suspend fun unmarkWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult

    suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult

    suspend fun setTitleInWatchlist(
        itemId: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult

    suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
    ): WatchHistoryResult

    suspend fun setTitleRating(
        itemId: String,
        rating: Int?,
    ): WatchHistoryResult
}
