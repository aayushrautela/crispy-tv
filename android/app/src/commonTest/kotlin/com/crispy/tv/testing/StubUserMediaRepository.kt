package com.crispy.tv.testing

import com.crispy.tv.data.repository.resumePositionMs
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot

/**
 * `:app`'s single double for [UserMediaRepository].
 *
 * Promoted out of `DetailsViewModelTest` the same way [FakeWatchHistoryService] left
 * `HomeViewModelTest`: the repo keeps one double per port, so when a second suite needs
 * the same port answered it subclasses or configures this one rather than re-deriving the
 * member list from memory. Every member not worth a canned answer throws, so a test asking
 * for a member it did not stub fails loudly instead of reading a default somebody invented.
 */
internal open class StubUserMediaRepository : UserMediaRepository {
    var progressAnswer: WatchProgressSnapshot? = null
    var titleWatchStateAnswer: CanonicalWatchStateSnapshot? = null
    var continueWatchingAnswer: CanonicalContinueWatchingResult =
        CanonicalContinueWatchingResult(statusMessage = "")
    val continueWatchingNowMs = mutableListOf<Long>()

    override suspend fun getTitleWatchState(
        itemId: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? = titleWatchStateAnswer

    override suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        continueWatchingNowMs += nowMs
        return continueWatchingAnswer
    }

    override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? = progressAnswer

    /**
     * Composed from the same rule the real repository runs, over the two canned
     * answers above. A hand-written `progressPercent in 1.0..95.0` here would be
     * a second copy of the decision; this way a suite that seeds
     * [progressAnswer]/[titleWatchStateAnswer] sees the rule itself run.
     */
    override suspend fun getResumePositionMs(identity: PlaybackIdentity): Long =
        resumePositionMs(local = progressAnswer, loadServer = { titleWatchStateAnswer })

    override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? =
        throw AssertionError("getCanonicalWatchState is not stubbed")

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
        throw AssertionError("markWatched is not stubbed")

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
        throw AssertionError("unmarkWatched is not stubbed")

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult = throw AssertionError("setInWatchlist is not stubbed")

    override suspend fun setTitleInWatchlist(
        itemId: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult = throw AssertionError("setTitleInWatchlist is not stubbed")

    override suspend fun setLiked(
        request: WatchHistoryRequest,
        liked: Boolean?,
    ): WatchHistoryResult = throw AssertionError("setLiked is not stubbed")

    override suspend fun setTitleLiked(
        itemId: String,
        liked: Boolean?,
    ): WatchHistoryResult = throw AssertionError("setTitleLiked is not stubbed")
}
