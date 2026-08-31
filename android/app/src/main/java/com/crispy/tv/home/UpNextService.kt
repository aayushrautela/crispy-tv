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
    suspend fun loadUpNext(nowMs: Long): HomeWideRailSectionUi? {
        val backendContext = backendContextResolver.resolve()
            ?: throw IllegalStateException("Sign in and select a profile to load Up Next.")

        val result = try {
            backendClient.getUpNext(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                limit = UP_NEXT_LIMIT,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load up next", error)
            throw IllegalStateException("Unable to load Up Next right now.", error)
        }

        val items = result.items.mapNotNull { view -> toCanonicalContinueWatchingItem(view) }
        if (items.isEmpty()) return null

        val railItems = items.map { item -> item.toWideRailItem(nowMs) }
        return defaultWideRailSection(
            key = UP_NEXT_SECTION_KEY,
            title = "Up Next",
            kind = HomeWideRailSectionKind.UP_NEXT,
            items = railItems,
        )
    }

    private fun toCanonicalContinueWatchingItem(view: CrispyBackendClient.UpNextItem): CanonicalContinueWatchingItem? {
        val episode = view.nextEpisode ?: return null
        val seriesId = view.show?.itemId ?: return null
        return CanonicalContinueWatchingItem(
            id = episode.itemId,
            titleItemId = seriesId,
            playbackItemId = episode.itemId,
            itemType = "episode",
            title = view.show?.title ?: "",
            episodeTitle = episode.title,
            season = episode.parent?.seasonNumber,
            episode = episode.parent?.episodeNumber,
            progressPercent = 0.0,
            lastUpdatedEpochMs = parseUpNextDate(view.lastInteractedAt),
            artworkUrl = view.show?.images?.artwork?.medium,
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
