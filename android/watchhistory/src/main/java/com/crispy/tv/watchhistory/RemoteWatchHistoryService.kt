package com.crispy.tv.watchhistory

import android.content.Context
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.ProviderAuthActionResult
import com.crispy.tv.player.ProviderAuthStartResult
import com.crispy.tv.player.ProviderCommentQuery
import com.crispy.tv.player.ProviderCommentResult
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
import com.crispy.tv.player.ProviderRecommendationsResult
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryService
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
import com.crispy.tv.watchhistory.provider.syncStatusLabel
import com.crispy.tv.watchhistory.simkl.SimklApi
import com.crispy.tv.watchhistory.simkl.SimklOAuthClient
import com.crispy.tv.watchhistory.simkl.SimklWatchHistoryProvider
import com.crispy.tv.watchhistory.trakt.TraktApi
import com.crispy.tv.watchhistory.trakt.TraktOAuthClient
import com.crispy.tv.watchhistory.trakt.TraktWatchHistoryProvider
import java.util.Locale

class RemoteWatchHistoryService(
    context: Context,
    httpClient: CrispyHttpClient,
    private val traktClientId: String,
    private val simklClientId: String,
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

    private val http = WatchHistoryHttp(httpClient = httpClient, tag = TAG)
    private val simklApi =
        SimklApi(
            http = http,
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
            simklApi = simklApi,
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
        )
    private val simklProvider =
        SimklWatchHistoryProvider(
            simklApi = simklApi,
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
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT -> "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)}"
                WatchProvider.SIMKL -> "Marked watched locally. simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
                WatchProvider.LOCAL -> "Marked watched locally."
                null -> "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)} simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
            }

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
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT -> "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)}"
                WatchProvider.SIMKL -> "Removed watched entry locally. simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
                WatchProvider.LOCAL -> "Removed watched entry locally."
                null -> "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)} simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
            }

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

        val verb = if (inWatchlist) "Saved" else "Removed"
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT -> "$verb. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)}"
                WatchProvider.SIMKL -> "$verb. simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
                WatchProvider.LOCAL -> "$verb."
                null -> "$verb. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)} simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
            }

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
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT -> "$verb. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)}"
                WatchProvider.SIMKL -> "$verb. simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
                WatchProvider.LOCAL -> "$verb."
                null -> "$verb. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)} simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
            }

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

        val statusMessage =
            when (source) {
                WatchProvider.TRAKT -> "Removed from continue watching. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)}"
                WatchProvider.SIMKL -> "Removed from continue watching. simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
                WatchProvider.LOCAL -> "Removed from continue watching."
                null -> "Removed from continue watching. trakt=${syncStatusLabel(syncedTrakt, sessionStore.traktAccessToken(), traktClientId)} simkl=${syncStatusLabel(syncedSimkl, sessionStore.simklAccessToken(), simklClientId)}"
            }

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
                val local = localStore.localContinueWatchingFallback().take(targetLimit)
                val status = if (local.isNotEmpty()) "Loaded ${local.size} local continue watching entries." else "No local continue watching entries yet."
                return ContinueWatchingResult(statusMessage = status, entries = local)
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
            val status =
                when (source) {
                    WatchProvider.TRAKT -> {
                        if (traktClientId.isBlank()) {
                            "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties."
                        } else if (sessionStore.traktAccessToken().isBlank()) {
                            "Connect Trakt to load continue watching."
                        } else if (normalized.isNotEmpty()) {
                            "Loaded ${normalized.size} Trakt continue watching entries."
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
                            "Loaded ${normalized.size} Simkl continue watching entries."
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

            val local = localStore.localContinueWatchingFallback().take(targetLimit)
            val status = if (local.isNotEmpty()) "Loaded ${local.size} local continue watching entries." else "No continue watching entries yet."
            return ContinueWatchingResult(statusMessage = status, entries = local)
        }

        val merged = continueWatchingNormalizer.normalize(entries = traktEntries + simklEntries, nowMs = nowMs, limit = targetLimit)
        val status =
            when {
                merged.isNotEmpty() -> "Loaded ${merged.size} continue watching entries."
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
                            "Loaded ${selected.first.size} Trakt folders."
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
                            "Loaded ${selected.first.size} Simkl folders."
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
                folders.isNotEmpty() -> "Loaded ${folders.size} provider folders."
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

    private companion object {
        private const val TAG = "RemoteWatchHistory"
    }
}
