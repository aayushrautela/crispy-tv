package com.crispy.rewrite.watchhistory.provider

import com.crispy.rewrite.player.ContinueWatchingEntry
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.ProviderCommentQuery
import com.crispy.rewrite.player.ProviderCommentResult
import com.crispy.rewrite.player.ProviderLibraryFolder
import com.crispy.rewrite.player.ProviderLibraryItem
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.watchhistory.local.NormalizedWatchRequest

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
