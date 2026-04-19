package com.crispy.tv.watchhistory

import android.content.Context
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.PlaybackEventInput
import com.crispy.tv.backend.CrispyBackendClient.WatchMutationInput
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchedEpisodeRecord
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.watchhistory.progress.WatchProgress
import com.crispy.tv.watchhistory.progress.WatchProgressStore
import java.time.Instant
import java.util.Locale

class BackendWatchHistoryService(
    context: Context,
    private val backend: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
    private val episodeListProvider: EpisodeListProvider,
    private val config: WatchHistoryConfig = WatchHistoryConfig(),
) : WatchHistoryService {
    private val appContext = context.applicationContext
    private val watchProgressStore =
        WatchProgressStore(
            prefs = appContext.getSharedPreferences(WATCH_PROGRESS_PREFS_NAME, Context.MODE_PRIVATE),
        )
    private val appVersion = config.appVersion.trim().ifBlank { "dev" }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult {
        return syncWatchedMutation(request, shouldMark = true)
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult {
        return syncWatchedMutation(request, shouldMark = false)
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update watchlist.")
        val mediaKey = request.mediaKey?.trim()?.ifBlank { null }
            ?: resolveMediaKey(backendContext.accessToken, request)
            ?: return WatchHistoryResult(statusMessage = "Watchlist update failed.")
        val action = try {
            if (inWatchlist) {
                backend.putNativeWatchlist(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = mediaKey,
                )
            } else {
                backend.deleteNativeWatchlist(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = mediaKey,
                )
            }
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Watchlist update failed.")
        }

        return WatchHistoryResult(
            statusMessage = if (action.accepted) {
                if (inWatchlist) "Saved to watchlist." else "Removed from watchlist."
            } else {
                "Watchlist update failed."
            },
            accepted = action.accepted,
        )
    }

    override suspend fun setTitleInWatchlist(
        mediaKey: String,
        inWatchlist: Boolean,
    ): WatchHistoryResult {
        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update watchlist.")
        val normalizedMediaKey = mediaKey.trim().ifBlank {
            return WatchHistoryResult(statusMessage = "Watchlist update failed.")
        }

        val action = try {
            if (inWatchlist) {
                backend.putNativeWatchlist(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = normalizedMediaKey,
                )
            } else {
                backend.deleteNativeWatchlist(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = normalizedMediaKey,
                )
            }
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Watchlist update failed.")
        }

        return WatchHistoryResult(
            statusMessage = if (action.accepted) {
                if (inWatchlist) "Saved to watchlist." else "Removed from watchlist."
            } else {
                "Watchlist update failed."
            },
            accepted = action.accepted,
        )
    }

    override suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
    ): WatchHistoryResult {
        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update ratings.")
        val mediaKey = request.mediaKey?.trim()?.ifBlank { null }
            ?: resolveMediaKey(backendContext.accessToken, request)
            ?: return WatchHistoryResult(statusMessage = "Rating update failed.")
        val action = try {
            if (rating == null) {
                backend.deleteNativeRating(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = mediaKey,
                )
            } else {
                backend.putNativeRating(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = mediaKey,
                    rating = rating.coerceIn(1, 10),
                )
            }
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Rating update failed.")
        }

        return WatchHistoryResult(
            statusMessage = if (action.accepted) {
                if (rating == null) "Removed rating." else "Rated ${rating.coerceIn(1, 10)}/10."
            } else {
                "Rating update failed."
            },
            accepted = action.accepted,
        )
    }

    override suspend fun setTitleRating(
        mediaKey: String,
        rating: Int?,
    ): WatchHistoryResult {
        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update ratings.")
        val normalizedMediaKey = mediaKey.trim().ifBlank {
            return WatchHistoryResult(statusMessage = "Rating update failed.")
        }

        val action = try {
            if (rating == null) {
                backend.deleteNativeRating(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = normalizedMediaKey,
                )
            } else {
                backend.putNativeRating(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    mediaKey = normalizedMediaKey,
                    rating = rating.coerceIn(1, 10),
                )
            }
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Rating update failed.")
        }

        return WatchHistoryResult(
            statusMessage = if (action.accepted) {
                if (rating == null) "Removed rating." else "Rated ${rating.coerceIn(1, 10)}/10."
            } else {
                "Rating update failed."
            },
            accepted = action.accepted,
        )
    }

    override suspend fun removeFromPlayback(playbackId: String): WatchHistoryResult {
        val trimmedId = playbackId.trim()
        if (trimmedId.isEmpty()) {
            return WatchHistoryResult(statusMessage = "Playback id missing.")
        }

        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update continue watching.")

        val action = try {
            backend.dismissContinueWatching(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                itemId = trimmedId,
            )
        } catch (_: Throwable) {
            return WatchHistoryResult(statusMessage = "Continue watching removal failed.")
        }

        return WatchHistoryResult(
            statusMessage = if (action.accepted) "Removed from continue watching." else "Continue watching removal unavailable.",
            accepted = action.accepted,
        )
    }

    override suspend fun listWatchedEpisodeRecords(): List<WatchedEpisodeRecord> {
        return canonicalWatchedEpisodeRecords()
    }

    override suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        val targetLimit = limit.coerceAtLeast(1)
        return listCanonicalBackendContinueWatchingItems(targetLimit, nowMs)
    }

    override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        val backendContext = getBackendContext()
        val input = identity.toPlaybackLookupInput()
        val backendSnapshot =
            if (backendContext == null || input == null) {
                null
            } else {
                try {
                    val mediaKey =
                        backend.resolvePlayback(
                            accessToken = backendContext.accessToken,
                            input = input,
                        ).item.mediaKey.trim().takeIf { it.isNotBlank() } ?: return null
                    val envelope = backend.getWatchState(
                        accessToken = backendContext.accessToken,
                        profileId = backendContext.profileId,
                        mediaKey = mediaKey,
                    )
                    envelope.item.toCanonicalWatchStateSnapshot()
                } catch (_: Throwable) {
                    null
                }
        }
        return backendSnapshot
    }

    override suspend fun getTitleWatchState(
        mediaKey: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? {
        val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
        val backendContext = getBackendContext()
        val backendSnapshot =
            if (backendContext == null) {
                null
            } else {
                try {
                    backend.getWatchState(
                        accessToken = backendContext.accessToken,
                        profileId = backendContext.profileId,
                        mediaKey = normalizedMediaKey,
                    ).item.toCanonicalWatchStateSnapshot()
                } catch (_: Throwable) {
                    null
                }
            }
        return backendSnapshot
    }

    override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        val parts = progressKeyParts(identity) ?: return null
        val progress = watchProgressStore.getWatchProgress(
            id = parts.id,
            type = parts.type,
            episodeId = parts.episodeId,
        ) ?: return null
        return WatchProgressSnapshot(
            currentTimeSeconds = progress.currentTimeSeconds,
            durationSeconds = progress.durationSeconds,
            lastUpdatedEpochMs = progress.lastUpdatedEpochMs,
        )
    }

    override suspend fun removeLocalWatchProgress(identity: PlaybackIdentity): WatchHistoryResult {
        val parts = progressKeyParts(identity)
            ?: return WatchHistoryResult(statusMessage = "Missing playback identity.")

        watchProgressStore.removeAllWatchProgressForContent(
            id = parts.id,
            type = parts.type,
            addBaseTombstone = true,
        )
        watchProgressStore.addContinueWatchingRemoved(id = parts.id, type = parts.type)

        return WatchHistoryResult(statusMessage = "Removed local playback progress.")
    }

    override suspend fun onPlaybackStarted(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        onPlaybackProgress(identity = identity, positionMs = positionMs, durationMs = durationMs, isPlaying = true)
        sendPlaybackEvent(identity, positionMs, durationMs, eventType = "playback_progress")
    }

    override suspend fun onPlaybackProgress(identity: PlaybackIdentity, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val parts = progressKeyParts(identity) ?: return

        val durationSeconds = durationMs.coerceAtLeast(0L).toDouble() / 1000.0
        val currentSeconds = positionMs.coerceAtLeast(0L).toDouble() / 1000.0
        if (durationSeconds <= 0.0) return

        watchProgressStore.setContentDuration(
            id = parts.id,
            type = parts.type,
            durationSeconds = durationSeconds,
            episodeId = parts.episodeId,
        )

        val existing = watchProgressStore.getWatchProgress(
            id = parts.id,
            type = parts.type,
            episodeId = parts.episodeId,
        )
val next =
            (existing ?: WatchProgress(currentTimeSeconds = 0.0, durationSeconds = durationSeconds, lastUpdatedEpochMs = 0L))
            .copy(
                currentTimeSeconds = currentSeconds.coerceIn(0.0, durationSeconds),
                durationSeconds = durationSeconds,
            )

        watchProgressStore.setWatchProgress(
            id = parts.id,
            type = parts.type,
            progress = next,
            episodeId = parts.episodeId,
        )

        sendPlaybackEvent(
            identity = identity,
            positionMs = positionMs,
            durationMs = durationMs,
            eventType = if (isPlaying) "playback_progress" else "playback_progress_snapshot",
        )
    }

    override suspend fun onPlaybackStopped(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        onPlaybackProgress(identity = identity, positionMs = positionMs, durationMs = durationMs, isPlaying = false)
        val progressPercent = toProgressPercent(positionMs = positionMs, durationMs = durationMs)
        sendPlaybackEvent(
            identity = identity,
            positionMs = positionMs,
            durationMs = durationMs,
            eventType = if ((progressPercent ?: 0.0) >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                "playback_completed"
            } else {
                "playback_progress_snapshot"
            },
        )
    }

    private suspend fun resolveMediaKey(accessToken: String, request: WatchHistoryRequest): String? {
        return try {
            backend.resolvePlayback(
                accessToken = accessToken,
                input = request.toProviderLookupInput(),
            ).item.mediaKey.trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun syncWatchedMutation(request: WatchHistoryRequest, shouldMark: Boolean): WatchHistoryResult {
        val backendContext = getBackendContext() ?: return WatchHistoryResult(statusMessage = "Select a profile to update watched state.")
        val mutationInput = buildWatchMutationInput(request, backendContext)
            ?: return WatchHistoryResult(statusMessage = "Watched update failed.")

        val response = try {
            if (shouldMark) {
                backend.markWatched(backendContext.accessToken, backendContext.profileId, mutationInput)
            } else {
                backend.unmarkWatched(backendContext.accessToken, backendContext.profileId, mutationInput)
            }
        } catch (_: Throwable) {
            null
        } ?: return WatchHistoryResult(statusMessage = "Watched update failed.")

        return WatchHistoryResult(
            statusMessage = if (response.accepted) {
                if (shouldMark) "Marked watched." else "Removed from watched."
            } else {
                "Watched update failed."
            },
            accepted = response.accepted,
        )
    }

    private suspend fun listCanonicalBackendContinueWatchingItems(
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        val backendContext = getBackendContext()
            ?: return CanonicalContinueWatchingResult(
                statusMessage = "Sign in and select a profile to load continue watching.",
                isError = true,
            )

        return try {
            val entries = backend
                .listContinueWatching(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    limit = limit.coerceAtLeast(1),
                ).items
                .toCanonicalContinueWatchingItems(nowMs = nowMs, limit = limit)
            val status = if (entries.isNotEmpty()) "" else "No continue watching entries available."
            CanonicalContinueWatchingResult(statusMessage = status, entries = entries)
        } catch (_: Throwable) {
            CanonicalContinueWatchingResult(
                statusMessage = "Continue watching temporarily unavailable.",
                entries = emptyList(),
                isError = true,
            )
        }
    }

    private suspend fun canonicalWatchedEpisodeRecords(): List<WatchedEpisodeRecord> {
        return listCanonicalWatchHistory(limit = 1000)
            .asSequence()
            .filter { item ->
                item.media.mediaType.equals("episode", ignoreCase = true) &&
                    item.media.seasonNumber != null &&
                    item.media.episodeNumber != null
            }
            .mapNotNull { item ->
                val canonicalMediaKey = item.media.mediaKey.trim().ifBlank { return@mapNotNull null }
                val watchedAtEpochMs =
                    parseIsoToEpochMs(item.watchedAt)
                        ?: parseIsoToEpochMs(item.lastActivityAt)
                        ?: 0L
                if (watchedAtEpochMs <= 0L) {
                    return@mapNotNull null
                }
                WatchedEpisodeRecord(
                    contentId = canonicalMediaKey,
                    season = item.media.seasonNumber ?: 1,
                    episode = item.media.episodeNumber ?: 1,
                    watchedAtEpochMs = watchedAtEpochMs,
                )
            }
            .toList()
    }

    private suspend fun listCanonicalWatchHistory(limit: Int): List<CrispyBackendClient.WatchedItem> {
        val backendContext = getBackendContext() ?: return emptyList()
        return try {
            backend.listWatchHistory(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                limit = limit.coerceAtLeast(1),
            ).items
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun sendPlaybackEvent(
        identity: PlaybackIdentity,
        positionMs: Long,
        durationMs: Long,
        eventType: String,
    ) {
        val backendContext = getBackendContext() ?: return
        val mediaType = when (identity.contentType) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> if (identity.season != null && identity.episode != null) "episode" else "show"
            MetadataLabMediaType.ANIME -> if (identity.season != null && identity.episode != null) "episode" else "anime"
        }

        val playbackInput = PlaybackEventInput(
            clientEventId = buildClientEventId(identity, eventType),
            eventType = eventType,
            mediaKey = identity.mediaKey,
            mediaType = mediaType,
            seasonNumber = identity.season,
            episodeNumber = identity.episode,
            absoluteEpisodeNumber = identity.absoluteEpisodeNumber,
            positionSeconds = positionMs.coerceAtLeast(0L).toDouble() / 1000.0,
            durationSeconds = durationMs.coerceAtLeast(0L).toDouble() / 1000.0,
            occurredAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            payload = mapOf(
                "source" to "android",
                "appVersion" to appVersion,
                "title" to identity.title,
                "showTitle" to identity.showTitle,
            ),
        )

        try {
            backend.sendWatchEvent(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                input = playbackInput,
            )
        } catch (_: Throwable) {
        }
    }

private fun buildClientEventId(identity: PlaybackIdentity, eventType: String): String {
    val suffix =
        listOfNotNull(
            identity.mediaKey?.trim()?.takeIf { it.isNotBlank() },
            identity.season?.toString(),
            identity.episode?.toString(),
            identity.absoluteEpisodeNumber?.toString(),
        ).filterNot { it.isBlank() }
            .joinToString(":")
            .ifBlank { identity.title.trim().replace(' ', '_') }
    return "$eventType:$suffix:${System.currentTimeMillis()}"
}

    private suspend fun getBackendContext(): BackendContext? {
        if (!backend.isConfigured()) {
            return null
        }
        return backendContextResolver.resolve()
    }

private fun progressKeyParts(identity: PlaybackIdentity): ProgressKeyParts? {
    val type = when (identity.contentType) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "series"
        MetadataLabMediaType.ANIME -> "anime"
    }

    val mediaKey = identity.mediaKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val episodeId =
        if (identity.contentType != MetadataLabMediaType.MOVIE && identity.season != null && identity.episode != null) {
            "$mediaKey:${identity.season}:${identity.episode}"
        } else {
            null
        }

    return ProgressKeyParts(type = type, id = mediaKey, episodeId = episodeId)
}

    private data class ProgressKeyParts(
        val type: String,
        val id: String,
        val episodeId: String?,
    )

    private fun normalizedImdbIdOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isBlank()) return null
        val candidate = when {
            value.startsWith("tt") -> value
            value.startsWith("imdb:") -> value.substringAfter("imdb:")
            value.all { it.isDigit() } -> "tt$value"
            else -> return null
        }
        if (!candidate.startsWith("tt")) return null
        if (candidate.length < 4) return null
        if (!candidate.substring(2).all { it.isDigit() }) return null
        return candidate
    }

    private fun toProgressPercent(positionMs: Long, durationMs: Long): Double? {
        if (durationMs <= 0L) return null
        val percent = (positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble()) * 100.0
        return percent.coerceIn(0.0, 100.0)
    }

    private fun parseIsoToEpochMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun MetadataLabMediaType.toBackendMediaType(request: WatchHistoryRequest): String {
        return when (this) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> if (request.season != null && request.episode != null) "episode" else "show"
            MetadataLabMediaType.ANIME -> if (request.season != null && request.episode != null) "episode" else "anime"
        }
    }

private fun WatchHistoryRequest.toProviderLookupInput(): MediaLookupInput {
    return MediaLookupInput(
        mediaKey = mediaKey?.trim()?.ifBlank { null },
        mediaType = contentType.toBackendMediaType(this),
        seasonNumber = season,
        episodeNumber = episode,
    )
}

    private suspend fun buildWatchMutationInput(
        request: WatchHistoryRequest,
        backendContext: BackendContext,
    ): WatchMutationInput? {
        val directMediaKey = request.mediaKey?.trim()?.ifBlank { null }
        if (directMediaKey != null && request.season == null && request.episode == null) {
            return WatchMutationInput(
                mediaKey = directMediaKey,
                mediaType = request.contentType.toBackendMediaType(request),
                occurredAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
            )
        }

        val resolved = try {
            backend.resolvePlayback(
                accessToken = backendContext.accessToken,
                input = request.toProviderLookupInput(),
            )
        } catch (_: Throwable) {
            null
        } ?: return null

val item = resolved.item
    return WatchMutationInput(
        mediaKey = item.mediaKey,
        mediaType = item.mediaType,
        seasonNumber = item.seasonNumber,
        episodeNumber = item.episodeNumber,
        absoluteEpisodeNumber = item.absoluteEpisodeNumber ?: request.absoluteEpisodeNumber,
        occurredAt = Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
    )
    }

private fun PlaybackIdentity.toPlaybackLookupInput(): MediaLookupInput? {
    val normalizedMediaKey = mediaKey?.trim()?.takeIf { it.isNotBlank() }
    val mediaType = when (contentType) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> if (season != null && episode != null) "episode" else "show"
        MetadataLabMediaType.ANIME -> if (season != null && episode != null) "episode" else "anime"
    }
    return MediaLookupInput(
        mediaKey = normalizedMediaKey,
        tmdbId = tmdbId,
        mediaType = mediaType,
        seasonNumber = season,
        episodeNumber = episode,
    ).takeIf { it.mediaKey != null || it.tmdbId != null }
}

    private fun List<CrispyBackendClient.ContinueWatchingItem>.toContinueWatchingEntries(
        nowMs: Long,
        limit: Int,
    ): List<CanonicalContinueWatchingItem> {
        return buildList {
            for (item in this@toContinueWatchingEntries) {
                val updatedAt =
                    parseIsoToEpochMs(item.lastActivityAt)
                        ?: parseIsoToEpochMs(item.progress?.lastPlayedAt)
                        ?: nowMs
                add(
                    CanonicalContinueWatchingItem(
                        id = item.id,
                        titleMediaKey = item.media.toTitleMediaKey(),
                        playbackMediaKey = item.media.mediaKey,
                        mediaType = item.media.mediaType,
                        title = item.media.episodeTitle ?: item.media.title,
                        season = item.media.seasonNumber,
                        episode = item.media.episodeNumber,
                        progressPercent = item.progress?.progressPercent ?: 0.0,
                        lastUpdatedEpochMs = updatedAt,
                        posterUrl = item.media.posterUrl,
                        backdropUrl = item.media.backdropUrl,
                        logoUrl = null,
                        addonId = "backend",
                        subtitle = item.media.subtitle,
                        absoluteEpisodeNumber = null,
                    )
                )
            }
        }
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun List<CrispyBackendClient.ContinueWatchingItem>.toCanonicalContinueWatchingItems(
        nowMs: Long,
        limit: Int,
    ): List<CanonicalContinueWatchingItem> {
        return toContinueWatchingEntries(nowMs, limit)
    }

    private fun CrispyBackendClient.WatchStateResponse.toCanonicalWatchStateSnapshot(): CanonicalWatchStateSnapshot {
        return CanonicalWatchStateSnapshot(
            isWatched = watched != null,
            watchedAtEpochMs = parseIsoToEpochMs(watched?.watchedAt),
            isInWatchlist = watchlist != null,
            isRated = rating != null,
            userRating = rating?.value,
            watchedEpisodeKeys = watchedEpisodeKeys.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet(),
        )
    }

private fun titleStateIdentity(
    mediaKey: String,
    contentType: MetadataLabMediaType,
): PlaybackIdentity {
    return PlaybackIdentity(
        mediaKey = mediaKey,
        contentType = contentType,
        title = mediaKey,
    )
}

    private fun String.toMetadataLabMediaType(): MetadataLabMediaType {
        return when (trim().lowercase(Locale.US)) {
            "show", "series", "tv", "episode" -> MetadataLabMediaType.SERIES
            "anime" -> MetadataLabMediaType.ANIME
            else -> MetadataLabMediaType.MOVIE
        }
    }

    private fun CrispyBackendClient.RuntimeMediaCard.toTitleMediaKey(): String {
        val normalizedProvider = provider.trim()
        val normalizedProviderId = providerId.trim()
        if (normalizedProvider.isBlank() || normalizedProviderId.isBlank()) {
            return mediaKey
        }
        val titleMediaType =
            when (mediaType.trim().lowercase(Locale.US)) {
                "movie" -> "movie"
                else -> "show"
            }
        return "$titleMediaType:$normalizedProvider:$normalizedProviderId"
    }

    private companion object {
        private const val CONTINUE_WATCHING_COMPLETION_PERCENT = 85.0
        private const val WATCH_PROGRESS_PREFS_NAME = "watch_progress"
    }
}
