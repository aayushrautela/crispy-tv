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
    val progressPercent: Double? = null,
    val resumePositionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val lastPlayedAtEpochMs: Long? = null,
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
        val lookupId = details?.itemId?.trim()?.ifBlank { null } ?: itemId.trim().ifBlank { null }
        if (lookupId == null) {
            return ProviderState(
                isWatched = false,
                watchedAtEpochMs = null,
                isInWatchlist = false,
                isRated = false,
                userRating = null,
            )
        }

        val snapshot = userMediaRepository.getTitleWatchState(itemId, requestedMediaType)
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
                progressPercent = snapshot.progressPercent,
                resumePositionSeconds = snapshot.resumePositionSeconds,
                durationSeconds = snapshot.durationSeconds,
                lastPlayedAtEpochMs = snapshot.lastPlayedAtEpochMs,
            )
        }
    }

suspend fun resolveContinueWatchingEntry(
    details: MediaDetails,
    expectedType: MetadataLabMediaType,
    nowMs: Long,
  ): CanonicalContinueWatchingItem? {
    val targetId = details.id.trim().lowercase(Locale.US)
    val targetItemId = details.itemId?.trim()?.lowercase(Locale.US)
    if (targetId.isBlank() && targetItemId.isNullOrBlank()) {
      return null
    }

val snapshot = userMediaRepository.getCanonicalContinueWatching(limit = 50, nowMs = nowMs)

  return snapshot.entries
    .asSequence()
    .filter { entry ->
      val entryItemId = entry.titleItemId.trim().lowercase(Locale.US)
      val entryId = entry.id.trim().lowercase(Locale.US)
      val matchesIdentity =
        when {
          !targetItemId.isNullOrBlank() -> entryItemId == targetItemId || entryId == targetItemId
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

        // Title-level resume progress comes from the authoritative per-profile
        // server watch state (GET /watch/state), which already carries position.
        // Series still resolve the specific continue target (season/episode) from
        // the canonical continue-watching list; movies use the title progress.
        val titleProgressPercent = providerState.progressPercent
        val continueEntry = if (isSeries) {
            resolveContinueWatchingEntry(details, expectedType, nowMs)
        } else {
            null
        }

        val canContinue = if (isSeries) {
            continueEntry != null &&
                continueEntry.progressPercent > CTA_CONTINUE_MIN_PROGRESS_PERCENT &&
                continueEntry.progressPercent < CTA_CONTINUE_COMPLETION_PERCENT
        } else {
            titleProgressPercent != null &&
                titleProgressPercent > CTA_CONTINUE_MIN_PROGRESS_PERCENT &&
                titleProgressPercent < CTA_CONTINUE_COMPLETION_PERCENT
        }

        if (canContinue) {
            val progress = if (isSeries) continueEntry!!.progressPercent else titleProgressPercent!!
            val continueSeason = continueEntry?.season
            val continueEpisode = continueEntry?.episode
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
                    val remaining = runtimeMinutes.toDouble() * (1.0 - (progress / 100.0))
                    remaining.roundToInt().coerceAtLeast(0)
                }

            val continueVideoId =
                if (isSeries && continueSeason != null && continueEpisode != null) {
                    details.videos.firstOrNull {
                        it.season == continueSeason && it.episode == continueEpisode
                    }?.id
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
