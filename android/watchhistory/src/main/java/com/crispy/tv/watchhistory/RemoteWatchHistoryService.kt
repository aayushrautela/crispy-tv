package com.crispy.tv.watchhistory

import android.content.Context
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.domain.watch.findNextEpisode
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.ProviderAuthActionResult
import com.crispy.tv.player.ProviderAuthStartResult
import com.crispy.tv.player.ProviderCommentQuery
import com.crispy.tv.player.ProviderCommentResult
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
import com.crispy.tv.player.ProviderRecommendationsResult
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.player.WatchProgressSyncResult
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import com.crispy.tv.watchhistory.cache.WatchHistoryCache
import com.crispy.tv.watchhistory.local.LocalWatchHistoryStore
import com.crispy.tv.watchhistory.oauth.OAuthCallbackParser
import com.crispy.tv.watchhistory.oauth.OAuthStateStore
import com.crispy.tv.watchhistory.oauth.Pkce
import com.crispy.tv.watchhistory.provider.ProviderRouter
import com.crispy.tv.watchhistory.provider.RecommendationResolver
import com.crispy.tv.watchhistory.provider.ContinueWatchingNormalizer
import com.crispy.tv.watchhistory.simkl.SimklOAuthClient
import com.crispy.tv.watchhistory.simkl.SimklService
import com.crispy.tv.watchhistory.simkl.SimklWatchHistoryProvider
import com.crispy.tv.watchhistory.progress.WatchProgress
import com.crispy.tv.watchhistory.progress.WatchProgressStore
import com.crispy.tv.watchhistory.progress.UnsyncedProgressItem
import com.crispy.tv.watchhistory.trakt.TraktApi
import com.crispy.tv.watchhistory.trakt.TraktOAuthClient
import com.crispy.tv.watchhistory.trakt.TraktScrobbleService
import com.crispy.tv.watchhistory.trakt.TraktWatchHistoryProvider
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

class RemoteWatchHistoryService(
    context: Context,
    httpClient: CrispyHttpClient,
    private val traktClientId: String,
    private val simklClientId: String,
    private val episodeListProvider: EpisodeListProvider,
    private val config: WatchHistoryConfig = WatchHistoryConfig(),
) : WatchHistoryService {
    private val appContext = context.applicationContext
    private val traktClientSecret = config.traktClientSecret
    private val traktRedirectUri = config.traktRedirectUri
    private val simklClientSecret = config.simklClientSecret
    private val simklRedirectUri = config.simklRedirectUri
    private val appVersion = config.appVersion.trim().ifEmpty { "dev" }

    private val watchHistoryCache = WatchHistoryCache(appContext)
    private val sessionStore = ProviderSessionStore(appContext, clearProviderCaches = watchHistoryCache::clearProviderCaches)
    private val localStore = LocalWatchHistoryStore(sessionStore.prefs)
    private val watchProgressStore =
        WatchProgressStore(
            prefs = appContext.getSharedPreferences(WATCH_PROGRESS_PREFS_NAME, Context.MODE_PRIVATE),
        )

    private val http = WatchHistoryHttp(httpClient = httpClient, tag = TAG)
    private val simklService =
        SimklService(
            http = http,
            sessionStore = sessionStore,
            simklClientId = simklClientId,
            simklClientSecret = simklClientSecret,
            simklRedirectUri = simklRedirectUri,
            appVersion = appVersion,
        )
    private val traktApi =
        TraktApi(
            http = http,
            prefs = sessionStore.prefs,
            traktClientId = traktClientId,
            traktClientSecret = traktClientSecret,
            traktRedirectUri = traktRedirectUri,
            readSecret = sessionStore::readSecret,
            writeEncrypted = sessionStore::writeEncryptedSecret,
        )
    private val traktScrobbleService = TraktScrobbleService(traktApi = traktApi)

    private val pkce = Pkce()
    private val callbackParser = OAuthCallbackParser()
    private val oauthStateStore = OAuthStateStore(sessionStore.prefs)
    private val traktOAuthClient =
        TraktOAuthClient(
            traktClientId = traktClientId,
            traktClientSecret = traktClientSecret,
            traktRedirectUri = traktRedirectUri,
            http = http,
            sessionStore = sessionStore,
            stateStore = oauthStateStore,
            callbackParser = callbackParser,
            pkce = pkce,
        )
    private val simklOAuthClient =
        SimklOAuthClient(
            simklClientId = simklClientId,
            simklClientSecret = simklClientSecret,
            simklRedirectUri = simklRedirectUri,
            simklService = simklService,
            sessionStore = sessionStore,
            stateStore = oauthStateStore,
            callbackParser = callbackParser,
            pkce = pkce,
        )

    private val traktProvider =
        TraktWatchHistoryProvider(
            traktApi = traktApi,
            sessionStore = sessionStore,
            traktClientId = traktClientId,
            episodeListProvider = episodeListProvider,
        )
    private val simklProvider =
        SimklWatchHistoryProvider(
            simklService = simklService,
            sessionStore = sessionStore,
            simklClientId = simklClientId,
        )
    private val providerRouter = ProviderRouter(traktProvider = traktProvider, simklProvider = simklProvider)
    private val continueWatchingNormalizer = ContinueWatchingNormalizer()
    private val recommendationResolver =
        RecommendationResolver(
            traktProvider = traktProvider,
            simklProvider = simklProvider,
            sessionStore = sessionStore,
            traktClientId = traktClientId,
            simklClientId = simklClientId,
        )

    override fun connectProvider(
        provider: WatchProvider,
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMs: Long?,
        userHandle: String?,
    ) {
        sessionStore.connectProvider(provider, accessToken, refreshToken, expiresAtEpochMs, userHandle)
    }

    override fun disconnectProvider(provider: WatchProvider) {
        sessionStore.disconnectProvider(provider)
    }

    override fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        super.updateAuthTokens(traktAccessToken, simklAccessToken)
    }

    override fun authState(): WatchProviderAuthState {
        return sessionStore.authState()
    }

    override suspend fun beginTraktOAuth(): ProviderAuthStartResult? {
        if (traktClientId.isBlank()) return null
        if (traktRedirectUri.isBlank()) return null
        return traktOAuthClient.begin()
    }

    override suspend fun completeTraktOAuth(callbackUri: String): ProviderAuthActionResult {
        if (traktClientId.isBlank()) {
            return ProviderAuthActionResult(success = false, statusMessage = "Missing TRAKT_CLIENT_ID.")
        }
        if (traktClientSecret.isBlank()) {
            return ProviderAuthActionResult(success = false, statusMessage = "Missing TRAKT_CLIENT_SECRET.")
        }
        return traktOAuthClient.complete(callbackUri)
    }

    override suspend fun beginSimklOAuth(): ProviderAuthStartResult? {
        if (simklClientId.isBlank()) return null
        if (simklRedirectUri.isBlank()) return null
        return simklOAuthClient.begin()
    }

    override suspend fun completeSimklOAuth(callbackUri: String): ProviderAuthActionResult {
        if (simklClientId.isBlank()) {
            return ProviderAuthActionResult(success = false, statusMessage = "Missing SIMKL_CLIENT_ID.")
        }
        if (simklClientSecret.isBlank()) {
            return ProviderAuthActionResult(success = false, statusMessage = "Missing SIMKL_CLIENT_SECRET.")
        }
        return simklOAuthClient.complete(callbackUri)
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
        val normalized = localStore.normalizeRequest(request)
        val updated = localStore.upsertEntry(localStore.loadEntries(), normalized.toLocalWatchedItem())
        localStore.saveEntries(updated)

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> traktProvider.markWatched(normalized)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> traktProvider.markWatched(normalized)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> simklProvider.markWatched(normalized)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> simklProvider.markWatched(normalized)
            }

        val entries = updated.sortedByDescending { it.watchedAtEpochMs }.map { it.toPublicEntry() }
        val statusMessage = "Marked watched."

        return WatchHistoryResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest, source: WatchProvider?): WatchHistoryResult {
        val normalized = localStore.normalizeRequest(request)
        val updated = localStore.removeEntry(localStore.loadEntries(), normalized)
        localStore.saveEntries(updated)

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> traktProvider.unmarkWatched(normalized)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> traktProvider.unmarkWatched(normalized)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> simklProvider.unmarkWatched(normalized)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> simklProvider.unmarkWatched(normalized)
            }

        val entries = updated.sortedByDescending { it.watchedAtEpochMs }.map { it.toPublicEntry() }
        val statusMessage = "Removed from watched."

        return WatchHistoryResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
        source: WatchProvider?,
    ): WatchHistoryResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryResult(statusMessage = "Watchlist is unavailable for local watch history.")
        }

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> traktProvider.setInWatchlist(request, inWatchlist)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> traktProvider.setInWatchlist(request, inWatchlist)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> simklProvider.setInWatchlist(request, inWatchlist)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> simklProvider.setInWatchlist(request, inWatchlist)
            }
        if (syncedTrakt) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.SIMKL)

        val statusMessage = if (inWatchlist) "Saved to watchlist." else "Removed from watchlist."

        return WatchHistoryResult(
            statusMessage = statusMessage,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun setRating(request: WatchHistoryRequest, rating: Int?, source: WatchProvider?): WatchHistoryResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryResult(statusMessage = "Rating is unavailable for local watch history.")
        }

        val ratingValue = rating?.coerceIn(1, 10)
        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> traktProvider.setRating(request, ratingValue)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> traktProvider.setRating(request, ratingValue)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> simklProvider.setRating(request, ratingValue)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> simklProvider.setRating(request, ratingValue)
            }
        if (syncedTrakt) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) watchHistoryCache.invalidateProviderLibraryCache(WatchProvider.SIMKL)

        val verb = if (ratingValue == null) "Removed rating" else "Rated $ratingValue/10"
        val statusMessage = "$verb."

        return WatchHistoryResult(
            statusMessage = statusMessage,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun removeFromPlayback(playbackId: String, source: WatchProvider?): WatchHistoryResult {
        val id = playbackId.trim()
        val entries = localStore.loadEntries().sortedByDescending { it.watchedAtEpochMs }.map { it.toPublicEntry() }

        if (id.isEmpty()) {
            return WatchHistoryResult(
                statusMessage = "Playback id missing.",
                entries = entries,
                authState = authState(),
            )
        }

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> traktProvider.removeFromPlayback(id)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> traktProvider.removeFromPlayback(id)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> simklProvider.removeFromPlayback(id)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> simklProvider.removeFromPlayback(id)
            }

        val statusMessage = "Removed from continue watching."

        return WatchHistoryResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl,
        )
    }

    override suspend fun listContinueWatching(limit: Int, nowMs: Long, source: WatchProvider?): ContinueWatchingResult {
        val targetLimit = limit.coerceAtLeast(1)

        if (source != null) {
            if (source == WatchProvider.LOCAL) {
                val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                val normalized = continueWatchingNormalizer.normalize(entries = local, nowMs = nowMs, limit = targetLimit)
                val status = if (normalized.isNotEmpty()) "" else "No local continue watching entries yet."
                return ContinueWatchingResult(statusMessage = status, entries = normalized)
            }

            val provider = providerRouter.providerFor(source) ?: return ContinueWatchingResult(statusMessage = "No continue watching entries yet.")
            val entries =
                if (provider.hasAccessToken() && provider.hasClientId()) {
                    try {
                        provider.listContinueWatching(nowMs)
                    } catch (_: Throwable) {
                        val cached = getCachedContinueWatching(limit = targetLimit, nowMs = nowMs, source = source)
                        val prefix = if (source == WatchProvider.TRAKT) "Trakt" else "Simkl"
                        return cached.copy(statusMessage = "$prefix temporarily unavailable. ${cached.statusMessage}")
                    }
                } else {
                    emptyList()
                }

            val normalized = continueWatchingNormalizer.normalize(entries = entries, nowMs = nowMs, limit = targetLimit)

            if (normalized.isEmpty()) {
                val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
                val normalizedLocal = continueWatchingNormalizer.normalize(entries = local, nowMs = nowMs, limit = targetLimit)
                if (normalizedLocal.isNotEmpty()) {
                    return ContinueWatchingResult(statusMessage = "", entries = normalizedLocal)
                }
            }
            val status =
                when (source) {
                    WatchProvider.TRAKT -> {
                        if (traktClientId.isBlank()) {
                            "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties."
                        } else if (sessionStore.traktAccessToken().isBlank()) {
                            "Connect Trakt to load continue watching."
                        } else if (normalized.isNotEmpty()) {
                            ""
                        } else {
                            "No Trakt continue watching entries available."
                        }
                    }

                    WatchProvider.SIMKL -> {
                        if (simklClientId.isBlank()) {
                            "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties."
                        } else if (sessionStore.simklAccessToken().isBlank()) {
                            "Connect Simkl to load continue watching."
                        } else if (normalized.isNotEmpty()) {
                            ""
                        } else {
                            "No Simkl continue watching entries available."
                        }
                    }

                    WatchProvider.LOCAL -> "Local source selected."
                }

            val result = ContinueWatchingResult(statusMessage = status, entries = normalized)
            watchHistoryCache.writeContinueWatchingCache(source, result)
            return result
        }

        val traktEntries = runCatching { traktProvider.listContinueWatching(nowMs) }.getOrElse { emptyList() }
        val simklEntries = if (traktEntries.isEmpty()) runCatching { simklProvider.listContinueWatching(nowMs) }.getOrElse { emptyList() } else emptyList()

        if (traktEntries.isEmpty() && simklEntries.isEmpty()) {
            val cached = getCachedContinueWatching(limit = targetLimit, nowMs = nowMs, source = null)
            if (cached.entries.isNotEmpty()) return cached

            val local = listContinueWatchingFromLocalProgress(nowMs = nowMs, limit = targetLimit)
            val normalized = continueWatchingNormalizer.normalize(entries = local, nowMs = nowMs, limit = targetLimit)
            val status = if (normalized.isNotEmpty()) "" else "No continue watching entries yet."
            return ContinueWatchingResult(statusMessage = status, entries = normalized)
        }

        val merged = continueWatchingNormalizer.normalize(entries = traktEntries + simklEntries, nowMs = nowMs, limit = targetLimit)
        val status =
            when {
                merged.isNotEmpty() -> ""
                sessionStore.traktAccessToken().isNotEmpty() -> "No Trakt continue watching entries available."
                sessionStore.simklAccessToken().isNotEmpty() -> "No Simkl continue watching entries available."
                else -> "No continue watching entries yet."
            }
        return ContinueWatchingResult(statusMessage = status, entries = merged)
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int, source: WatchProvider?): ProviderLibrarySnapshot {
        if (source != null) {
            if (source == WatchProvider.LOCAL) {
                return ProviderLibrarySnapshot(statusMessage = "Local source selected. Provider library unavailable.")
            }

            val provider = providerRouter.providerFor(source)
            val selected =
                if (provider != null && provider.hasAccessToken() && provider.hasClientId()) {
                    try {
                        provider.listProviderLibrary(limitPerFolder)
                    } catch (_: Throwable) {
                        val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
                        val prefix = if (source == WatchProvider.TRAKT) "Trakt" else "Simkl"
                        return cached.copy(statusMessage = "$prefix temporarily unavailable. ${cached.statusMessage}")
                    }
                } else {
                    emptyList<ProviderLibraryFolder>() to emptyList()
                }

            val status =
                when (source) {
                    WatchProvider.LOCAL -> "Local source selected. Provider library unavailable."
                    WatchProvider.TRAKT -> {
                        if (traktClientId.isBlank()) {
                            "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties."
                        } else if (sessionStore.traktAccessToken().isBlank()) {
                            "Connect Trakt to load provider library."
                        } else if (selected.first.isNotEmpty()) {
                            ""
                        } else {
                            "No Trakt library data available."
                        }
                    }

                    WatchProvider.SIMKL -> {
                        if (simklClientId.isBlank()) {
                            "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties."
                        } else if (sessionStore.simklAccessToken().isBlank()) {
                            "Connect Simkl to load provider library."
                        } else if (selected.first.isNotEmpty()) {
                            ""
                        } else {
                            "No Simkl library data available."
                        }
                    }
                }

            val snapshot =
                ProviderLibrarySnapshot(
                    statusMessage = status,
                    folders = selected.first.sortedBy { it.label.lowercase(Locale.US) },
                    items = selected.second.sortedByDescending { it.addedAtEpochMs },
                )
            watchHistoryCache.writeProviderLibraryCache(source, snapshot)
            return snapshot
        }

        val folders = mutableListOf<ProviderLibraryFolder>()
        val items = mutableListOf<ProviderLibraryItem>()

        if (traktProvider.hasAccessToken() && traktProvider.hasClientId()) {
            runCatching { traktProvider.listProviderLibrary(limitPerFolder) }
                .onSuccess {
                    folders += it.first
                    items += it.second
                }
        }
        if (simklProvider.hasAccessToken() && simklProvider.hasClientId()) {
            runCatching { simklProvider.listProviderLibrary(limitPerFolder) }
                .onSuccess {
                    folders += it.first
                    items += it.second
                }
        }

        if (folders.isEmpty()) {
            val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = null)
            if (cached.folders.isNotEmpty() || cached.items.isNotEmpty()) {
                return cached
            }
        }

        val status =
            when {
                folders.isNotEmpty() -> ""
                authState().traktAuthenticated || authState().simklAuthenticated -> "No provider library data available."
                else -> "Connect Trakt or Simkl to load provider library."
            }

        return ProviderLibrarySnapshot(
            statusMessage = status,
            folders = folders.sortedBy { it.label.lowercase(Locale.US) },
            items = items.sortedByDescending { it.addedAtEpochMs },
        )
    }

    override suspend fun listProviderRecommendations(limit: Int, source: WatchProvider?): ProviderRecommendationsResult {
        return recommendationResolver.listProviderRecommendations(limit = limit, source = source)
    }

    override suspend fun getCachedContinueWatching(limit: Int, nowMs: Long, source: WatchProvider?): ContinueWatchingResult {
        return watchHistoryCache.getCachedContinueWatching(
            limit = limit,
            nowMs = nowMs,
            source = source,
            localFallback = { localStore.localContinueWatchingFallback() },
            normalize = { entries, normalizedNowMs, targetLimit ->
                continueWatchingNormalizer.normalize(entries = entries, nowMs = normalizedNowMs, limit = targetLimit)
            },
        )
    }

    override suspend fun getCachedProviderLibrary(limitPerFolder: Int, source: WatchProvider?): ProviderLibrarySnapshot {
        return watchHistoryCache.getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = source)
    }

    override suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        return traktProvider.fetchComments(query)
    }

    override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
        val (type, id, episodeId) = progressKeyParts(identity) ?: return null
        val progress = watchProgressStore.getWatchProgress(id = id, type = type, episodeId = episodeId) ?: return null
        return WatchProgressSnapshot(
            currentTimeSeconds = progress.currentTimeSeconds,
            durationSeconds = progress.durationSeconds,
            lastUpdatedEpochMs = progress.lastUpdatedEpochMs,
        )
    }

    override suspend fun removeLocalWatchProgress(identity: PlaybackIdentity): WatchHistoryResult {
        val (type, id, episodeId) = progressKeyParts(identity) ?: return WatchHistoryResult(statusMessage = "Missing playback identity.")

        watchProgressStore.removeAllWatchProgressForContent(id = id, type = type, addBaseTombstone = true)
        watchProgressStore.addContinueWatchingRemoved(id = id, type = type)

        return WatchHistoryResult(statusMessage = "Removed local playback progress.")
    }

    override suspend fun onPlaybackStarted(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        onPlaybackProgress(identity = identity, positionMs = positionMs, durationMs = durationMs, isPlaying = true)
        scrobbleStart(identity = identity, positionMs = positionMs, durationMs = durationMs)
    }

    override suspend fun onPlaybackProgress(identity: PlaybackIdentity, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val (type, id, episodeId) = progressKeyParts(identity) ?: return

        val durationSeconds = (durationMs.coerceAtLeast(0L)).toDouble() / 1000.0
        val currentSeconds = (positionMs.coerceAtLeast(0L)).toDouble() / 1000.0
        if (durationSeconds <= 0.0) return

        watchProgressStore.setContentDuration(id = id, type = type, durationSeconds = durationSeconds, episodeId = episodeId)

        val existing = watchProgressStore.getWatchProgress(id = id, type = type, episodeId = episodeId)
        val next =
            (existing ?: WatchProgress(currentTimeSeconds = 0.0, durationSeconds = durationSeconds, lastUpdatedEpochMs = 0L))
                .copy(
                    currentTimeSeconds = currentSeconds.coerceIn(0.0, durationSeconds),
                    durationSeconds = durationSeconds,
                )

        watchProgressStore.setWatchProgress(id = id, type = type, progress = next, episodeId = episodeId)

        if (isPlaying) {
            scrobblePause(identity = identity, progressPercent = next.progressPercentOrZero())
        }
    }

    override suspend fun onPlaybackStopped(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        onPlaybackProgress(identity = identity, positionMs = positionMs, durationMs = durationMs, isPlaying = false)
        scrobbleStop(identity = identity, positionMs = positionMs, durationMs = durationMs)
    }

    override suspend fun fetchAndMergeTraktProgress(): WatchProgressSyncResult {
        if (!authState().traktAuthenticated || traktClientId.isBlank()) {
            return WatchProgressSyncResult(statusMessage = "Trakt not connected.")
        }

        var updated = 0

        val playback = traktApi.getArray("/sync/playback") ?: JSONArray()
        for (i in 0 until playback.length()) {
            val item = playback.optJSONObject(i) ?: continue
            val progress = item.optDouble("progress", -1.0)
            if (progress < 0.0) continue

            val pausedAtEpochMs = parseIsoToEpochMs(item.optString("paused_at")) ?: continue
            val type = item.optString("type").trim().lowercase(Locale.US)

            when (type) {
                "movie" -> {
                    val imdb = normalizedImdbIdOrNull(item.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")) ?: continue
                    val duration = watchProgressStore.getContentDurationSeconds(id = imdb, type = "movie")
                    val exactTime = duration?.let { it * (progress / 100.0) }
                    watchProgressStore.mergeWithTraktProgress(
                        id = imdb,
                        type = "movie",
                        traktProgressPercent = progress,
                        traktPausedAtEpochMs = pausedAtEpochMs,
                        episodeId = null,
                        exactTimeSeconds = exactTime,
                    )
                    updated++
                }

                "episode" -> {
                    val showImdb = normalizedImdbIdOrNull(item.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")) ?: continue
                    val episodeObj = item.optJSONObject("episode") ?: continue
                    val season = episodeObj.optInt("season", 0)
                    val episode = episodeObj.optInt("number", 0)
                    if (season <= 0 || episode <= 0) continue
                    val episodeId = "$showImdb:$season:$episode"

                    val duration = watchProgressStore.getContentDurationSeconds(id = showImdb, type = "series", episodeId = episodeId)
                    val exactTime = duration?.let { it * (progress / 100.0) }
                    watchProgressStore.mergeWithTraktProgress(
                        id = showImdb,
                        type = "series",
                        traktProgressPercent = progress,
                        traktPausedAtEpochMs = pausedAtEpochMs,
                        episodeId = episodeId,
                        exactTimeSeconds = exactTime,
                    )
                    updated++
                }
            }
        }

        val watchedMovies = traktApi.getArray("/sync/watched/movies") ?: JSONArray()
        for (i in 0 until watchedMovies.length()) {
            val item = watchedMovies.optJSONObject(i) ?: continue
            val movie = item.optJSONObject("movie") ?: continue
            val imdb = normalizedImdbIdOrNull(movie.optJSONObject("ids")?.optString("imdb")) ?: continue
            val watchedAtEpochMs = parseIsoToEpochMs(item.optString("last_watched_at")) ?: continue
            watchProgressStore.mergeWithTraktProgress(
                id = imdb,
                type = "movie",
                traktProgressPercent = 100.0,
                traktPausedAtEpochMs = watchedAtEpochMs,
            )
            updated++
        }

        val watchedShows = traktApi.getArray("/sync/watched/shows") ?: JSONArray()
        for (i in 0 until watchedShows.length()) {
            val item = watchedShows.optJSONObject(i) ?: continue
            val show = item.optJSONObject("show") ?: continue
            val showImdb = normalizedImdbIdOrNull(show.optJSONObject("ids")?.optString("imdb")) ?: continue
            val seasons = item.optJSONArray("seasons") ?: continue

            for (s in 0 until seasons.length()) {
                val seasonObj = seasons.optJSONObject(s) ?: continue
                val seasonNumber = seasonObj.optInt("number", 0)
                if (seasonNumber <= 0) continue
                val episodes = seasonObj.optJSONArray("episodes") ?: continue
                for (e in 0 until episodes.length()) {
                    val epObj = episodes.optJSONObject(e) ?: continue
                    val epNumber = epObj.optInt("number", 0)
                    if (epNumber <= 0) continue
                    val watchedAtEpochMs = parseIsoToEpochMs(epObj.optString("last_watched_at")) ?: continue
                    val episodeId = "$showImdb:$seasonNumber:$epNumber"
                    watchProgressStore.mergeWithTraktProgress(
                        id = showImdb,
                        type = "series",
                        traktProgressPercent = 100.0,
                        traktPausedAtEpochMs = watchedAtEpochMs,
                        episodeId = episodeId,
                    )
                    updated++
                }
            }
        }

        return WatchProgressSyncResult(statusMessage = "Merged Trakt progress.", updatedCount = updated)
    }

    override suspend fun fetchAndMergeSimklProgress(): WatchProgressSyncResult {
        if (!authState().simklAuthenticated || simklClientId.isBlank()) {
            return WatchProgressSyncResult(statusMessage = "Simkl not connected.")
        }

        var updated = 0
        val playback = simklService.getPlaybackStatus(forceRefresh = false)
        for (i in 0 until playback.length()) {
            val item = playback.optJSONObject(i) ?: continue
            val progress = item.optDouble("progress", -1.0)
            if (progress < 0.0) continue

            val pausedAtEpochMs = parseIsoToEpochMs(item.optString("paused_at")) ?: continue
            when (item.optString("type").trim().lowercase(Locale.US)) {
                "movie" -> {
                    val imdb = normalizedImdbIdOrNull(item.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")) ?: continue
                    watchProgressStore.mergeWithSimklProgress(
                        id = imdb,
                        type = "movie",
                        simklProgressPercent = progress,
                        simklPausedAtEpochMs = pausedAtEpochMs,
                    )
                    updated++
                }

                "episode" -> {
                    val showImdb = normalizedImdbIdOrNull(item.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")) ?: continue
                    val episodeObj = item.optJSONObject("episode") ?: continue
                    val season = episodeObj.optInt("season", 0)
                    val episode = episodeObj.optInt("episode", 0).takeIf { it > 0 }
                        ?: episodeObj.optInt("number", 0)
                    if (season <= 0 || episode <= 0) continue
                    val episodeId = "$showImdb:$season:$episode"

                    watchProgressStore.mergeWithSimklProgress(
                        id = showImdb,
                        type = "series",
                        simklProgressPercent = progress,
                        simklPausedAtEpochMs = pausedAtEpochMs,
                        episodeId = episodeId,
                    )
                    updated++
                }
            }
        }

        return WatchProgressSyncResult(statusMessage = "Merged Simkl progress.", updatedCount = updated)
    }

    override suspend fun syncAllTraktProgress(): WatchProgressSyncResult {
        if (!authState().traktAuthenticated || traktClientId.isBlank()) {
            return WatchProgressSyncResult(statusMessage = "Trakt not connected.")
        }

        val items = watchProgressStore.getUnsyncedProgress().filter { it.progress.needsTraktSync() }
        if (items.isEmpty()) {
            return WatchProgressSyncResult(statusMessage = "No Trakt progress to sync.")
        }

        var syncedCount = 0
        val batchSize = 5

        for (batchStart in items.indices step batchSize) {
            val batch = items.subList(batchStart, minOf(items.size, batchStart + batchSize))
            for (item in batch) {
                if (!item.id.startsWith("tt")) continue

                val percent = item.progress.progressPercentOrZero()
                val watchedAtEpochMs = item.progress.lastUpdatedEpochMs

                val ok =
                    if (percent >= traktScrobbleService.completionThresholdPercent) {
                        traktAddToHistory(item, watchedAtEpochMs)
                    } else {
                        val content = item.toTraktContentDataOrNull() ?: continue
                        traktScrobbleService.scrobblePause(contentData = content, progressPercent = percent, force = true)
                    }

                if (ok) {
                    val storedPercent = if (percent >= traktScrobbleService.completionThresholdPercent) 100.0 else percent
                    watchProgressStore.updateTraktSyncStatus(
                        id = item.id,
                        type = item.type,
                        traktSynced = true,
                        traktProgressPercent = storedPercent,
                        episodeId = item.episodeId,
                    )
                    syncedCount++
                }
            }

            if (batchStart + batchSize < items.size) {
                delay(2_000)
            }
        }

        return WatchProgressSyncResult(statusMessage = "Synced Trakt progress.", updatedCount = syncedCount)
    }

    override suspend fun syncAllSimklProgress(): WatchProgressSyncResult {
        if (!authState().simklAuthenticated || simklClientId.isBlank()) {
            return WatchProgressSyncResult(statusMessage = "Simkl not connected.")
        }

        val items = watchProgressStore.getUnsyncedProgress().filter { it.progress.needsSimklSync() }
        if (items.isEmpty()) {
            return WatchProgressSyncResult(statusMessage = "No Simkl progress to sync.")
        }

        var syncedCount = 0
        for (item in items) {
            if (!item.id.startsWith("tt")) continue

            val percent = item.progress.progressPercentOrZero()
            val ok =
                if (percent >= SIMKL_MARK_WATCHED_THRESHOLD_PERCENT) {
                    simklAddToHistory(item)
                } else {
                    val content = item.toSimklContentDataOrNull() ?: continue
                    simklService.scrobblePause(content = content, progressPercent = percent, force = true)
                }

            if (ok) {
                val storedPercent = if (percent >= SIMKL_MARK_WATCHED_THRESHOLD_PERCENT) 100.0 else percent
                watchProgressStore.updateSimklSyncStatus(
                    id = item.id,
                    type = item.type,
                    simklSynced = true,
                    simklProgressPercent = storedPercent,
                    episodeId = item.episodeId,
                )
                syncedCount++
            }
        }

        return WatchProgressSyncResult(statusMessage = "Synced Simkl progress.", updatedCount = syncedCount)
    }

    private suspend fun scrobbleStart(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        val progressPercent = toProgressPercent(positionMs = positionMs, durationMs = durationMs)
        if (progressPercent == null) return

        val imdb = normalizedImdbIdOrNull(identity.imdbId)
        if (authState().traktAuthenticated && imdb != null) {
            val content = identity.toTraktContentData(imdb)
            if (content != null) {
                traktScrobbleService.scrobbleStart(contentData = content, progressPercent = progressPercent)
            }
        }

        if (authState().simklAuthenticated) {
            val content = identity.toSimklContentData()
            if (content != null) {
                simklService.scrobbleStart(content = content, progressPercent = progressPercent)
            }
        }
    }

    private suspend fun scrobblePause(identity: PlaybackIdentity, progressPercent: Double) {
        val imdb = normalizedImdbIdOrNull(identity.imdbId)
        if (authState().traktAuthenticated && imdb != null) {
            val content = identity.toTraktContentData(imdb)
            if (content != null) {
                traktScrobbleService.scrobblePause(contentData = content, progressPercent = progressPercent)
            }
        }

        if (authState().simklAuthenticated) {
            val content = identity.toSimklContentData()
            if (content != null) {
                simklService.scrobblePause(content = content, progressPercent = progressPercent)
            }
        }
    }

    private suspend fun scrobbleStop(identity: PlaybackIdentity, positionMs: Long, durationMs: Long) {
        val progressPercent = toProgressPercent(positionMs = positionMs, durationMs = durationMs)
        if (progressPercent == null) return

        val imdb = normalizedImdbIdOrNull(identity.imdbId)
        if (authState().traktAuthenticated && imdb != null) {
            val content = identity.toTraktContentData(imdb)
            if (content != null) {
                traktScrobbleService.scrobbleStop(contentData = content, progressPercent = progressPercent)
            }
        }

        if (authState().simklAuthenticated) {
            val content = identity.toSimklContentData()
            if (content != null) {
                simklService.scrobbleStop(content = content, progressPercent = progressPercent)
            }
        }
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
                    movies +=
                        ContinueWatchingEntry(
                            contentId = id,
                            contentType = MetadataLabMediaType.MOVIE,
                            title = id,
                            season = null,
                            episode = null,
                            progressPercent = percent,
                            lastUpdatedEpochMs = lastUpdated,
                            provider = WatchProvider.LOCAL,
                            providerPlaybackId = null,
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
                        if (existingMax == null || nextMax.first > existingMax.first || (nextMax.first == existingMax.first && nextMax.second > existingMax.second)) {
                            maxWatchedEpisodeByShow[id] = nextMax
                        }
                        latestWatchedAtByShow[id] = maxOf(latestWatchedAtByShow[id] ?: 0L, lastUpdated)
                    } else {
                        val candidate =
                            SeriesCandidate(
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

        val seriesEntries =
            latestInProgressEpisodeByShow.values.map {
                ContinueWatchingEntry(
                    contentId = it.showId,
                    contentType = MetadataLabMediaType.SERIES,
                    title = it.showId,
                    season = it.season,
                    episode = it.episode,
                    progressPercent = it.progressPercent,
                    lastUpdatedEpochMs = it.lastUpdatedEpochMs,
                    provider = WatchProvider.LOCAL,
                    providerPlaybackId = null,
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

            val episodeList =
                runCatching {
                    episodeListProvider.fetchEpisodeList(mediaType = "series", contentId = showId)
                }.getOrNull()
                    ?: continue
            val watchedSet = watchedSetByShow[showId]

            val next =
                findNextEpisode(
                    currentSeason = maxEpisode.first,
                    currentEpisode = maxEpisode.second,
                    episodes = episodeList,
                    watchedSet = watchedSet,
                    showId = showId,
                ) ?: continue

            placeholders +=
                ContinueWatchingEntry(
                    contentId = showId,
                    contentType = MetadataLabMediaType.SERIES,
                    title = showId,
                    season = next.season,
                    episode = next.episode,
                    progressPercent = 0.0,
                    lastUpdatedEpochMs = lastWatchedAt,
                    provider = WatchProvider.LOCAL,
                    providerPlaybackId = null,
                    isUpNextPlaceholder = true,
                )
        }

        return (movies + seriesEntries + placeholders)
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun progressKeyParts(identity: PlaybackIdentity): ProgressKeyParts? {
        val type =
            when (identity.contentType) {
                com.crispy.tv.player.MetadataLabMediaType.MOVIE -> "movie"
                com.crispy.tv.player.MetadataLabMediaType.SERIES -> "series"
            }

        val id = normalizedImdbIdOrNull(identity.imdbId) ?: return null

        val episodeId =
            if (identity.contentType == com.crispy.tv.player.MetadataLabMediaType.SERIES && identity.season != null && identity.episode != null) {
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

    private fun UnsyncedProgressItem.toTraktContentDataOrNull(): TraktScrobbleService.TraktContentData? {
        return when (type) {
            "movie" -> TraktScrobbleService.TraktContentData.Movie(title = id, year = null, imdbId = id)
            else -> {
                val seasonEpisode = episodeSeasonEpisode() ?: return null
                TraktScrobbleService.TraktContentData.Episode(
                    title = id,
                    showTitle = id,
                    showYear = null,
                    showImdbId = id,
                    season = seasonEpisode.first,
                    episode = seasonEpisode.second,
                )
            }
        }
    }

    private fun PlaybackIdentity.toTraktContentData(imdbId: String): TraktScrobbleService.TraktContentData? {
        return when (contentType) {
            com.crispy.tv.player.MetadataLabMediaType.MOVIE -> {
                TraktScrobbleService.TraktContentData.Movie(title = title, year = year, imdbId = imdbId)
            }

            com.crispy.tv.player.MetadataLabMediaType.SERIES -> {
                val seasonValue = season ?: return null
                val episodeValue = episode ?: return null
                TraktScrobbleService.TraktContentData.Episode(
                    title = title,
                    showTitle = showTitle ?: title,
                    showYear = showYear,
                    showImdbId = imdbId,
                    season = seasonValue,
                    episode = episodeValue,
                )
            }
        }
    }

    private fun UnsyncedProgressItem.toSimklContentDataOrNull(): SimklService.SimklScrobbleContent? {
        val ids = SimklService.SimklIds(imdbId = id)
        return when (type) {
            "movie" -> SimklService.SimklScrobbleContent.Movie(title = id, year = null, ids = ids)
            else -> {
                val seasonEpisode = episodeSeasonEpisode() ?: return null
                SimklService.SimklScrobbleContent.Episode(
                    showTitle = id,
                    showYear = null,
                    season = seasonEpisode.first,
                    episode = seasonEpisode.second,
                    title = id,
                    year = null,
                    ids = ids,
                )
            }
        }
    }

    private fun PlaybackIdentity.toSimklContentData(): SimklService.SimklScrobbleContent? {
        val ids =
            SimklService.SimklIds(
                imdbId = normalizedImdbIdOrNull(imdbId),
                tmdbId = tmdbId?.toLong(),
            )

        return when (contentType) {
            com.crispy.tv.player.MetadataLabMediaType.MOVIE -> SimklService.SimklScrobbleContent.Movie(title = title, year = year, ids = ids)
            com.crispy.tv.player.MetadataLabMediaType.SERIES -> {
                val seasonValue = season ?: return null
                val episodeValue = episode ?: return null
                SimklService.SimklScrobbleContent.Episode(
                    showTitle = showTitle ?: title,
                    showYear = showYear,
                    season = seasonValue,
                    episode = episodeValue,
                    title = title,
                    year = year,
                    ids = ids,
                )
            }
        }
    }

    private suspend fun traktAddToHistory(item: UnsyncedProgressItem, watchedAtEpochMs: Long): Boolean {
        val imdb = normalizedImdbIdOrNull(item.id) ?: return false
        val watchedAt = Instant.ofEpochMilli(watchedAtEpochMs).toString()

        val payload =
            when (item.type) {
                "movie" -> {
                    JSONObject().put(
                        "movies",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdb))
                                .put("watched_at", watchedAt),
                        ),
                    )
                }

                else -> {
                    val seasonEpisode = item.episodeSeasonEpisode() ?: return false
                    val season = seasonEpisode.first
                    val episode = seasonEpisode.second

                    val episodeObj =
                        JSONObject()
                            .put("number", episode)
                            .put("watched_at", watchedAt)

                    val seasonObj =
                        JSONObject()
                            .put("number", season)
                            .put("episodes", JSONArray().put(episodeObj))

                    val showObj =
                        JSONObject()
                            .put("ids", JSONObject().put("imdb", imdb))
                            .put("seasons", JSONArray().put(seasonObj))

                    JSONObject().put("shows", JSONArray().put(showObj))
                }
            }

        return traktApi.post("/sync/history", payload)
    }

    private suspend fun simklAddToHistory(item: UnsyncedProgressItem): Boolean {
        val imdb = normalizedImdbIdOrNull(item.id) ?: return false

        val payload =
            when (item.type) {
                "movie" -> {
                    JSONObject().put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", JSONObject().put("imdb", imdb)),
                        ),
                    )
                }

                else -> {
                    val seasonEpisode = item.episodeSeasonEpisode() ?: return false
                    val season = seasonEpisode.first
                    val episode = seasonEpisode.second

                    val episodeObj = JSONObject().put("number", episode)
                    val seasonObj =
                        JSONObject()
                            .put("number", season)
                            .put("episodes", JSONArray().put(episodeObj))

                    val showObj =
                        JSONObject()
                            .put("ids", JSONObject().put("imdb", imdb))
                            .put("seasons", JSONArray().put(seasonObj))

                    JSONObject().put("shows", JSONArray().put(showObj))
                }
            }

        return simklService.addToHistory(payload)
    }

    private fun UnsyncedProgressItem.episodeSeasonEpisode(): Pair<Int, Int>? {
        val raw = episodeId?.trim().orEmpty()
        if (raw.isBlank()) return null

        val parts = raw.split(':')
        if (parts.size >= 2) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null && season > 0 && episode > 0) {
                return season to episode
            }
        }

        val match = Regex("s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE).find(raw)
        if (match != null) {
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (season != null && episode != null && season > 0 && episode > 0) {
                return season to episode
            }
        }

        return null
    }

    private fun WatchProgress.needsTraktSync(): Boolean {
        return !traktSynced || (traktLastSyncedEpochMs != null && lastUpdatedEpochMs > traktLastSyncedEpochMs)
    }

    private fun WatchProgress.needsSimklSync(): Boolean {
        return !simklSynced || (simklLastSyncedEpochMs != null && lastUpdatedEpochMs > simklLastSyncedEpochMs)
    }

    private fun normalizedImdbIdOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isBlank()) return null

        val candidate =
            when {
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

    private fun parseIsoToEpochMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun toProgressPercent(positionMs: Long, durationMs: Long): Double? {
        if (durationMs <= 0L) return null
        val percent = (positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble()) * 100.0
        return percent.coerceIn(0.0, 100.0)
    }

    private companion object {
        private const val TAG = "RemoteWatchHistory"

        private const val WATCH_PROGRESS_PREFS_NAME = "watch_progress"
        private const val SIMKL_MARK_WATCHED_THRESHOLD_PERCENT = 85.0
    }
}
