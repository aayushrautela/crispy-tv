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

    override suspend fun getResumePositionMs(identity: PlaybackIdentity): Long =
        resumePositionMs(
            local = watchHistoryService.getLocalWatchProgress(identity),
            // A lambda, not a value: the second question must not be asked when
            // the local store already answered. A fresh install or a second
            // device has an empty local store while the server still knows
            // where the profile got to, so the server is the fallback, never
            // the first question.
            loadServer = {
                // `itemId` is nullable on the identity; a title the app cannot
                // name is not a question the server can answer.
                identity.itemId?.let { itemId ->
                    watchHistoryService.getTitleWatchState(itemId, identity.contentType)
                }
            },
        )

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

/**
 * The resume rule [DefaultUserMediaRepository.getResumePositionMs] owns, as one
 * function so a test can call the decision itself.
 *
 * The local store answers first: this device's own recorder wrote the position,
 * and it answers offline. The server is asked second — when the local store has
 * no in-band answer — because the two former call sites each ran an identical
 * `progressPercent in 1.0..95.0` block and neither ever asked the server, so a
 * device with an empty local store resumed at zero while the server knew where
 * the profile got to.
 *
 * `loadServer` is a lambda because "do not ask the server when the local store
 * already answered" is part of the rule, not an optimisation.
 */
internal suspend fun resumePositionMs(
    local: WatchProgressSnapshot?,
    loadServer: suspend () -> CanonicalWatchStateSnapshot?,
): Long {
    val localAnswer = local?.takeIf { it.progressPercent in RESUME_PROGRESS_RANGE }
    if (localAnswer != null) {
        return (localAnswer.currentTimeSeconds * 1000.0).toLong()
    }
    val server = loadServer() ?: return 0L
    // `progressPercent` is a derived property on a class from another module,
    // so the null check needs a local before the range check can see it.
    val serverPercent = server.progressPercent
    if (serverPercent != null && serverPercent !in RESUME_PROGRESS_RANGE) return 0L
    val resumeSeconds = server.resumePositionSeconds?.takeIf { it > 0.0 } ?: return 0L
    return (resumeSeconds * 1000.0).toLong()
}

/**
 * The band a resume point must fall inside. Below it the user never really
 * started; above it the title is finished — in both cases "resume" is the wrong
 * verb and the answer is the beginning.
 */
private val RESUME_PROGRESS_RANGE = 1.0..95.0
