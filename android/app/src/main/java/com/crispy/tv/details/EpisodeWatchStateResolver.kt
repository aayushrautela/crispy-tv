package com.crispy.tv.details

import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.toMetadataLabMediaTypeOrNull
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.watchhistory.addEpisodeKey
import com.crispy.tv.watchhistory.episodeWatchKeyCandidates

internal class EpisodeWatchStateResolver(
    private val userMediaRepository: UserMediaRepository,
    private val completionPercent: Double = 85.0,
) {
    private var cachedEpisodeWatchKeys: Set<String>? = null

    fun clearCache() {
        cachedEpisodeWatchKeys = null
    }

    suspend fun resolve(
        details: MediaDetails,
        videos: List<MediaVideo>,
    ): Map<String, EpisodeWatchState> {
        if (videos.isEmpty()) return emptyMap()

        val watchedKeys = resolveWatchKeys(details)
        val yearInt = details.year?.trim()?.toIntOrNull()
        val contentType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES
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
                val watchedByHistory =
                    episodeWatchKeyCandidates(details, season, episode).any { key -> watchedKeys.contains(key) }
                val localProgress =
                    userMediaRepository.getLocalWatchProgress(
                        PlaybackIdentity(
                            contentId = details.id,
                            imdbId = details.imdbId,
                            tmdbId = null,
                            contentType = contentType,
                            season = season,
                            episode = episode,
                            title = video.title,
                            year = yearInt,
                            showTitle = if (contentType == MetadataLabMediaType.MOVIE) null else details.title,
                            showYear = if (contentType == MetadataLabMediaType.MOVIE) null else yearInt,
                            provider = video.provider ?: details.provider,
                            providerId = video.providerId ?: details.providerId,
                            parentMediaType = parentMediaType,
                            parentProvider = video.parentProvider ?: details.parentProvider ?: details.provider,
                            parentProviderId = video.parentProviderId ?: details.parentProviderId ?: details.providerId,
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

    private suspend fun resolveWatchKeys(details: MediaDetails): Set<String> {
        cachedEpisodeWatchKeys?.let { return it }

        val source = userMediaRepository.preferredProvider()
        val canonical = userMediaRepository.getCanonicalWatchState(
            PlaybackIdentity(
                contentId = details.id,
                imdbId = details.imdbId,
                tmdbId = null,
                contentType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES,
                title = details.title,
                year = details.year?.trim()?.toIntOrNull(),
                showTitle = details.title,
                showYear = details.year?.trim()?.toIntOrNull(),
                provider = details.provider,
                providerId = details.providerId,
                parentMediaType = details.parentMediaType,
                parentProvider = details.parentProvider ?: details.provider,
                parentProviderId = details.parentProviderId ?: details.providerId,
                absoluteEpisodeNumber = details.absoluteEpisodeNumber,
            )
        )
        val canonicalKeys = canonical?.watchedEpisodeKeys.orEmpty().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (canonicalKeys.isNotEmpty()) {
            return canonicalKeys.also { cachedEpisodeWatchKeys = it }
        }

        val providerHistoryKeys =
            userMediaRepository.listWatchedEpisodeRecords(source = source).mapNotNull { record ->
                addEpisodeKey(record.contentId, record.season, record.episode)
            }.toSet()

        return providerHistoryKeys.also { cachedEpisodeWatchKeys = it }
    }
}
