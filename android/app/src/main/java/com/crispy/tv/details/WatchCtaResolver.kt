package com.crispy.tv.details

import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.watchhistory.matchesMediaType
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
    private val userMediaRepository: UserMediaRepository,
    private val requestedMediaType: MetadataLabMediaType,
) {

    data class Resolution(
        val watchCta: WatchCta,
        val continueVideoId: String?,
    )

    suspend fun ensureImdbId(details: MediaDetails): MediaDetails {
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        if (fromField != null) return details.copy(imdbId = fromField)
        return details
    }

    suspend fun resolveProviderState(
        details: MediaDetails?,
        itemId: String,
    ): ProviderState {
        val mediaKey = details?.mediaKey?.trim()?.ifBlank { null } ?: itemId.trim().ifBlank { null }
        if (mediaKey == null) {
            return ProviderState(
                isWatched = false,
                watchedAtEpochMs = null,
                isInWatchlist = false,
                isRated = false,
                userRating = null,
            )
        }

        val snapshot = userMediaRepository.getTitleWatchState(mediaKey, requestedMediaType)
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
        val targetId = details.id.trim().lowercase(Locale.US)
        val targetMediaKey = details.mediaKey?.trim()?.lowercase(Locale.US)
        val targetProvider = details.provider?.trim()?.lowercase(Locale.US)
        val targetProviderId = details.providerId?.trim()?.lowercase(Locale.US)
        if (targetId.isBlank() && targetMediaKey.isNullOrBlank() && (targetProvider.isNullOrBlank() || targetProviderId.isNullOrBlank())) {
            return null
        }

        val snapshot = userMediaRepository.getCanonicalContinueWatching(limit = 50, nowMs = nowMs)

        return snapshot.entries
            .asSequence()
            .filter { entry ->
                val entryMediaKey = entry.mediaKey?.trim()?.lowercase(Locale.US)
                val entryProvider = entry.provider.trim().lowercase(Locale.US)
                val entryProviderId = entry.providerId.trim().lowercase(Locale.US)
                val entryId = entry.id.trim().lowercase(Locale.US)
                val matchesIdentity =
                    when {
                        !targetMediaKey.isNullOrBlank() -> entryMediaKey == targetMediaKey || entryId == targetMediaKey
                        !targetProvider.isNullOrBlank() && !targetProviderId.isNullOrBlank() -> {
                            (entryProvider == targetProvider && entryProviderId == targetProviderId) || entryId == targetId
                        }
                        else -> entryId == targetId
                    }
                matchesIdentity &&
                    matchesMediaType(expectedType, entry.type.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE)
            }
            .sortedWith(
                compareByDescending<CanonicalContinueWatchingItem> { it.lastUpdatedEpochMs }
            )
            .firstOrNull()
    }

    suspend fun resolveWatchCta(
        details: MediaDetails?,
        providerState: ProviderState,
        nowMs: Long,
    ): Resolution {
        if (details == null) return Resolution(WatchCta(), null)

        val isSeries = requestedMediaType != MetadataLabMediaType.MOVIE
        val expectedType = requestedMediaType

        val continueEntry = resolveContinueWatchingEntry(details, expectedType, nowMs)
        val canContinue =
            continueEntry != null &&
                continueEntry.progressPercent > CTA_CONTINUE_MIN_PROGRESS_PERCENT &&
                continueEntry.progressPercent < CTA_CONTINUE_COMPLETION_PERCENT

        if (canContinue) {
            val continueSeason = continueEntry.season
            val continueEpisode = continueEntry.episode
            val label =
                if (isSeries) {
                    if (continueSeason != null && continueEpisode != null) {
                        "Continue S$continueSeason:E$continueEpisode"
                    } else {
                        "Continue"
                    }
                } else {
                    "Continue"
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

            return Resolution(
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

        if (providerState.isWatched) {
            return Resolution(
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

        return Resolution(
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
