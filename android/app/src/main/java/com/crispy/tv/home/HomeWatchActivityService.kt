package com.crispy.tv.home

import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import java.util.Locale

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
            id = entry.id.trim().ifBlank {
                "${entry.provider.name.lowercase(Locale.US)}:${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:${entry.season ?: -1}:${entry.episode ?: -1}"
            },
            contentId = entry.contentId,
            title = entry.title,
            season = entry.season,
            episode = entry.episode,
            lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
            progressPercent = entry.progressPercent,
            provider = entry.provider,
            providerPlaybackId = entry.providerPlaybackId,
            isUpNextPlaceholder = entry.isUpNextPlaceholder,
            backdropUrl = entry.backdropUrl,
            posterUrl = entry.posterUrl,
            logoUrl = entry.logoUrl,
            addonId = entry.addonId,
            contentType = entry.contentType,
            metadataProviderId = entry.metadataProviderId,
            metadataProvider = entry.metadataProvider,
            parentProvider = entry.parentProvider,
            parentProviderId = entry.parentProviderId,
            absoluteEpisodeNumber = entry.absoluteEpisodeNumber,
        )
    }
}
