package com.crispy.tv.watchhistory

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.ImportProvider
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.PlaybackEventInput
import com.crispy.tv.backend.CrispyBackendClient.ProviderAuthState
import com.crispy.tv.backend.CrispyBackendClient.WatchMutationInput
import com.crispy.tv.domain.watch.findNextEpisode
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.CanonicalProviderLibraryItem
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
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

        if (source == null) {
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
                authState = authState(),
                accepted = action.accepted,
            )
        }
        return WatchHistoryResult(statusMessage = "Provider watchlist sync is unavailable right now.")
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
            authState = authState(),
            accepted = action.accepted,
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

        if (source == null) {
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
                authState = authState(),
                accepted = action.accepted,
            )
        }
        return WatchHistoryResult(statusMessage = "Provider rating sync is unavailable right now.")
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
            authState = authState(),
            accepted = action.accepted,
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
            accepted = synced,
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
            WatchProvider.SIMKL -> listCanonicalBackendContinueWatching(source, targetLimit, nowMs)

            null -> {
                val auth = authState()
                when {
                    auth.traktAuthenticated -> listCanonicalBackendContinueWatching(WatchProvider.TRAKT, targetLimit, nowMs)
                    auth.simklAuthenticated -> listCanonicalBackendContinueWatching(WatchProvider.SIMKL, targetLimit, nowMs)
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
            WatchProvider.SIMKL -> canonicalWatchedEpisodeRecords(source)
            null -> {
                val auth = authState()
                when {
                    auth.traktAuthenticated -> canonicalWatchedEpisodeRecords(WatchProvider.TRAKT)
                    auth.simklAuthenticated -> canonicalWatchedEpisodeRecords(WatchProvider.SIMKL)
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
            val auth = authState()
            return when {
                auth.traktAuthenticated -> listCanonicalLibrarySnapshot(WatchProvider.TRAKT)
                auth.simklAuthenticated -> listCanonicalLibrarySnapshot(WatchProvider.SIMKL)
                else -> ProviderLibrarySnapshot(statusMessage = "Connect Trakt or Simkl to load provider library.")
            }
        }

        val backendContext = getBackendContext()
            ?: return ProviderLibrarySnapshot(statusMessage = connectLibraryMessage(source))
        val sectionLimit = limitPerFolder.coerceAtLeast(1)

        return try {
            val discovery = backend.getProfileLibrary(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
            )
            val authProviders = persistAuthState(discovery.auth.providers)
            if (!authProviders.isProviderConnected(source)) {
                return ProviderLibrarySnapshot(statusMessage = connectLibraryMessage(source))
            }
            val mapped = loadProviderLibrarySnapshot(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                provider = source,
                sections = discovery.sections,
                limitPerSection = sectionLimit,
            )
            val status = when {
                mapped.folders.isNotEmpty() || mapped.items.isNotEmpty() -> ""
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

    override suspend fun getCanonicalProviderLibraryItems(
        limitPerFolder: Int,
        source: WatchProvider,
    ): List<CanonicalProviderLibraryItem> {
        if (source == WatchProvider.LOCAL) {
            return emptyList()
        }

        val backendContext = getBackendContext() ?: return emptyList()
        val sectionLimit = limitPerFolder.coerceAtLeast(1)
        return try {
            val discovery = backend.getProfileLibrary(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
            )
            val authProviders = persistAuthState(discovery.auth.providers)
            if (!authProviders.isProviderConnected(source)) {
                return emptyList()
            }
            loadProviderLibraryPages(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                sections = discovery.sections,
                limitPerSection = sectionLimit,
            )
                .asSequence()
                .flatMap { (section, items) ->
                    items.asSequence()
                        .filter { item -> item.origins.any { it.equals(source.apiValue(), ignoreCase = true) } }
                        .map { item -> item.toCanonicalProviderLibraryItem(section.id) }
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun getCachedCanonicalProviderLibraryItems(
        limitPerFolder: Int,
        source: WatchProvider,
    ): List<CanonicalProviderLibraryItem> {
        if (source == WatchProvider.LOCAL) {
            return emptyList()
        }

        val snapshot = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
        return snapshot.items.map { item -> item.toCanonicalProviderLibraryItem() }
    }

    override suspend fun getCanonicalContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?,
    ): CanonicalContinueWatchingResult {
        val targetLimit = limit.coerceAtLeast(1)
        return when (source) {
            WatchProvider.LOCAL -> {
                val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                CanonicalContinueWatchingResult(
                    statusMessage = if (local.isNotEmpty()) "" else "No local continue watching entries yet.",
                    entries = local.map { it.toCanonicalContinueWatchingItem() },
                )
            }

            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> listCanonicalBackendContinueWatchingItems(source, targetLimit, nowMs)

            null -> {
                val auth = authState()
                when {
                    auth.traktAuthenticated -> listCanonicalBackendContinueWatchingItems(WatchProvider.TRAKT, targetLimit, nowMs)
                    auth.simklAuthenticated -> listCanonicalBackendContinueWatchingItems(WatchProvider.SIMKL, targetLimit, nowMs)
                    else -> {
                        val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                        CanonicalContinueWatchingResult(
                            statusMessage = if (local.isNotEmpty()) "" else "No continue watching entries yet.",
                            entries = local.map { it.toCanonicalContinueWatchingItem() },
                        )
                    }
                }
            }
        }
    }

    override suspend fun getCachedProviderLibrary(limitPerFolder: Int, source: WatchProvider?): ProviderLibrarySnapshot {
        return watchHistoryCache.getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
    }

    override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        val localSnapshot = localWatchStateSnapshot(identity)
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
                        ).item.mediaKey.trim().takeIf { it.isNotBlank() } ?: return mergeCanonicalWatchState(null, localSnapshot)
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
        return mergeCanonicalWatchState(backendSnapshot, localSnapshot)
    }

    override suspend fun getTitleWatchState(
        mediaKey: String,
        contentType: MetadataLabMediaType,
    ): CanonicalWatchStateSnapshot? {
        val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
        val localSnapshot = localWatchStateSnapshot(titleStateIdentity(normalizedMediaKey, contentType))
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
        return mergeCanonicalWatchState(backendSnapshot, localSnapshot)
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
            if (source == WatchProvider.LOCAL) {
                false
            } else {
                syncWatchedMutation(normalized, shouldMark)
            }

        val accepted =
            when (source) {
                WatchProvider.LOCAL -> true
                WatchProvider.TRAKT,
                WatchProvider.SIMKL,
                null -> synced
            }

        val successStatus = if (shouldMark) "Marked watched." else "Removed from watched."
        val failureStatus =
            when (source) {
                WatchProvider.LOCAL -> successStatus
                WatchProvider.TRAKT -> "Trakt watched sync failed."
                WatchProvider.SIMKL -> "Simkl watched sync failed."
                null -> "Watched update failed."
            }

        return WatchHistoryResult(
            statusMessage = if (accepted) successStatus else failureStatus,
            entries = updated.sortedByDescending { it.watchedAtEpochMs }.map { it.toPublicEntry() },
            authState = authState(),
            accepted = accepted,
            syncedToTrakt = synced && source == WatchProvider.TRAKT,
            syncedToSimkl = synced && source == WatchProvider.SIMKL,
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

    private suspend fun syncWatchedMutation(request: NormalizedWatchRequest, shouldMark: Boolean): Boolean {
        val backendContext = getBackendContext() ?: return false
        val mutationInput =
            request.toTitleWatchMutationInput()
                ?: run {
                    val resolved = try {
                        backend.resolvePlayback(
                            accessToken = backendContext.accessToken,
                            input = request.toPlaybackLookupInput(),
                        )
                    } catch (_: Throwable) {
                        null
                    } ?: return false

                    val item = resolved.item
                    WatchMutationInput(
                        mediaKey = item.mediaKey,
                        mediaType = item.mediaType,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                        provider = item.provider,
                        providerId = item.providerId,
                        parentProvider = item.parentProvider ?: request.parentProvider,
                        parentProviderId = item.parentProviderId ?: request.parentProviderId,
                        absoluteEpisodeNumber = item.absoluteEpisodeNumber ?: request.absoluteEpisodeNumber,
                        occurredAt = Instant.ofEpochMilli(request.watchedAtEpochMs).toString(),
                    )
                }

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

    private suspend fun listCanonicalBackendContinueWatching(
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

    private suspend fun listCanonicalBackendContinueWatchingItems(
        source: WatchProvider,
        limit: Int,
        nowMs: Long,
    ): CanonicalContinueWatchingResult {
        val backendContext = getBackendContext()
            ?: return CanonicalContinueWatchingResult(
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
                return CanonicalContinueWatchingResult(
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
                .toCanonicalContinueWatchingItems(
                    provider = source,
                    nowMs = nowMs,
                    limit = limit,
                )
            val status = when {
                entries.isNotEmpty() -> ""
                else -> "No ${providerLabel(source)} continue watching entries available."
            }
            CanonicalContinueWatchingResult(statusMessage = status, entries = entries)
        } catch (_: Throwable) {
            val cached = getCachedContinueWatching(limit = limit, nowMs = nowMs, source = source)
            CanonicalContinueWatchingResult(
                statusMessage = "${providerLabel(source)} temporarily unavailable. ${cached.statusMessage}",
                entries = cached.entries.map { it.toCanonicalContinueWatchingItem() },
                isError = true,
            )
        }
    }

    private suspend fun listCanonicalLibrarySnapshot(source: WatchProvider): ProviderLibrarySnapshot {
        val backendContext = getBackendContext()
            ?: return ProviderLibrarySnapshot(statusMessage = connectLibraryMessage(source))
        val sectionLimit = 250

        return try {
            val discovery = backend.getProfileLibrary(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
            )
            val authState = persistAuthState(discovery.auth.providers)
            if (!authState.isProviderConnected(source)) {
                return ProviderLibrarySnapshot(statusMessage = connectLibraryMessage(source))
            }
            val snapshot = loadProviderLibrarySnapshot(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
                provider = source,
                sections = discovery.sections,
                limitPerSection = sectionLimit,
            )
            watchHistoryCache.writeProviderLibraryCache(source, snapshot)
            snapshot
        } catch (_: Throwable) {
            val cached = getCachedProviderLibrary(limitPerFolder = 0, source = source)
            cached.copy(statusMessage = "${providerLabel(source)} temporarily unavailable. ${cached.statusMessage}")
        }
    }

    private suspend fun loadProviderLibrarySnapshot(
        accessToken: String,
        profileId: String,
        provider: WatchProvider,
        sections: List<CrispyBackendClient.LibrarySection>,
        limitPerSection: Int,
    ): ProviderLibrarySnapshot {
        val folders = mutableListOf<ProviderLibraryFolder>()
        val items = mutableListOf<ProviderLibraryItem>()
        for ((section, pageItems) in loadProviderLibraryPages(accessToken, profileId, sections, limitPerSection)) {
            val filteredItems = pageItems
                .filter { item -> item.origins.any { it.equals(provider.apiValue(), ignoreCase = true) } }
                .map { item -> item.toProviderLibraryItem(provider = provider, folderId = section.id) }
                .sortedByDescending { it.addedAtEpochMs }
            if (filteredItems.isEmpty()) {
                continue
            }
            folders += ProviderLibraryFolder(
                id = section.id,
                label = section.label,
                provider = provider,
                itemCount = filteredItems.size,
            )
            items += filteredItems
        }
        return ProviderLibrarySnapshot(
            statusMessage = "",
            folders = folders.sortedBy { it.label.lowercase(Locale.US) },
            items = items.sortedByDescending { it.addedAtEpochMs },
        )
    }

    private suspend fun loadProviderLibraryPages(
        accessToken: String,
        profileId: String,
        sections: List<CrispyBackendClient.LibrarySection>,
        limitPerSection: Int,
    ): List<Pair<CrispyBackendClient.LibrarySection, List<CrispyBackendClient.LibrarySectionItem>>> {
        val requestLimit = limitPerSection.coerceAtLeast(1)
        return buildList {
            for (section in sections.sortedBy { it.order }) {
                val page = backend.getProfileLibrarySectionPage(
                    accessToken = accessToken,
                    profileId = profileId,
                    sectionId = section.id,
                    limit = requestLimit,
                )
                add(section to page.items)
            }
        }
    }

    private suspend fun canonicalWatchedEpisodeRecords(source: WatchProvider): List<WatchedEpisodeRecord> {
        val providerOrigin = source.apiValue()
        return listCanonicalWatchHistory(limit = 1000)
            .asSequence()
            .filter { item ->
                if (item.origins.none { it.equals(providerOrigin, ignoreCase = true) }) {
                    return@filter false
                }
                item.media.mediaType.equals("episode", ignoreCase = true) &&
                    item.media.seasonNumber != null &&
                    item.media.episodeNumber != null
            }
            .map { item ->
                WatchedEpisodeRecord(
                    contentId = item.media.providerId,
                    season = item.media.seasonNumber ?: 1,
                    episode = item.media.episodeNumber ?: 1,
                    watchedAtEpochMs =
                        parseIsoToEpochMs(item.watchedAt)
                            ?: parseIsoToEpochMs(item.lastActivityAt)
                            ?: 0L,
                )
            }
            .filter { it.watchedAtEpochMs > 0L }
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
            MetadataLabMediaType.ANIME -> if (identity.season != null && identity.episode != null) "episode" else "anime"
        }

        val playbackInput = PlaybackEventInput(
            clientEventId = buildClientEventId(identity, eventType),
            eventType = eventType,
            mediaType = mediaType,
            seasonNumber = identity.season,
            episodeNumber = identity.episode,
            provider = identity.provider,
            providerId = identity.providerId,
            parentProvider = identity.parentProvider,
            parentProviderId = identity.parentProviderId,
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
            listOf(
                identity.contentId?.trim()?.takeIf { it.isNotBlank() },
                identity.imdbId?.trim()?.takeIf { it.isNotBlank() },
                identity.season?.toString(),
                identity.episode?.toString(),
                identity.provider?.trim()?.takeIf { it.isNotBlank() },
                identity.providerId?.trim()?.takeIf { it.isNotBlank() },
                identity.parentProvider?.trim()?.takeIf { it.isNotBlank() },
                identity.parentProviderId?.trim()?.takeIf { it.isNotBlank() },
                identity.absoluteEpisodeNumber?.toString(),
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

        data class EpisodicCandidate(
            val showId: String,
            val contentType: MetadataLabMediaType,
            val season: Int,
            val episode: Int,
            val progressPercent: Double,
            val lastUpdatedEpochMs: Long,
        )

        val movies = ArrayList<ContinueWatchingEntry>()
        val latestInProgressEpisodeByShow = LinkedHashMap<String, EpisodicCandidate>()
        val maxWatchedEpisodeByShow = LinkedHashMap<String, Pair<Int, Int>>()
        val watchedSetByShow = LinkedHashMap<String, MutableSet<String>>()
        val latestWatchedAtByShow = LinkedHashMap<String, Long>()
        val contentTypeByShow = LinkedHashMap<String, MetadataLabMediaType>()

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
                        id = "local:movie:$id",
                        mediaKey = id,
                        localKey = "local:movie:$id",
                        provider = "local",
                        providerId = id,
                        mediaType = "movie",
                        title = id,
                        season = null,
                        episode = null,
                        progressPercent = percent,
                        lastUpdatedEpochMs = lastUpdated,
                        source = WatchProvider.LOCAL,
                    )
                }

                "series", "anime" -> {
                    if (parts.size < 4) continue
                    val season = parts[parts.size - 2].toIntOrNull()?.takeIf { it > 0 } ?: continue
                    val episode = parts[parts.size - 1].toIntOrNull()?.takeIf { it > 0 } ?: continue
                    val contentType = if (type == "anime") MetadataLabMediaType.ANIME else MetadataLabMediaType.SERIES
                    contentTypeByShow[id] = contentType

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
                        val candidate = EpisodicCandidate(
                            showId = id,
                            contentType = contentType,
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
                id = "local:${it.contentType.label}:${it.showId}:${it.season}:${it.episode}",
                mediaKey = it.showId,
                localKey = "local:${it.contentType.label}:${it.showId}:${it.season}:${it.episode}",
                provider = "local",
                providerId = it.showId,
                mediaType = if (it.contentType == MetadataLabMediaType.ANIME) "anime" else "series",
                title = it.showId,
                season = it.season,
                episode = it.episode,
                progressPercent = it.progressPercent,
                lastUpdatedEpochMs = it.lastUpdatedEpochMs,
                source = WatchProvider.LOCAL,
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
            val episodicType = contentTypeByShow[showId] ?: MetadataLabMediaType.SERIES

            val episodeList = try {
                episodeListProvider.fetchEpisodeList(
                    mediaType = when (episodicType) {
                        MetadataLabMediaType.MOVIE -> "movie"
                        MetadataLabMediaType.SERIES -> "series"
                        MetadataLabMediaType.ANIME -> "anime"
                    },
                    contentId = showId,
                    seasonHint = null,
                )
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
                id = "local:${episodicType.label}:$showId:${next.season}:${next.episode}",
                mediaKey = showId,
                localKey = "local:${episodicType.label}:$showId:${next.season}:${next.episode}",
                provider = "local",
                providerId = showId,
                mediaType = if (episodicType == MetadataLabMediaType.ANIME) "anime" else "series",
                title = showId,
                season = next.season,
                episode = next.episode,
                progressPercent = 0.0,
                lastUpdatedEpochMs = lastWatchedAt,
                source = WatchProvider.LOCAL,
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
            MetadataLabMediaType.ANIME -> "anime"
        }

        val id = identity.contentId?.trim()?.takeIf { it.isNotBlank() } ?: normalizedImdbIdOrNull(identity.imdbId) ?: return null
        val episodeId =
            if (identity.contentType != MetadataLabMediaType.MOVIE && identity.season != null && identity.episode != null) {
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

    private fun WatchProviderAuthState.isProviderConnected(provider: WatchProvider): Boolean {
        return when (provider) {
            WatchProvider.TRAKT -> traktAuthenticated
            WatchProvider.SIMKL -> simklAuthenticated
            WatchProvider.LOCAL -> true
        }
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
            id = contentId,
            mediaKey = mediaKey?.trim()?.ifBlank { null },
            mediaType = contentType.toBackendMediaType(this),
            imdbId = remoteImdbId,
            seasonNumber = season,
            episodeNumber = episode,
            provider = provider,
            providerId = providerId,
            parentProvider = parentProvider,
            parentProviderId = parentProviderId,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        )
    }

    private fun PlaybackIdentity.toPlaybackLookupInput(): MediaLookupInput? {
        val normalizedImdb = normalizedImdbIdOrNull(imdbId)
        val normalizedContentId = contentId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedMediaKey = mediaKey?.trim()?.takeIf { it.isNotBlank() }
        val mediaType = when (contentType) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> if (season != null && episode != null) "episode" else "show"
            MetadataLabMediaType.ANIME -> if (season != null && episode != null) "episode" else "anime"
        }
        return MediaLookupInput(
            id = normalizedContentId,
            mediaKey = normalizedMediaKey,
            mediaType = mediaType,
            imdbId = normalizedImdb,
            seasonNumber = season,
            episodeNumber = episode,
            provider = provider,
            providerId = providerId,
            parentProvider = parentProvider,
            parentProviderId = parentProviderId,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        ).takeIf {
            it.mediaKey != null ||
                it.id != null ||
                it.imdbId != null ||
                (it.provider != null && it.providerId != null) ||
                (it.parentProvider != null && it.parentProviderId != null)
        }
    }

    private fun NormalizedWatchRequest.toPlaybackLookupInput(): MediaLookupInput {
        return MediaLookupInput(
            id = contentId,
            mediaKey = mediaKey?.trim()?.ifBlank { null },
            mediaType = when (contentType) {
                MetadataLabMediaType.MOVIE -> "movie"
                MetadataLabMediaType.SERIES,
                MetadataLabMediaType.ANIME -> "episode"
            },
            imdbId = remoteImdbId,
            seasonNumber = season,
            episodeNumber = episode,
            provider = provider,
            providerId = providerId,
            parentProvider = parentProvider,
            parentProviderId = parentProviderId,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        )
    }

    private fun NormalizedWatchRequest.toTitleWatchMutationInput(): WatchMutationInput? {
        val normalizedMediaKey = mediaKey?.trim()?.ifBlank { null } ?: return null
        if (season != null || episode != null) {
            return null
        }
        val mediaType = when (contentType) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> "show"
            MetadataLabMediaType.ANIME -> "anime"
        }
        return WatchMutationInput(
            mediaKey = normalizedMediaKey,
            mediaType = mediaType,
            occurredAt = Instant.ofEpochMilli(watchedAtEpochMs).toString(),
        )
    }

    private fun List<CrispyBackendClient.ContinueWatchingItem>.toContinueWatchingEntries(
        provider: WatchProvider,
        nowMs: Long,
        limit: Int,
    ): List<ContinueWatchingEntry> {
        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val providerOrigin = provider.apiValue()
        return buildList {
            for (item in this@toContinueWatchingEntries) {
                if (item.origins.none { it.equals(providerOrigin, ignoreCase = true) }) continue
                val updatedAt =
                    parseIsoToEpochMs(item.lastActivityAt)
                        ?: parseIsoToEpochMs(item.progress?.lastPlayedAt)
                        ?: nowMs
                if (updatedAt < staleCutoff) continue
                add(
                    ContinueWatchingEntry(
                        id = item.id,
                        mediaKey = item.media.mediaKey,
                        localKey = item.media.mediaKey,
                        provider = item.media.provider,
                        providerId = item.media.providerId,
                        mediaType = item.media.mediaType,
                        title = item.media.episodeTitle ?: item.media.title,
                        season = item.media.seasonNumber,
                        episode = item.media.episodeNumber,
                        progressPercent = item.progress?.progressPercent ?: 0.0,
                        lastUpdatedEpochMs = updatedAt,
                        source = provider,
                        posterUrl = item.media.posterUrl,
                        backdropUrl = item.media.backdropUrl,
                        logoUrl = null,
                        addonId = "backend",
                        subtitle = item.media.subtitle,
                        dismissible = item.dismissible,
                        absoluteEpisodeNumber = null,
                    )
                )
            }
        }
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun List<CrispyBackendClient.ContinueWatchingItem>.toCanonicalContinueWatchingItems(
        provider: WatchProvider,
        nowMs: Long,
        limit: Int,
    ): List<CanonicalContinueWatchingItem> {
        return toContinueWatchingEntries(provider, nowMs, limit).map { it.toCanonicalContinueWatchingItem() }
    }

    private fun ContinueWatchingEntry.toCanonicalContinueWatchingItem(): CanonicalContinueWatchingItem {
        return CanonicalContinueWatchingItem(
            id = id,
            mediaKey = mediaKey,
            localKey = localKey,
            provider = provider,
            providerId = providerId,
            mediaType = mediaType,
            title = title,
            season = season,
            episode = episode,
            progressPercent = progressPercent,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
            source = source,
            isUpNextPlaceholder = isUpNextPlaceholder,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            addonId = addonId,
            subtitle = subtitle,
            dismissible = dismissible,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        )
    }

    private fun CrispyBackendClient.LibrarySectionItem.toProviderLibraryItem(
        provider: WatchProvider,
        folderId: String,
    ): ProviderLibraryItem {
        return ProviderLibraryItem(
            provider = provider,
            folderId = folderId,
            contentId = media.providerId,
            mediaKey = media.mediaKey,
            contentType = media.mediaType.toMetadataLabMediaType(),
            title = media.title,
            posterUrl = media.posterUrl,
            backdropUrl = media.backdropUrl,
            externalIds = null,
            season = media.seasonNumber,
            episode = media.episodeNumber,
            addedAtEpochMs = parseIsoToEpochMs(state.addedAt) ?: 0L,
        )
    }

    private fun CrispyBackendClient.LibrarySectionItem.toCanonicalProviderLibraryItem(folderId: String): CanonicalProviderLibraryItem {
        val resolvedContentId = media.providerId.trim()
        return CanonicalProviderLibraryItem(
            contentId = resolvedContentId,
            mediaKey = media.mediaKey,
            contentType = media.mediaType.toMetadataLabMediaType(),
            title = media.title.trim(),
            posterUrl = media.posterUrl,
            backdropUrl = media.backdropUrl,
            folderIds = setOf(folderId.trim()).filter { it.isNotBlank() }.toSet(),
            addedAtEpochMs = parseIsoToEpochMs(state.addedAt) ?: 0L,
        )
    }

    private fun ProviderLibraryItem.toCanonicalProviderLibraryItem(): CanonicalProviderLibraryItem {
        return CanonicalProviderLibraryItem(
            contentId = contentId.trim(),
            mediaKey = mediaKey,
            contentType = contentType,
            title = title.trim().ifBlank { contentId.trim() },
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            folderIds = setOf(folderId.trim()).filter { it.isNotBlank() }.toSet(),
            addedAtEpochMs = addedAtEpochMs,
        )
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
            contentId = mediaKey,
            mediaKey = mediaKey,
            imdbId = null,
            contentType = contentType,
            title = mediaKey,
        )
    }

    private fun mergeCanonicalWatchState(
        backendSnapshot: CanonicalWatchStateSnapshot?,
        localSnapshot: CanonicalWatchStateSnapshot?,
    ): CanonicalWatchStateSnapshot? {
        if (backendSnapshot == null && localSnapshot == null) return null
        val backend = backendSnapshot ?: CanonicalWatchStateSnapshot(
            isWatched = false,
            watchedAtEpochMs = null,
            isInWatchlist = false,
            isRated = false,
            userRating = null,
        )
        val local = localSnapshot ?: CanonicalWatchStateSnapshot(
            isWatched = false,
            watchedAtEpochMs = null,
            isInWatchlist = false,
            isRated = false,
            userRating = null,
        )
        return CanonicalWatchStateSnapshot(
            isWatched = backend.isWatched || local.isWatched,
            watchedAtEpochMs = listOfNotNull(backend.watchedAtEpochMs, local.watchedAtEpochMs).maxOrNull(),
            isInWatchlist = backend.isInWatchlist,
            isRated = backend.isRated,
            userRating = backend.userRating,
            watchedEpisodeKeys = backend.watchedEpisodeKeys + local.watchedEpisodeKeys,
        )
    }

    private fun localWatchStateSnapshot(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {
        val normalizedContentId = identity.contentId?.trim()?.lowercase(Locale.US).takeUnless { it.isNullOrBlank() } ?: return null
        val matchingEntries =
            localStore.loadEntries().filter { item ->
                item.contentType == identity.contentType &&
                    item.contentId.trim().lowercase(Locale.US) == normalizedContentId
            }
        if (matchingEntries.isEmpty()) return null

        val exactEntries =
            if (identity.contentType == MetadataLabMediaType.MOVIE || identity.season == null || identity.episode == null) {
                matchingEntries
            } else {
                matchingEntries.filter { it.season == identity.season && it.episode == identity.episode }
            }
        val watchedEntries = exactEntries.ifEmpty { matchingEntries }
        val watchedEpisodeKeys =
            if (identity.contentType == MetadataLabMediaType.MOVIE) {
                emptySet()
            } else {
                matchingEntries.mapNotNull { item ->
                    val season = item.season ?: return@mapNotNull null
                    val episode = item.episode ?: return@mapNotNull null
                    addEpisodeKey(item.contentId, season, episode)
                }.toSet()
            }
        return CanonicalWatchStateSnapshot(
            isWatched = watchedEntries.isNotEmpty(),
            watchedAtEpochMs = watchedEntries.maxOfOrNull { it.watchedAtEpochMs },
            isInWatchlist = false,
            isRated = false,
            userRating = null,
            watchedEpisodeKeys = watchedEpisodeKeys,
        )
    }

    private fun String.toMetadataLabMediaType(): MetadataLabMediaType {
        return when (trim().lowercase(Locale.US)) {
            "show", "series", "tv", "episode" -> MetadataLabMediaType.SERIES
            "anime" -> MetadataLabMediaType.ANIME
            else -> MetadataLabMediaType.MOVIE
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
