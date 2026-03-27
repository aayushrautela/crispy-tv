package com.crispy.tv.watchhistory

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.ImportProvider
import com.crispy.tv.backend.CrispyBackendClient.LibraryMutationSource
import com.crispy.tv.backend.CrispyBackendClient.LibrarySource
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.PlaybackEventInput
import com.crispy.tv.backend.CrispyBackendClient.ProviderAuthState
import com.crispy.tv.backend.CrispyBackendClient.ProviderLibrarySnapshot as BackendProviderLibrarySnapshot
import com.crispy.tv.backend.CrispyBackendClient.WatchMutationInput
import com.crispy.tv.domain.watch.findNextEpisode
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.ProviderExternalIds
import com.crispy.tv.player.ProviderAuthActionResult
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
import com.crispy.tv.player.WatchedEpisodeRecord
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.player.WatchProviderSession
import com.crispy.tv.watchhistory.cache.WatchHistoryCache
import com.crispy.tv.watchhistory.local.LocalWatchHistoryStore
import com.crispy.tv.watchhistory.local.NormalizedWatchRequest
import com.crispy.tv.watchhistory.progress.WatchProgress
import com.crispy.tv.watchhistory.progress.WatchProgressStore
import java.time.Instant
import java.util.Locale

class BackendWatchHistoryService(
    context: Context,
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
    private val activeProfileStore: ActiveProfileStore,
    private val episodeListProvider: EpisodeListProvider,
    private val config: WatchHistoryConfig = WatchHistoryConfig(),
) : WatchHistoryService {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(WATCH_HISTORY_PREFS_NAME, Context.MODE_PRIVATE)
    private val localStore = LocalWatchHistoryStore(prefs)
    private val watchHistoryCache = WatchHistoryCache(appContext)
    private val watchProgressStore =
        WatchProgressStore(
            prefs = appContext.getSharedPreferences(WATCH_PROGRESS_PREFS_NAME, Context.MODE_PRIVATE),
        )
    private val appVersion = config.appVersion.trim().ifBlank { "dev" }

    @Volatile
    private var cachedAuthState: WatchProviderAuthState? = null

    init {
        migrateLegacyWatchHistoryPrefsIfNeeded(appContext)
        cachedAuthState = loadStoredAuthState()
    }

    override fun clearCachedProviderAuthState() {
        watchHistoryCache.clearProviderCaches(WatchProvider.TRAKT)
        watchHistoryCache.clearProviderCaches(WatchProvider.SIMKL)
        clearStoredAuthState()
    }

    override suspend fun disconnectProvider(provider: WatchProvider): ProviderAuthActionResult {
        if (provider == WatchProvider.LOCAL) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Local provider does not support disconnect.",
                authState = authState(),
            )
        }

        val backendContext = getBackendContext() ?: return ProviderAuthActionResult(
            success = false,
            statusMessage = "Select a profile to disconnect ${providerLabel(provider)}.",
            authState = authState(),
        )

        return try {
            backend.disconnectImportConnection(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                provider = provider.toImportProvider(),
            )
            watchHistoryCache.clearProviderCaches(provider)
            val refreshed = try {
                backend.getProviderAuthState(backendContext.accessToken, backendContext.profileId)
            } catch (_: Throwable) {
                null
            }
            val nextAuthState =
                if (refreshed != null) {
                    persistAuthState(refreshed)
                } else {
                    clearProvider(provider)
                }
            ProviderAuthActionResult(
                success = true,
                statusMessage = "${providerLabel(provider)} disconnected from Crispy.",
                authState = nextAuthState,
            )
        } catch (error: Throwable) {
            ProviderAuthActionResult(
                success = false,
                statusMessage = error.message ?: "${providerLabel(provider)} disconnect failed.",
                authState = authState(),
            )
        }
    }

    override fun authState(): WatchProviderAuthState {
        return cachedAuthState ?: loadStoredAuthState().also { cachedAuthState = it }
    }

    override suspend fun refreshProviderAuthState(forceRefresh: Boolean): ProviderAuthActionResult {
        val backendContext = getBackendContext() ?: return ProviderAuthActionResult(
            success = !forceRefresh,
            statusMessage = "",
            authState = clearStoredAuthState(),
        )

        return try {
            val authProviders = backend.getProviderAuthState(backendContext.accessToken, backendContext.profileId)
            ProviderAuthActionResult(
                success = true,
                statusMessage = "",
                authState = persistAuthState(authProviders),
            )
        } catch (error: Throwable) {
            ProviderAuthActionResult(
                success = false,
                statusMessage = error.message ?: "Failed to load provider status.",
                authState = authState(),
            )
        }
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryResult {
        return localStore.listLocalHistory(limit = limit, authState = authState())
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return localStore.exportLocalHistory()
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryResult {
        return localStore.replaceLocalHistory(entries = entries, authState = authState())
    }

    override suspend fun markWatched(request: WatchHistoryRequest, source: WatchProvider?): WatchHistoryResult {
        return updateWatchedState(request = request, source = source, shouldMark = true)
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest, source: WatchProvider?): WatchHistoryResult {
        return updateWatchedState(request = request, source = source, shouldMark = false)
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
        source: WatchProvider?,
    ): WatchHistoryResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryResult(statusMessage = "Watchlist is unavailable for local watch history.")
        }

        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update watchlist.")

        val response = try {
            backend.setProviderWatchlist(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                input = request.toProviderLookupInput(),
                inWatchlist = inWatchlist,
                source = source.toLibraryMutationSource(),
            )
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Watchlist update failed.")
        }

        val syncedTrakt = isProviderMutationSuccessful(response, WatchProvider.TRAKT)
        val syncedSimkl = isProviderMutationSuccessful(response, WatchProvider.SIMKL)
        if (syncedTrakt) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.SIMKL)

        return WatchHistoryResult(
            statusMessage = response.statusMessage.ifBlank {
                if (inWatchlist) "Saved to watchlist." else "Removed from watchlist."
            },
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
        source: WatchProvider?,
    ): WatchHistoryResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryResult(statusMessage = "Rating is unavailable for local watch history.")
        }

        val backendContext = getBackendContext()
            ?: return WatchHistoryResult(statusMessage = "Select a profile to update ratings.")

        val response = try {
            backend.setProviderRating(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                input = request.toProviderLookupInput(),
                rating = rating,
                source = source.toLibraryMutationSource(),
            )
        } catch (error: Throwable) {
            return WatchHistoryResult(statusMessage = error.message ?: "Rating update failed.")
        }

        val syncedTrakt = isProviderMutationSuccessful(response, WatchProvider.TRAKT)
        val syncedSimkl = isProviderMutationSuccessful(response, WatchProvider.SIMKL)
        if (syncedTrakt) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.SIMKL)

        return WatchHistoryResult(
            statusMessage = response.statusMessage.ifBlank {
                if (rating == null) "Removed rating." else "Rated ${rating.coerceIn(1, 10)}/10."
            },
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun removeFromPlayback(playbackId: String, source: WatchProvider?): WatchHistoryResult {
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
            return WatchHistoryResult(statusMessage = "Continue watching removal failed.", authState = authState())
        }

        val synced = action.accepted
        return WatchHistoryResult(
            statusMessage = if (synced) "Removed from continue watching." else "Continue watching removal unavailable.",
            authState = authState(),
            syncedToTrakt = synced && source == WatchProvider.TRAKT,
            syncedToSimkl = synced && source == WatchProvider.SIMKL,
        )
    }

    override suspend fun listContinueWatching(limit: Int, nowMs: Long, source: WatchProvider?): ContinueWatchingResult {
        val targetLimit = limit.coerceAtLeast(1)

        return when (source) {
            WatchProvider.LOCAL -> {
                val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                ContinueWatchingResult(
                    statusMessage = if (local.isNotEmpty()) "" else "No local continue watching entries yet.",
                    entries = local,
                )
            }

            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> listProviderContinueWatching(source, targetLimit, nowMs)

            null -> {
                val auth = authState()
                when {
                    auth.traktAuthenticated -> listProviderContinueWatching(WatchProvider.TRAKT, targetLimit, nowMs)
                    auth.simklAuthenticated -> listProviderContinueWatching(WatchProvider.SIMKL, targetLimit, nowMs)
                    else -> {
                        val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                        ContinueWatchingResult(
                            statusMessage = if (local.isNotEmpty()) "" else "No continue watching entries yet.",
                            entries = local,
                        )
                    }
                }
            }
        }
    }

    override suspend fun getCachedContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?,
    ): ContinueWatchingResult {
        return watchHistoryCache.getCachedContinueWatching(
            limit = limit,
            nowMs = nowMs,
            source = source,
            localFallback = { listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = limit.coerceAtLeast(1)) },
            normalize = { entries, _, targetLimit ->
                entries.sortedByDescending { it.lastUpdatedEpochMs }.take(targetLimit)
            },
        )
    }

    override suspend fun listWatchedEpisodeRecords(source: WatchProvider?): List<WatchedEpisodeRecord> {
        return when (source) {
            WatchProvider.LOCAL -> localWatchedEpisodeRecords()
            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> providerWatchedEpisodeRecords(source)
            null -> {
                val auth = authState()
                when {
                    auth.traktAuthenticated -> providerWatchedEpisodeRecords(WatchProvider.TRAKT)
                    auth.simklAuthenticated -> providerWatchedEpisodeRecords(WatchProvider.SIMKL)
                    else -> emptyList()
                }
            }
        }
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int, source: WatchProvider?): ProviderLibrarySnapshot {
        if (source == WatchProvider.LOCAL) {
            return ProviderLibrarySnapshot(statusMessage = "Local source selected. Provider library unavailable.")
        }

        if (source == null) {
            return listMergedProviderLibrary(limitPerFolder.coerceAtLeast(1))
        }

        val backendContext = getBackendContext()
            ?: return ProviderLibrarySnapshot(statusMessage = connectLibraryMessage(source))

        return try {
            val response = backend.getProfileLibrary(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                source = source.toLibrarySource(),
                limitPerFolder = limitPerFolder.coerceAtLeast(1),
            )
            val authProviders = persistAuthState(response.auth.providers)
            val snapshot = response.diagnostics.providers.firstOrNull { it.provider.equals(source.apiValue(), ignoreCase = true) }
            val mapped = snapshot?.toProviderLibrary(source) ?: ProviderLibrarySnapshot(statusMessage = "")
            val status = when {
                mapped.folders.isNotEmpty() || mapped.items.isNotEmpty() -> mapped.statusMessage
                !authProviders.isProviderConnected(source) -> connectLibraryMessage(source)
                mapped.statusMessage.isNotBlank() -> mapped.statusMessage
                else -> "No ${providerLabel(source)} library data available."
            }
            val result = mapped.copy(statusMessage = status)
            watchHistoryCache.writeProviderLibraryCache(source, result)
            result
        } catch (_: Throwable) {
            val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
            cached.copy(statusMessage = "${providerLabel(source)} temporarily unavailable. ${cached.statusMessage}")
        }
    }

    override suspend fun getCachedProviderLibrary(limitPerFolder: Int, source: WatchProvider?): ProviderLibrarySnapshot {
        return watchHistoryCache.getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
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
                    remoteImdbId = existing?.remoteImdbId ?: normalizedImdbIdOrNull(identity.imdbId),
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

    private suspend fun updateWatchedState(
        request: WatchHistoryRequest,
        source: WatchProvider?,
        shouldMark: Boolean,
    ): WatchHistoryResult {
        val normalized = runCatching { localStore.normalizeRequest(request) }.getOrElse { error ->
            return WatchHistoryResult(statusMessage = error.message ?: "Invalid watch history request.")
        }

        val updated =
            if (shouldMark) {
                localStore.upsertEntry(localStore.loadEntries(), normalized.toLocalWatchedItem())
            } else {
                localStore.removeEntry(localStore.loadEntries(), normalized)
            }
        localStore.saveEntries(updated)

        val synced =
            if (source == WatchProvider.LOCAL || source == null) {
                false
            } else {
                syncWatchedMutation(normalized, shouldMark)
            }

        return WatchHistoryResult(
            statusMessage = if (shouldMark) "Marked watched." else "Removed from watched.",
            entries = updated.sortedByDescending { it.watchedAtEpochMs }.map { it.toPublicEntry() },
            authState = authState(),
            syncedToTrakt = synced && source == WatchProvider.TRAKT,
            syncedToSimkl = synced && source == WatchProvider.SIMKL,
        )
    }

    private suspend fun syncWatchedMutation(request: NormalizedWatchRequest, shouldMark: Boolean): Boolean {
        val backendContext = getBackendContext() ?: return false
        val resolved = try {
            backend.resolvePlayback(
                accessToken = backendContext.accessToken,
                input = request.toPlaybackLookupInput(),
            )
        } catch (_: Throwable) {
            null
        } ?: return false

        val item = resolved.item
        val mutationInput = WatchMutationInput(
            mediaKey = item.mediaKey,
            mediaType = item.mediaType,
            tmdbId = if (item.mediaType == "movie") item.tmdbId else null,
            showTmdbId = item.showTmdbId ?: resolved.show?.tmdbId ?: if (item.mediaType == "show") item.tmdbId else null,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            occurredAt = Instant.ofEpochMilli(request.watchedAtEpochMs).toString(),
        )

        val response = try {
            if (shouldMark) {
                backend.markWatched(backendContext.accessToken, backendContext.profileId, mutationInput)
            } else {
                backend.unmarkWatched(backendContext.accessToken, backendContext.profileId, mutationInput)
            }
        } catch (_: Throwable) {
            null
        } ?: return false

        return response.accepted
    }

    private suspend fun listProviderContinueWatching(
        source: WatchProvider,
        limit: Int,
        nowMs: Long,
    ): ContinueWatchingResult {
        val backendContext = getBackendContext()
            ?: return ContinueWatchingResult(
                statusMessage = connectContinueWatchingMessage(source),
                isError = true,
            )

        return try {
            val authState = persistAuthState(
                backend.getProviderAuthState(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                )
            )
            if (!authState.isProviderConnected(source)) {
                return ContinueWatchingResult(
                    statusMessage = connectContinueWatchingMessage(source),
                    isError = true,
                )
            }

            val entries = backend
                .listContinueWatching(
                    accessToken = backendContext.accessToken,
                    profileId = backendContext.profileId,
                    limit = limit.coerceAtLeast(1),
                ).items
                .toContinueWatchingEntries(
                    provider = source,
                    nowMs = nowMs,
                    limit = limit,
                )
            val status = when {
                entries.isNotEmpty() -> ""
                !authState.isProviderConnected(source) -> connectContinueWatchingMessage(source)
                else -> "No ${providerLabel(source)} continue watching entries available."
            }
            val result = ContinueWatchingResult(statusMessage = status, entries = entries)
            watchHistoryCache.writeContinueWatchingCache(source, result)
            result
        } catch (_: Throwable) {
            val cached = getCachedContinueWatching(limit = limit, nowMs = nowMs, source = source)
            cached.copy(
                statusMessage = "${providerLabel(source)} temporarily unavailable. ${cached.statusMessage}",
                isError = true,
            )
        }
    }

    private suspend fun listMergedProviderLibrary(limitPerFolder: Int): ProviderLibrarySnapshot {
        val auth = authState()
        val providers = buildList {
            if (auth.traktAuthenticated) add(WatchProvider.TRAKT)
            if (auth.simklAuthenticated) add(WatchProvider.SIMKL)
        }

        if (providers.isEmpty()) {
            return ProviderLibrarySnapshot(statusMessage = "Connect Trakt or Simkl to load provider library.")
        }

        val folders = mutableListOf<ProviderLibraryFolder>()
        val items = mutableListOf<ProviderLibraryItem>()
        var temporaryFailure = false

        providers.forEach { provider ->
            val snapshot = listProviderLibrary(limitPerFolder = limitPerFolder, source = provider)
            if (snapshot.statusMessage.startsWith("${providerLabel(provider)} temporarily unavailable")) {
                temporaryFailure = true
            }
            folders += snapshot.folders
            items += snapshot.items
        }

        if (folders.isEmpty() && items.isEmpty()) {
            val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = null)
            if (cached.folders.isNotEmpty() || cached.items.isNotEmpty()) {
                return cached
            }
        }

        val status = when {
            folders.isNotEmpty() || items.isNotEmpty() -> if (temporaryFailure) "Provider data partially unavailable." else ""
            temporaryFailure -> "Provider data temporarily unavailable."
            else -> "No provider library data available."
        }

        return ProviderLibrarySnapshot(
            statusMessage = status,
            folders = folders.sortedBy { it.label.lowercase(Locale.US) },
            items = items.sortedByDescending { it.addedAtEpochMs },
        )
    }

    private suspend fun providerWatchedEpisodeRecords(source: WatchProvider): List<WatchedEpisodeRecord> {
        val snapshot = listProviderLibrary(limitPerFolder = 1000, source = source)
        return snapshot.items
            .asSequence()
            .filter { item ->
                item.provider == source &&
                    source.isWatchedFolder(item.folderId) &&
                    item.season != null &&
                    item.episode != null
            }
            .map { item ->
                WatchedEpisodeRecord(
                    contentId = item.contentId,
                    season = item.season ?: 1,
                    episode = item.episode ?: 1,
                    watchedAtEpochMs = item.addedAtEpochMs,
                )
            }
            .toList()
    }

    private fun localWatchedEpisodeRecords(): List<WatchedEpisodeRecord> {
        return localStore
            .listLocalHistory(limit = Int.MAX_VALUE, authState = authState())
            .entries
            .mapNotNull { entry ->
                val season = entry.season ?: return@mapNotNull null
                val episode = entry.episode ?: return@mapNotNull null
                WatchedEpisodeRecord(
                    contentId = entry.contentId,
                    season = season,
                    episode = episode,
                    watchedAtEpochMs = entry.watchedAtEpochMs,
                )
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
        }

        val playbackInput = PlaybackEventInput(
            clientEventId = buildClientEventId(identity, eventType),
            eventType = eventType,
            mediaType = mediaType,
            tmdbId = if (mediaType == "movie") identity.tmdbId else null,
            showTmdbId = if (mediaType == "episode") identity.tmdbId else null,
            seasonNumber = identity.season,
            episodeNumber = identity.episode,
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
            listOf(
                identity.contentId?.trim()?.takeIf { it.isNotBlank() },
                identity.imdbId?.trim()?.takeIf { it.isNotBlank() },
                identity.tmdbId?.toString(),
                identity.season?.toString(),
                identity.episode?.toString(),
            ).filterNotNull()
                .joinToString(":")
                .ifBlank { identity.title.trim().replace(' ', '_') }
        return "$eventType:$suffix:${System.currentTimeMillis()}"
    }

    private suspend fun getBackendContext(): BackendContext? {
        if (!supabase.isConfigured() || !backend.isConfigured()) {
            return null
        }
        val session = supabase.ensureValidSession() ?: return null
        var profileId = activeProfileStore.getActiveProfileId(session.userId).orEmpty().trim()
        if (profileId.isBlank()) {
            profileId = try {
                backend.getMe(session.accessToken).profiles.firstOrNull()?.id.orEmpty().trim()
            } catch (_: Throwable) {
                ""
            }
            if (profileId.isNotBlank()) {
                activeProfileStore.setActiveProfileId(session.userId, profileId)
            }
        }
        if (profileId.isBlank()) {
            return null
        }
        return BackendContext(accessToken = session.accessToken, profileId = profileId)
    }

    private fun loadStoredAuthState(): WatchProviderAuthState {
        val traktConnected = prefs.getBoolean(KEY_BACKEND_TRAKT_CONNECTED, false)
        val simklConnected = prefs.getBoolean(KEY_BACKEND_SIMKL_CONNECTED, false)
        val traktHandle = prefs.getString(KEY_BACKEND_TRAKT_USERNAME, null)?.trim().orEmpty().ifBlank { null }
        val simklHandle = prefs.getString(KEY_BACKEND_SIMKL_USERNAME, null)?.trim().orEmpty().ifBlank { null }
        return WatchProviderAuthState(
            traktAuthenticated = traktConnected,
            simklAuthenticated = simklConnected,
            traktSession = traktHandle?.let { WatchProviderSession(accessToken = BACKEND_SESSION_TOKEN, userHandle = it) },
            simklSession = simklHandle?.let { WatchProviderSession(accessToken = BACKEND_SESSION_TOKEN, userHandle = it) },
        )
    }

    private fun persistAuthState(states: List<ProviderAuthState>): WatchProviderAuthState {
        val trakt = states.firstOrNull { it.provider.equals(WatchProvider.TRAKT.apiValue(), ignoreCase = true) }
        val simkl = states.firstOrNull { it.provider.equals(WatchProvider.SIMKL.apiValue(), ignoreCase = true) }
        prefs.edit()
            .putBoolean(KEY_BACKEND_TRAKT_CONNECTED, trakt?.connected == true)
            .putBoolean(KEY_BACKEND_SIMKL_CONNECTED, simkl?.connected == true)
            .putString(KEY_BACKEND_TRAKT_USERNAME, trakt?.externalUsername)
            .putString(KEY_BACKEND_SIMKL_USERNAME, simkl?.externalUsername)
            .apply()
        return loadStoredAuthState().also { cachedAuthState = it }
    }

    private fun clearStoredAuthState(): WatchProviderAuthState {
        prefs.edit()
            .remove(KEY_BACKEND_TRAKT_CONNECTED)
            .remove(KEY_BACKEND_SIMKL_CONNECTED)
            .remove(KEY_BACKEND_TRAKT_USERNAME)
            .remove(KEY_BACKEND_SIMKL_USERNAME)
            .apply()
        return loadStoredAuthState().also { cachedAuthState = it }
    }

    private fun clearProvider(provider: WatchProvider): WatchProviderAuthState {
        when (provider) {
            WatchProvider.TRAKT -> prefs.edit().remove(KEY_BACKEND_TRAKT_CONNECTED).remove(KEY_BACKEND_TRAKT_USERNAME).apply()
            WatchProvider.SIMKL -> prefs.edit().remove(KEY_BACKEND_SIMKL_CONNECTED).remove(KEY_BACKEND_SIMKL_USERNAME).apply()
            WatchProvider.LOCAL -> Unit
        }
        return loadStoredAuthState().also { cachedAuthState = it }
    }

    private suspend fun listContinueWatchingFromLocalProgress(nowMs: Long, limit: Int): List<ContinueWatchingEntry> {
        val removed = watchProgressStore.getContinueWatchingRemoved()
        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS

        data class SeriesCandidate(
            val showId: String,
            val season: Int,
            val episode: Int,
            val progressPercent: Double,
            val lastUpdatedEpochMs: Long,
        )

        val movies = ArrayList<ContinueWatchingEntry>()
        val latestInProgressEpisodeByShow = LinkedHashMap<String, SeriesCandidate>()
        val maxWatchedEpisodeByShow = LinkedHashMap<String, Pair<Int, Int>>()
        val watchedSetByShow = LinkedHashMap<String, MutableSet<String>>()
        val latestWatchedAtByShow = LinkedHashMap<String, Long>()

        for ((rawKey, progress) in watchProgressStore.getAllWatchProgress()) {
            val parts = rawKey.split(':')
            if (parts.size < 2) continue

            val type = parts[0]
            val id = parts[1]
            if (id.isBlank()) continue

            val baseKey = "$type:$id"
            val removedAt = removed[baseKey]
            if (removedAt != null && progress.lastUpdatedEpochMs <= removedAt) {
                continue
            }

            val lastUpdated = progress.lastUpdatedEpochMs
            if (lastUpdated <= 0L || lastUpdated < staleCutoff) continue

            val percent = progress.progressPercentOrZero()
            if (percent < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) continue

            when (type) {
                "movie" -> {
                    movies += ContinueWatchingEntry(
                        contentId = id,
                        contentType = MetadataLabMediaType.MOVIE,
                        title = id,
                        season = null,
                        episode = null,
                        progressPercent = percent,
                        lastUpdatedEpochMs = lastUpdated,
                        provider = WatchProvider.LOCAL,
                    )
                }

                "series" -> {
                    if (parts.size < 4) continue
                    val season = parts[parts.size - 2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                    val episode = parts[parts.size - 1].toIntOrNull()?.takeIf { it > 0 } ?: continue

                    if (percent >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                        val watchedKey = "$id:$season:$episode"
                        watchedSetByShow.getOrPut(id) { linkedSetOf() }.add(watchedKey)
                        val existingMax = maxWatchedEpisodeByShow[id]
                        val nextMax = season to episode
                        if (existingMax == null ||
                            nextMax.first > existingMax.first ||
                            (nextMax.first == existingMax.first && nextMax.second > existingMax.second)
                        ) {
                            maxWatchedEpisodeByShow[id] = nextMax
                        }
                        latestWatchedAtByShow[id] = maxOf(latestWatchedAtByShow[id] ?: 0L, lastUpdated)
                    } else {
                        val candidate = SeriesCandidate(
                            showId = id,
                            season = season,
                            episode = episode,
                            progressPercent = percent,
                            lastUpdatedEpochMs = lastUpdated,
                        )
                        val existing = latestInProgressEpisodeByShow[id]
                        if (existing == null || candidate.lastUpdatedEpochMs > existing.lastUpdatedEpochMs) {
                            latestInProgressEpisodeByShow[id] = candidate
                        }
                    }
                }
            }
        }

        val seriesEntries = latestInProgressEpisodeByShow.values.map {
            ContinueWatchingEntry(
                contentId = it.showId,
                contentType = MetadataLabMediaType.SERIES,
                title = it.showId,
                season = it.season,
                episode = it.episode,
                progressPercent = it.progressPercent,
                lastUpdatedEpochMs = it.lastUpdatedEpochMs,
                provider = WatchProvider.LOCAL,
            )
        }

        val placeholderCandidates =
            maxWatchedEpisodeByShow
                .filterKeys { it !in latestInProgressEpisodeByShow.keys }
                .entries
                .sortedByDescending { (showId, _) -> latestWatchedAtByShow[showId] ?: 0L }

        val placeholders = ArrayList<ContinueWatchingEntry>()
        for ((showId, maxEpisode) in placeholderCandidates) {
            if (movies.size + seriesEntries.size + placeholders.size >= limit) break
            val lastWatchedAt = latestWatchedAtByShow[showId] ?: 0L
            if (lastWatchedAt < staleCutoff) continue

            val episodeList = try {
                episodeListProvider.fetchEpisodeList(mediaType = "series", contentId = showId, seasonHint = null)
            } catch (_: Throwable) {
                null
            } ?: continue

            val next = findNextEpisode(
                currentSeason = maxEpisode.first,
                currentEpisode = maxEpisode.second,
                episodes = episodeList,
                watchedSet = watchedSetByShow[showId],
                showId = showId,
            ) ?: continue

            placeholders += ContinueWatchingEntry(
                contentId = showId,
                contentType = MetadataLabMediaType.SERIES,
                title = showId,
                season = next.season,
                episode = next.episode,
                progressPercent = 0.0,
                lastUpdatedEpochMs = lastWatchedAt,
                provider = WatchProvider.LOCAL,
                isUpNextPlaceholder = true,
            )
        }

        return (movies + seriesEntries + placeholders)
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun progressKeyParts(identity: PlaybackIdentity): ProgressKeyParts? {
        val type = when (identity.contentType) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> "series"
        }

        val id = identity.contentId?.trim()?.takeIf { it.isNotBlank() } ?: normalizedImdbIdOrNull(identity.imdbId) ?: return null
        val episodeId =
            if (identity.contentType == MetadataLabMediaType.SERIES && identity.season != null && identity.episode != null) {
                "$id:${identity.season}:${identity.episode}"
            } else {
                null
            }

        return ProgressKeyParts(type = type, id = id, episodeId = episodeId)
    }

    private data class ProgressKeyParts(
        val type: String,
        val id: String,
        val episodeId: String?,
    )

    private data class BackendContext(
        val accessToken: String,
        val profileId: String,
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

    private fun providerLabel(provider: WatchProvider): String {
        return when (provider) {
            WatchProvider.TRAKT -> "Trakt"
            WatchProvider.SIMKL -> "Simkl"
            WatchProvider.LOCAL -> "Local"
        }
    }

    private fun connectContinueWatchingMessage(provider: WatchProvider): String {
        return "Connect ${providerLabel(provider)} to load continue watching."
    }

    private fun connectLibraryMessage(provider: WatchProvider): String {
        return "Connect ${providerLabel(provider)} to load provider library."
    }

    private fun WatchProvider.apiValue(): String {
        return when (this) {
            WatchProvider.TRAKT -> "trakt"
            WatchProvider.SIMKL -> "simkl"
            WatchProvider.LOCAL -> "local"
        }
    }

    private fun WatchProvider.toImportProvider(): ImportProvider {
        return when (this) {
            WatchProvider.TRAKT -> ImportProvider.TRAKT
            WatchProvider.SIMKL -> ImportProvider.SIMKL
            WatchProvider.LOCAL -> throw IllegalArgumentException("Local provider has no backend import provider.")
        }
    }

    private fun WatchProvider.toLibrarySource(): LibrarySource {
        return when (this) {
            WatchProvider.TRAKT -> LibrarySource.TRAKT
            WatchProvider.SIMKL -> LibrarySource.SIMKL
            WatchProvider.LOCAL -> LibrarySource.LOCAL
        }
    }

    private fun WatchProvider?.toLibraryMutationSource(): LibraryMutationSource? {
        return when (this) {
            WatchProvider.TRAKT -> LibraryMutationSource.TRAKT
            WatchProvider.SIMKL -> LibraryMutationSource.SIMKL
            WatchProvider.LOCAL,
            null -> null
        }
    }

    private fun WatchProviderAuthState.isProviderConnected(provider: WatchProvider): Boolean {
        return when (provider) {
            WatchProvider.TRAKT -> traktAuthenticated
            WatchProvider.SIMKL -> simklAuthenticated
            WatchProvider.LOCAL -> true
        }
    }

    private fun isProviderMutationSuccessful(
        response: CrispyBackendClient.LibraryMutationResponse,
        provider: WatchProvider,
    ): Boolean {
        return response.results.any { result ->
            result.provider.equals(provider.apiValue(), ignoreCase = true) &&
                result.status.equals("success", ignoreCase = true)
        }
    }

    private fun MetadataLabMediaType.toBackendMediaType(request: WatchHistoryRequest): String {
        return when (this) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> if (request.season != null && request.episode != null) "episode" else "show"
        }
    }

    private fun WatchHistoryRequest.toProviderLookupInput(): MediaLookupInput {
        return MediaLookupInput(
            id = contentId,
            mediaType = contentType.toBackendMediaType(this),
            imdbId = remoteImdbId,
            seasonNumber = season,
            episodeNumber = episode,
        )
    }

    private fun NormalizedWatchRequest.toPlaybackLookupInput(): MediaLookupInput {
        return MediaLookupInput(
            id = contentId,
            mediaType = if (contentType == MetadataLabMediaType.MOVIE) "movie" else "episode",
            imdbId = remoteImdbId,
            seasonNumber = season,
            episodeNumber = episode,
        )
    }

    private suspend fun List<CrispyBackendClient.HydratedWatchItem>.toContinueWatchingEntries(
        provider: WatchProvider,
        nowMs: Long,
        limit: Int,
    ): List<ContinueWatchingEntry> {
        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        return buildList {
            for (item in this@toContinueWatchingEntries) {
                val media = item.media
                val updatedAt =
                    parseIsoToEpochMs(item.lastActivityAt)
                        ?: parseIsoToEpochMs(item.progress?.lastPlayedAt)
                        ?: parseIsoToEpochMs(item.watchedAt)
                        ?: nowMs
                if (updatedAt < staleCutoff) {
                    continue
                }
                add(
                    ContinueWatchingEntry(
                        contentId = media.canonicalContentId(),
                        contentType = media.mediaType.toMetadataLabMediaType(),
                        title = media.continueWatchingTitle(),
                        season = media.seasonNumber,
                        episode = media.episodeNumber,
                        progressPercent = item.progress?.progressPercent ?: 0.0,
                        lastUpdatedEpochMs = updatedAt,
                        provider = provider,
                        providerPlaybackId = item.id,
                    )
                )
            }
        }
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun BackendProviderLibrarySnapshot.toProviderLibrary(provider: WatchProvider): ProviderLibrarySnapshot {
        return ProviderLibrarySnapshot(
            statusMessage = statusMessage,
            folders = folders.map { folder ->
                ProviderLibraryFolder(
                    id = folder.id,
                    label = folder.label,
                    provider = provider,
                    itemCount = folder.itemCount,
                )
            },
            items = items.map { item ->
                ProviderLibraryItem(
                    provider = provider,
                    folderId = item.folderId,
                    contentId = item.contentId,
                    contentType = item.contentType.toMetadataLabMediaType(),
                    title = item.title,
                    posterUrl = item.posterUrl ?: item.media?.images?.posterUrl,
                    backdropUrl = item.backdropUrl ?: item.media?.images?.backdropUrl,
                    externalIds = item.externalIds.toProviderExternalIds(),
                    season = item.seasonNumber,
                    episode = item.episodeNumber,
                    addedAtEpochMs = parseIsoToEpochMs(item.addedAt) ?: System.currentTimeMillis(),
                )
            },
        )
    }

    private fun CrispyBackendClient.MetadataExternalIds?.toProviderExternalIds(): ProviderExternalIds? {
        val ids = this ?: return null
        if (ids.tmdb == null && ids.imdb == null && ids.tvdb == null) return null
        return ProviderExternalIds(tmdb = ids.tmdb, imdb = ids.imdb, tvdb = ids.tvdb)
    }

    private fun CrispyBackendClient.MetadataView.canonicalContentId(): String {
        return id.trim()
    }

    private fun CrispyBackendClient.MetadataView.continueWatchingTitle(): String {
        return when {
            mediaType.equals("episode", ignoreCase = true) -> subtitle?.trim().orEmpty().ifBlank { title?.trim().orEmpty() }.ifBlank { canonicalContentId() }
            else -> title?.trim().orEmpty().ifBlank { canonicalContentId() }
        }
    }

    private fun String.toMetadataLabMediaType(): MetadataLabMediaType {
        return when (trim().lowercase(Locale.US)) {
            "show", "series", "tv", "episode" -> MetadataLabMediaType.SERIES
            else -> MetadataLabMediaType.MOVIE
        }
    }

    private fun WatchProvider.isWatchedFolder(folderId: String): Boolean {
        val normalized = folderId.trim().lowercase(Locale.US)
        return when (this) {
            WatchProvider.TRAKT -> normalized == "watched"
            WatchProvider.SIMKL -> normalized.startsWith("completed")
            WatchProvider.LOCAL -> false
        }
    }

    private companion object {
        private const val WATCH_PROGRESS_PREFS_NAME = "watch_progress"
        private const val BACKEND_SESSION_TOKEN = "backend"
        private const val KEY_BACKEND_TRAKT_CONNECTED = "backend_provider_trakt_connected"
        private const val KEY_BACKEND_SIMKL_CONNECTED = "backend_provider_simkl_connected"
        private const val KEY_BACKEND_TRAKT_USERNAME = "backend_provider_trakt_username"
        private const val KEY_BACKEND_SIMKL_USERNAME = "backend_provider_simkl_username"
    }
}
