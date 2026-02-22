package com.crispy.tv.watchhistory.provider

import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderCommentQuery
import com.crispy.tv.player.ProviderCommentResult
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.local.NormalizedWatchRequest

internal interface WatchHistoryProvider {
    val source: WatchProvider

    fun hasClientId(): Boolean

    fun hasAccessToken(): Boolean

    suspend fun markWatched(request: NormalizedWatchRequest): Boolean

    suspend fun unmarkWatched(request: NormalizedWatchRequest): Boolean

    suspend fun setInWatchlist(request: WatchHistoryRequest, inWatchlist: Boolean): Boolean

    suspend fun setRating(request: WatchHistoryRequest, rating: Int?): Boolean

    suspend fun removeFromPlayback(playbackId: String): Boolean

    suspend fun listContinueWatching(nowMs: Long): List<ContinueWatchingEntry>

    suspend fun listProviderLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>>

    suspend fun listRecommendations(limit: Int): List<ProviderLibraryItem>

    suspend fun fetchComments(query: ProviderCommentQuery): ProviderCommentResult {
        return ProviderCommentResult(statusMessage = "Comments unavailable.")
    }
}

internal data class NormalizedContentRequest(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val remoteImdbId: String?,
)
