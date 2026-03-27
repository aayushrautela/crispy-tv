package com.crispy.tv.details

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.metadata.tmdbLookupId
import com.crispy.tv.metadata.TmdbImdbIdResolver
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.watchhistory.isWatchedFolder
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
    private val tmdbImdbIdResolver: TmdbImdbIdResolver,
) {

    suspend fun ensureImdbId(details: MediaDetails): MediaDetails {
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        if (fromField != null) return details.copy(imdbId = fromField)

        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: requestedMediaType
        val lookupId = details.tmdbLookupId() ?: return details
        val resolved = tmdbImdbIdResolver.resolveImdbId(lookupId, mediaType) ?: return details
        return details.copy(imdbId = resolved)
    }

    suspend fun resolveProviderState(
        details: MediaDetails?,
        itemId: String,
    ): ProviderState {
        val authState = watchHistoryService.authState()
        val source = preferredWatchProvider(authState)
        val targetId = (details?.id ?: itemId).trim().lowercase(Locale.US)
        val expectedType = requestedMediaType
        if (targetId.isBlank()) {
            return ProviderState(
                isWatched = false,
                watchedAtEpochMs = null,
                isInWatchlist = false,
                isRated = false,
                userRating = null,
            )
        }

        return when (source) {
            WatchProvider.LOCAL -> {
                val local = watchHistoryService.listLocalHistory(limit = 250)
                val watchedEntry =
                    local.entries
                        .asSequence()
                        .filter { entry ->
                            matchesContentId(entry.contentId, targetId) && matchesMediaType(expectedType, entry.contentType)
                        }
                        .maxByOrNull { it.watchedAtEpochMs }

                val watched = watchedEntry != null
                ProviderState(
                    isWatched = watched,
                    watchedAtEpochMs = watchedEntry?.watchedAtEpochMs,
                    isInWatchlist = false,
                    isRated = false,
                    userRating = null,
                )
            }

            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> {
                val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 250, source = source)
                val snapshot =
                    if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                        cached
                    } else {
                        watchHistoryService.listProviderLibrary(limitPerFolder = 250, source = source)
                    }

                val watchedItem =
                    snapshot.items.firstOrNull { item ->
                        item.provider == source &&
                            source.isWatchedFolder(item.folderId) &&
                            matchesContentId(item.contentId, targetId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val watchedAtEpochMs = watchedItem?.addedAtEpochMs
                val watched = watchedItem != null

                val watchlistFolderId =
                    when (source) {
                        WatchProvider.TRAKT -> "watchlist"
                        WatchProvider.SIMKL -> "plantowatch"
                        WatchProvider.LOCAL -> ""
                    }
                val inWatchlist =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == watchlistFolderId &&
                            matchesContentId(item.contentId, targetId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val isRated =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == "ratings" &&
                            matchesContentId(item.contentId, targetId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                ProviderState(
                    isWatched = watched,
                    watchedAtEpochMs = watchedAtEpochMs,
                    isInWatchlist = inWatchlist,
                    isRated = isRated,
                    userRating = null,
                )
            }
        }
    }

    suspend fun resolveContinueWatchingEntry(
        details: MediaDetails,
        expectedType: MetadataLabMediaType,
        nowMs: Long,
    ): ContinueWatchingEntry? {
        val source = preferredWatchProvider(watchHistoryService.authState())
        val targetId = details.id.trim().lowercase(Locale.US)
        if (targetId.isBlank()) return null

        val cached = watchHistoryService.getCachedContinueWatching(limit = 50, nowMs = nowMs, source = source)
        val snapshot =
            if (cached.entries.isNotEmpty()) {
                cached
            } else {
                watchHistoryService.listContinueWatching(limit = 50, nowMs = nowMs, source = source)
            }

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

        val isSeries = requestedMediaType == MetadataLabMediaType.SERIES
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
}
