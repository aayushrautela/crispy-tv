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
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchedEpisodeRecord
import com.crispy.tv.watchhistory.preferredWatchProvider

class DefaultUserMediaRepository(
    private val watchHistoryService: WatchHistoryService,
) : UserMediaRepository {
    override suspend fun preferredProvider(): WatchProvider? {
        return preferredWatchProvider(watchHistoryService.authState())
    }

    override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        return watchHistoryService.getCanonicalWatchState(identity)
    }

    override suspend fun getTitleWatchState(
        mediaKey: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? {
        return watchHistoryService.getTitleWatchState(mediaKey, contentType)
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

    override suspend fun listWatchedEpisodeRecords(): List<WatchedEpisodeRecord> {
        return watchHistoryService.listWatchedEpisodeRecords()
    }

    override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        return watchHistoryService.getLocalWatchProgress(identity)
    }

    override suspend fun markWatched(
        request: WatchHistoryRequest,
        source: WatchProvider?,
    ): WatchHistoryResult {
        return watchHistoryService.markWatched(request, source)
    }

    override suspend fun unmarkWatched(
        request: WatchHistoryRequest,
        source: WatchProvider?,
    ): WatchHistoryResult {
        return watchHistoryService.unmarkWatched(request, source)
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
        source: WatchProvider?,
    ): WatchHistoryResult {
        return watchHistoryService.setInWatchlist(request, inWatchlist, source)
    }

    override suspend fun setTitleInWatchlist(
        mediaKey: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        return watchHistoryService.setTitleInWatchlist(mediaKey, inWatchlist)
    }

    override suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
        source: WatchProvider?,
    ): WatchHistoryResult {
        return watchHistoryService.setRating(request, rating, source)
    }

    override suspend fun setTitleRating(
        mediaKey: String,
        rating: Int?,
    ): WatchHistoryResult {
        return watchHistoryService.setTitleRating(mediaKey, rating)
    }
}
