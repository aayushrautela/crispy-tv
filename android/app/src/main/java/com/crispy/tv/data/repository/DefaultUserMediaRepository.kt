package com.crispy.tv.data.repository

import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProgressSnapshot

class DefaultUserMediaRepository(
    private val watchHistoryService: WatchHistoryService,
) : UserMediaRepository {
    override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        return watchHistoryService.getCanonicalWatchState(identity)
    }

    override suspend fun getTitleWatchState(
        itemId: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? {
        return watchHistoryService.getTitleWatchState(itemId, contentType)
    }

    override suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        return watchHistoryService.getCanonicalContinueWatching(
            limit = limit,
            nowMs = nowMs,
        )
    }

    override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        return watchHistoryService.getLocalWatchProgress(identity)
    }

    override suspend fun markWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult {
        return watchHistoryService.markWatched(request)
    }

    override suspend fun unmarkWatched(
        request: WatchHistoryRequest,
    ): WatchHistoryResult {
        return watchHistoryService.unmarkWatched(request)
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        return watchHistoryService.setInWatchlist(request, inWatchlist)
    }

    override suspend fun setTitleInWatchlist(
        itemId: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        return watchHistoryService.setTitleInWatchlist(itemId, inWatchlist)
    }

    override suspend fun setLiked(
        request: WatchHistoryRequest,
        liked: Boolean?,
    ): WatchHistoryResult {
        return watchHistoryService.setLiked(request, liked)
    }

    override suspend fun setTitleLiked(
        itemId: String,
        liked: Boolean?,
    ): WatchHistoryResult {
        return watchHistoryService.setTitleLiked(itemId, liked)
    }
}
