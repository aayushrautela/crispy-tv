package com.crispy.tv.details

import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity

internal class EpisodeWatchStateResolver(
    private val crispyBackendClient: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
    private val userMediaRepository: UserMediaRepository,
    private val completionPercent: Double = 85.0,
) {
    suspend fun resolve(
        details: MediaDetails,
        videos: List<MediaVideo>,
    ): Map<String, EpisodeWatchState> {
        if (videos.isEmpty()) return emptyMap()

        val watchedEpisodeIds = resolveWatchedEpisodeIds(videos)
        val yearInt = details.year?.trim()?.toIntOrNull()
        val contentType = details.itemType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES
        val parentMediaType =
            when (contentType) {
                MetadataLabMediaType.MOVIE -> null
                MetadataLabMediaType.SERIES -> "show"
                MetadataLabMediaType.ANIME -> "anime"
            }
        return videos.associate { video ->
            val season = video.season
            val episode = video.episode
            if (season == null || episode == null) {
                video.id to EpisodeWatchState()
            } else {
                val watchedByHistory = watchedEpisodeIds.contains(video.id)
                val localProgress =
                    userMediaRepository.getLocalWatchProgress(
                        PlaybackIdentity(
                            itemId = details.itemId,
                            contentType = contentType,
                            season = season,
                            episode = episode,
                            title = video.title,
                            year = yearInt,
                            showTitle = if (contentType == MetadataLabMediaType.MOVIE) null else details.title,
                            showYear = if (contentType == MetadataLabMediaType.MOVIE) null else yearInt,
                            parentMediaType = parentMediaType,
                            absoluteEpisodeNumber = video.absoluteEpisodeNumber ?: details.absoluteEpisodeNumber,
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

    private suspend fun resolveWatchedEpisodeIds(videos: List<MediaVideo>): Set<String> {
        val backendContext =
            runCatching { backendContextResolver.resolve() }.getOrNull() ?: return emptySet()
        val states =
            runCatching {
                crispyBackendClient.getWatchStateMap(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    itemIds = videos.map { it.id },
                )
            }.getOrNull() ?: return emptySet()
        return states.filterValues { it.played }.keys
    }
}
