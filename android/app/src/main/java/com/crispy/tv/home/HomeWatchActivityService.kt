package com.crispy.tv.home

import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult

class HomeWatchActivityService {

    suspend fun loadWatchActivity(
        providerResult: CanonicalContinueWatchingResult,
        limit: Int = 20,
    ): CanonicalContinueWatchingResult {
        return if (providerResult.entries.isNotEmpty()) {
            loadContinueWatchingItems(providerResult.entries, limit).copy(
                statusMessage = providerResult.statusMessage,
                isError = providerResult.isError,
            )
        } else if (providerResult.isError) {
            CanonicalContinueWatchingResult(
                statusMessage = providerResult.statusMessage,
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
            mediaKey = entry.mediaKey,
            localKey = entry.localKey,
            provider = entry.provider,
            providerId = entry.providerId,
            mediaType = entry.mediaType,
            title = entry.title,
            season = entry.season,
            episode = entry.episode,
            lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
            progressPercent = entry.progressPercent,
            source = entry.source,
            isUpNextPlaceholder = entry.isUpNextPlaceholder,
            backdropUrl = entry.backdropUrl,
            posterUrl = entry.posterUrl,
            logoUrl = entry.logoUrl,
            addonId = entry.addonId,
            subtitle = entry.subtitle,
            dismissible = entry.dismissible,
            absoluteEpisodeNumber = entry.absoluteEpisodeNumber,
        )
    }
}
