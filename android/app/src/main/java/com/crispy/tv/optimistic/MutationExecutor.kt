package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult

/**
 * Result of attempting to flush a single [UserMutation] to the server.
 * `conflict` distinguishes a server-rejected write (stale local truth) from a
 * transient failure that should be retried.
 */
data class MutationResult(
    val success: Boolean,
    val conflict: Boolean = false,
    val serverValue: String? = null,
    val reason: String? = null,
)

/** Translates a [UserMutation] into the concrete repository call. */
interface MutationExecutor {
    suspend fun execute(mutation: UserMutation): MutationResult
}

internal class UserMediaMutationExecutor(
    private val repository: UserMediaRepository,
) : MutationExecutor {
    override suspend fun execute(mutation: UserMutation): MutationResult {
        val result: WatchHistoryResult =
            when (mutation) {
                is WatchlistMutation ->
                    repository.setTitleInWatchlist(mutation.entityId, mutation.desired)

                is RatingMutation ->
                    repository.setTitleRating(mutation.entityId, mutation.desired)

                is TitleWatchedMutation -> {
                    val request =
                        WatchHistoryRequest(
                            itemId = mutation.entityId,
                            contentType = mutation.contentType,
                        )
                    if (mutation.desired) {
                        repository.markWatched(request)
                    } else {
                        repository.unmarkWatched(request)
                    }
                }

                is EpisodeWatchedMutation -> {
                    val request =
                        WatchHistoryRequest(
                            itemId = mutation.titleItemId,
                            contentType = MetadataLabMediaType.SERIES,
                            season = mutation.season,
                            episode = mutation.episode,
                        )
                    if (mutation.desired) {
                        repository.markWatched(request)
                    } else {
                        repository.unmarkWatched(request)
                    }
                }

                is SeasonWatchedMutation -> {
                    val request =
                        WatchHistoryRequest(
                            itemId = mutation.seasonItemId,
                            contentType = MetadataLabMediaType.SERIES,
                        )
                    if (mutation.desired) {
                        repository.markWatched(request)
                    } else {
                        repository.unmarkWatched(request)
                    }
                }
            }

        return MutationResult(
            success = result.accepted,
            reason = result.statusMessage.takeIf { it.isNotBlank() },
        )
    }
}
