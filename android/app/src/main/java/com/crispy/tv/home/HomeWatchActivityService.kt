package com.crispy.tv.home

import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult

class HomeWatchActivityService {

    suspend fun loadWatchActivity(
        canonicalResult: CanonicalContinueWatchingResult,
        limit: Int = 20,
    ): CanonicalContinueWatchingResult {
        return if (canonicalResult.entries.isNotEmpty()) {
            loadContinueWatchingItems(canonicalResult.entries, limit).copy(
                statusMessage = canonicalResult.statusMessage,
                isError = canonicalResult.isError,
            )
        } else if (canonicalResult.isError) {
            CanonicalContinueWatchingResult(
                statusMessage = canonicalResult.statusMessage,
                isError = true,
            )
        } else {
            CanonicalContinueWatchingResult(statusMessage = "")
        }
    }

    private suspend fun loadContinueWatchingItems(
        entries: List<CanonicalContinueWatchingItem>,
        limit: Int,
    ): CanonicalContinueWatchingResult {
        val targetCount = limit.coerceAtLeast(1)
        val projectedEntries = entries.take(targetCount)

        if (projectedEntries.isEmpty()) {
            return CanonicalContinueWatchingResult(statusMessage = "")
        }

        val items = projectedEntries.map(::buildContinueWatchingItem)

        return CanonicalContinueWatchingResult(
            statusMessage = "",
            entries = items,
        )
    }

private fun buildContinueWatchingItem(entry: CanonicalContinueWatchingItem): CanonicalContinueWatchingItem {
  return CanonicalContinueWatchingItem(
    id = entry.id.trim(),
    titleMediaKey = entry.titleMediaKey,
    playbackMediaKey = entry.playbackMediaKey,
    localKey = entry.localKey,
    mediaType = entry.mediaType,
    title = entry.title,
    season = entry.season,
    episode = entry.episode,
    lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
    progressPercent = entry.progressPercent,
    backdropUrl = entry.backdropUrl,
    posterUrl = entry.posterUrl,
    logoUrl = entry.logoUrl,
    addonId = entry.addonId,
    subtitle = entry.subtitle,
    absoluteEpisodeNumber = entry.absoluteEpisodeNumber,
  )
}
}
