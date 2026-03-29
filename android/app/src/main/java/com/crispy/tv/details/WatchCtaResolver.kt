package com.crispy.tv.details

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.watchhistory.matchesContentId
import com.crispy.tv.watchhistory.matchesMediaType
import com.crispy.tv.watchhistory.preferredWatchProvider
import java.util.Locale
import kotlin.math.roundToInt

internal data class ProviderState(
    val isWatched: Boolean,
    val watchedAtEpochMs: Long?,
    val isInWatchlist: Boolean,
    val isRated: Boolean,
    val userRating: Int?,
)

internal class WatchCtaResolver(
    private val watchHistoryService: WatchHistoryService,
    private val requestedMediaType: MetadataLabMediaType,
) {

    suspend fun ensureImdbId(details: MediaDetails): MediaDetails {
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        if (fromField != null) return details.copy(imdbId = fromField)
        return details
    }

    suspend fun resolveProviderState(
        details: MediaDetails?,
        itemId: String,
    ): ProviderState {
        val targetId = (details?.id ?: itemId).trim().lowercase(Locale.US)
        if (targetId.isBlank()) {
            return ProviderState(
                isWatched = false,
                watchedAtEpochMs = null,
                isInWatchlist = false,
                isRated = false,
                userRating = null,
            )
        }

        val snapshot = watchHistoryService.getCanonicalWatchState(buildPlaybackIdentity(details, itemId))
        return if (snapshot == null) {
            ProviderState(
                isWatched = false,
                watchedAtEpochMs = null,
                isInWatchlist = false,
                isRated = false,
                userRating = null,
            )
        } else {
            ProviderState(
                isWatched = snapshot.isWatched,
                watchedAtEpochMs = snapshot.watchedAtEpochMs,
                isInWatchlist = snapshot.isInWatchlist,
                isRated = snapshot.isRated,
                userRating = snapshot.userRating,
            )
        }
    }

    suspend fun resolveContinueWatchingEntry(
        details: MediaDetails,
        expectedType: MetadataLabMediaType,
        nowMs: Long,
    ): CanonicalContinueWatchingItem? {
        val source = preferredWatchProvider(watchHistoryService.authState())
        val targetId = details.id.trim().lowercase(Locale.US)
        if (targetId.isBlank()) return null

        val snapshot = watchHistoryService.getCanonicalContinueWatching(limit = 50, nowMs = nowMs, source = source)

        return snapshot.entries
            .asSequence()
            .filter { entry ->
                matchesContentId(entry.contentId, targetId) && matchesMediaType(expectedType, entry.contentType)
            }
            .maxByOrNull { it.lastUpdatedEpochMs }
    }

    suspend fun resolveWatchCta(
        details: MediaDetails?,
        providerState: ProviderState,
        nowMs: Long,
    ): Pair<WatchCta, String?> {
        if (details == null) return Pair(WatchCta(), null)

        val isSeries = requestedMediaType != MetadataLabMediaType.MOVIE
        val expectedType = requestedMediaType

        val continueEntry = resolveContinueWatchingEntry(details, expectedType, nowMs)
        val canContinue =
            continueEntry != null &&
                !continueEntry.isUpNextPlaceholder &&
                continueEntry.progressPercent > CTA_CONTINUE_MIN_PROGRESS_PERCENT &&
                continueEntry.progressPercent < CTA_CONTINUE_COMPLETION_PERCENT

        if (canContinue) {
            val continueSeason = continueEntry.season
            val continueEpisode = continueEntry.episode
            val label =
                if (isSeries) {
                    if (continueSeason != null && continueEpisode != null) {
                        "Continue (S$continueSeason E$continueEpisode)"
                    } else {
                        "Continue"
                    }
                } else {
                    val progress = continueEntry.progressPercent
                    "Resume from ${progress.roundToInt()}%"
                }

            val remainingMinutes =
                parseRuntimeMinutes(details.runtime)?.let { runtimeMinutes ->
                    val progress = continueEntry.progressPercent
                    val remaining = runtimeMinutes.toDouble() * (1.0 - (progress / 100.0))
                    remaining.roundToInt().coerceAtLeast(0)
                }

            val continueVideoId =
                if (isSeries && continueSeason != null && continueEpisode != null) {
                    com.crispy.tv.playback.buildEpisodeLookupId(
                        details = details,
                        season = continueSeason,
                        episode = continueEpisode,
                    )
                } else {
                    null
                }

            return Pair(
                WatchCta(
                    kind = WatchCtaKind.CONTINUE,
                    label = label,
                    icon = WatchCtaIcon.PLAY,
                    remainingMinutes = remainingMinutes,
                    lastWatchedAtEpochMs = null,
                ),
                continueVideoId,
            )
        }

        if (!isSeries && providerState.isWatched) {
            return Pair(
                WatchCta(
                    kind = WatchCtaKind.REWATCH,
                    label = "Rewatch",
                    icon = WatchCtaIcon.REPLAY,
                    remainingMinutes = null,
                    lastWatchedAtEpochMs = providerState.watchedAtEpochMs,
                ),
                null,
            )
        }

        return Pair(
            WatchCta(
                kind = WatchCtaKind.WATCH,
                label = "Watch now",
                icon = WatchCtaIcon.PLAY,
                remainingMinutes = parseRuntimeMinutes(details.runtime),
                lastWatchedAtEpochMs = null,
            ),
            null,
        )
    }

    companion object {
        private const val CTA_CONTINUE_MIN_PROGRESS_PERCENT = 2.0
        private const val CTA_CONTINUE_COMPLETION_PERCENT = 85.0
    }

    private fun buildPlaybackIdentity(details: MediaDetails?, itemId: String): com.crispy.tv.player.PlaybackIdentity {
        val resolvedDetails = details
        val isEpisodic = requestedMediaType != MetadataLabMediaType.MOVIE
        return com.crispy.tv.player.PlaybackIdentity(
            contentId = resolvedDetails?.id ?: itemId,
            imdbId = resolvedDetails?.imdbId,
            tmdbId = resolvedDetails?.tmdbId,
            contentType = requestedMediaType,
            title = resolvedDetails?.title ?: itemId,
            year = resolvedDetails?.year?.trim()?.toIntOrNull(),
            showTitle = if (isEpisodic) resolvedDetails?.title else null,
            showYear = if (isEpisodic) resolvedDetails?.year?.trim()?.toIntOrNull() else null,
            provider = resolvedDetails?.provider,
            providerId = resolvedDetails?.providerId,
            parentMediaType =
                if (isEpisodic) {
                    resolvedDetails?.parentMediaType
                        ?: when (requestedMediaType) {
                            MetadataLabMediaType.SERIES -> "show"
                            MetadataLabMediaType.ANIME -> "anime"
                            MetadataLabMediaType.MOVIE -> null
                        }
                } else {
                    null
                },
            parentProvider = resolvedDetails?.parentProvider ?: resolvedDetails?.provider,
            parentProviderId = resolvedDetails?.parentProviderId ?: resolvedDetails?.providerId,
            absoluteEpisodeNumber = resolvedDetails?.absoluteEpisodeNumber,
        )
    }
}
