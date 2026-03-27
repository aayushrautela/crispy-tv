package com.crispy.tv.details

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.watchhistory.addEpisodeKey
import com.crispy.tv.watchhistory.episodeWatchKeyCandidates
import com.crispy.tv.watchhistory.isWatchedFolder
import com.crispy.tv.watchhistory.preferredWatchProvider

internal class EpisodeWatchStateResolver(
    private val watchHistoryService: WatchHistoryService,
    private val completionPercent: Double = 85.0,
) {
    private var cachedEpisodeWatchKeys: Set<String>? = null

    fun clearCache() {
        cachedEpisodeWatchKeys = null
    }

    suspend fun resolve(
        details: MediaDetails,
        videos: List<MediaVideo>,
        resolvedTmdbId: Int?,
    ): Map<String, EpisodeWatchState> {
        if (videos.isEmpty()) return emptyMap()

        val watchedKeys = resolveWatchKeys()
        val yearInt = details.year?.trim()?.toIntOrNull()
        return videos.associate { video ->
            val season = video.season
            val episode = video.episode
            if (season == null || episode == null) {
                video.id to EpisodeWatchState()
            } else {
                val watchedByHistory =
                    episodeWatchKeyCandidates(details, season, episode).any { key -> watchedKeys.contains(key) }
                val localProgress =
                    watchHistoryService.getLocalWatchProgress(
                        PlaybackIdentity(
                            contentId = details.id,
                            imdbId = details.imdbId,
                            tmdbId = resolvedTmdbId,
                            contentType = MetadataLabMediaType.SERIES,
                            season = season,
                            episode = episode,
                            title = video.title,
                            year = yearInt,
                            showTitle = details.title,
                            showYear = yearInt,
                        )
                    )
                val progressPercent = localProgress?.progressPercent ?: 0.0
                val isWatched = watchedByHistory || progressPercent >= completionPercent
                video.id to
                    EpisodeWatchState(
                        progressPercent = if (isWatched) maxOf(progressPercent, 100.0) else progressPercent,
                        isWatched = isWatched,
                    )
            }
        }
    }

    private suspend fun resolveWatchKeys(): Set<String> {
        cachedEpisodeWatchKeys?.let { return it }

        val localHistoryKeys: List<String> =
            watchHistoryService
                .listLocalHistory(limit = 1000)
                .entries
                .mapNotNull { entry ->
                    val season = entry.season ?: return@mapNotNull null
                    val episode = entry.episode ?: return@mapNotNull null
                    addEpisodeKey(entry.contentId, season, episode)
                }

        val source = preferredWatchProvider(watchHistoryService.authState())
        val providerHistoryKeys: List<String> =
            if (source == WatchProvider.LOCAL) {
                emptyList()
            } else {
                val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 1000, source = source)
                val snapshot =
                    if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                        cached
                    } else {
                        watchHistoryService.listProviderLibrary(limitPerFolder = 1000, source = source)
                    }
                snapshot.items.mapNotNull { item ->
                    if (item.provider != source || !source.isWatchedFolder(item.folderId)) {
                        return@mapNotNull null
                    }
                    val season = item.season ?: return@mapNotNull null
                    val episode = item.episode ?: return@mapNotNull null
                    addEpisodeKey(item.contentId, season, episode)
                }
            }

        val combined = LinkedHashSet<String>(localHistoryKeys.size + providerHistoryKeys.size)
        combined += localHistoryKeys
        combined += providerHistoryKeys
        return combined.also { cachedEpisodeWatchKeys = it }
    }
}
