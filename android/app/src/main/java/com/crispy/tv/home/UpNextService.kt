package com.crispy.tv.home

import android.util.Log
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.player.CanonicalContinueWatchingItem
import java.time.Instant

class UpNextService internal constructor(
    private val backendClient: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
) {
    suspend fun loadUpNext(nowMs: Long): HomeWideRailSectionUi {
        val backendContext = backendContextResolver.resolve()
            ?: return defaultWideRailSection(
                key = UP_NEXT_SECTION_KEY,
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
            ).copy(
                isLoading = false,
                statusMessage = "Sign in and select a profile to load Up Next.",
            )

        val result = try {
            backendClient.getUpNext(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                limit = UP_NEXT_LIMIT,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load up next", error)
            return defaultWideRailSection(
                key = UP_NEXT_SECTION_KEY,
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
            ).copy(
                isLoading = false,
                statusMessage = "Unable to load Up Next right now.",
            )
        }

        val items = result.items.mapNotNull { view -> toCanonicalContinueWatchingItem(view) }

        return defaultWideRailSection(
            key = UP_NEXT_SECTION_KEY,
            title = "Up Next",
            kind = HomeWideRailSectionKind.UP_NEXT,
        ).copy(
            items = items.map { item -> item.toWideRailItem(nowMs) },
            isLoading = false,
            statusMessage = if (items.isEmpty()) "No episodes to watch next right now." else "",
        )
    }

    private fun toCanonicalContinueWatchingItem(view: CrispyBackendClient.UpNextItem): CanonicalContinueWatchingItem? {
        val episodeId = view.nextEpisodeItemId ?: return null
        val seriesId = view.showItemId ?: return null
        return CanonicalContinueWatchingItem(
            id = episodeId,
            titleItemId = seriesId,
            playbackItemId = episodeId,
            itemType = "episode",
            title = view.showTitle ?: "",
            episodeTitle = view.nextEpisodeTitle,
            season = view.nextEpisodeSeasonNumber,
            episode = view.nextEpisodeEpisodeNumber,
            progressPercent = 0.0,
            lastUpdatedEpochMs = parseUpNextDate(view.lastInteractedAt),
            posterUrl = view.showPosterUrl,
            backdropUrl = view.showBackdropUrl,
        )
    }

    private fun parseUpNextDate(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return System.currentTimeMillis()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "UpNextService"
        private const val UP_NEXT_LIMIT = 20
    }
}
