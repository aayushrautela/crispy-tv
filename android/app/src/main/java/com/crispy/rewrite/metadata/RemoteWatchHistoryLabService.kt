package com.crispy.rewrite.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.domain.metadata.normalizeNuvioMediaId
import com.crispy.rewrite.player.ContinueWatchingEntry
import com.crispy.rewrite.player.ContinueWatchingLabResult
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.ProviderAuthActionResult
import com.crispy.rewrite.player.ProviderAuthStartResult
import com.crispy.rewrite.player.ProviderComment
import com.crispy.rewrite.player.ProviderCommentQuery
import com.crispy.rewrite.player.ProviderCommentResult
import com.crispy.rewrite.player.ProviderCommentScope
import com.crispy.rewrite.player.ProviderLibraryFolder
import com.crispy.rewrite.player.ProviderLibraryItem
import com.crispy.rewrite.player.ProviderLibrarySnapshot
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabResult
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchProviderAuthState
import com.crispy.rewrite.player.WatchProviderSession
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.time.Instant
import java.util.Locale

class RemoteWatchHistoryLabService(
    context: Context,
    private val traktClientId: String,
    private val simklClientId: String,
    private val traktClientSecret: String = BuildConfig.TRAKT_CLIENT_SECRET,
    private val traktRedirectUri: String = BuildConfig.TRAKT_REDIRECT_URI,
    private val simklClientSecret: String = BuildConfig.SIMKL_CLIENT_SECRET,
    private val simklRedirectUri: String = BuildConfig.SIMKL_REDIRECT_URI
) : WatchHistoryLabService {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun connectProvider(
        provider: WatchProvider,
        accessToken: String,
        refreshToken: String?,
        expiresAtEpochMs: Long?,
        userHandle: String?
    ) {
        val normalizedAccess = accessToken.trim()
        if (normalizedAccess.isBlank()) {
            disconnectProvider(provider)
            return
        }

        prefs.edit().apply {
            when (provider) {
                WatchProvider.TRAKT -> {
                    putString(KEY_TRAKT_TOKEN, normalizedAccess)
                    putString(KEY_TRAKT_REFRESH_TOKEN, refreshToken?.trim()?.ifBlank { null })
                    if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
                        putLong(KEY_TRAKT_EXPIRES_AT, expiresAtEpochMs)
                    } else {
                        remove(KEY_TRAKT_EXPIRES_AT)
                    }
                    putString(KEY_TRAKT_HANDLE, userHandle?.trim()?.ifBlank { null })
                }

                WatchProvider.SIMKL -> {
                    putString(KEY_SIMKL_TOKEN, normalizedAccess)
                    putString(KEY_SIMKL_HANDLE, userHandle?.trim()?.ifBlank { null })
                }

                WatchProvider.LOCAL -> Unit
            }
        }.apply()
    }

    override fun disconnectProvider(provider: WatchProvider) {
        prefs.edit().apply {
            when (provider) {
                WatchProvider.TRAKT -> {
                    remove(KEY_TRAKT_TOKEN)
                    remove(KEY_TRAKT_REFRESH_TOKEN)
                    remove(KEY_TRAKT_EXPIRES_AT)
                    remove(KEY_TRAKT_HANDLE)
                }

                WatchProvider.SIMKL -> {
                    remove(KEY_SIMKL_TOKEN)
                    remove(KEY_SIMKL_HANDLE)
                }

                WatchProvider.LOCAL -> Unit
            }
        }.apply()
    }

    override fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        super.updateAuthTokens(traktAccessToken, simklAccessToken)
    }

    override fun authState(): WatchProviderAuthState {
        val traktSession = traktSessionOrNull()
        val simklSession = simklSessionOrNull()
        return WatchProviderAuthState(
            traktAuthenticated = traktSession != null,
            simklAuthenticated = simklSession != null,
            traktSession = traktSession,
            simklSession = simklSession
        )
    }

    override suspend fun beginTraktOAuth(): ProviderAuthStartResult? {
        if (traktClientId.isBlank()) {
            Log.w(TAG, "Skipping Trakt OAuth start: missing client id")
            return null
        }
        if (traktRedirectUri.isBlank()) {
            Log.w(TAG, "Skipping Trakt OAuth start: missing redirect uri")
            return null
        }

        val state = generateUrlSafeToken(20)
        val codeVerifier = generateUrlSafeToken(64)
        val codeChallenge = codeChallengeFromVerifier(codeVerifier)

        prefs.edit().apply {
            putString(KEY_TRAKT_OAUTH_STATE, state)
            putString(KEY_TRAKT_OAUTH_CODE_VERIFIER, codeVerifier)
        }.apply()

        val authorizationUrl =
            Uri.parse(TRAKT_AUTHORIZE_BASE).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", traktClientId)
                .appendQueryParameter("redirect_uri", traktRedirectUri)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

        Log.d(TAG, "Prepared Trakt OAuth start (redirectUri=$traktRedirectUri)")

        return ProviderAuthStartResult(
            authorizationUrl = authorizationUrl,
            statusMessage = "Opening Trakt OAuth."
        )
    }

    override suspend fun completeTraktOAuth(callbackUri: String): ProviderAuthActionResult {
        if (traktClientId.isBlank()) {
            Log.w(TAG, "Trakt OAuth completion aborted: missing client id")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing TRAKT_CLIENT_ID.")
        }
        if (traktClientSecret.isBlank()) {
            Log.w(TAG, "Trakt OAuth completion aborted: missing client secret")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing TRAKT_CLIENT_SECRET.")
        }

        val uri = runCatching { Uri.parse(callbackUri) }.getOrNull()
            ?: run {
                Log.w(TAG, "Trakt OAuth completion failed: invalid callback uri")
                return ProviderAuthActionResult(success = false, statusMessage = "Invalid Trakt callback URI.")
            }

        Log.d(TAG, "Completing Trakt OAuth (${uriSummaryForLog(uri)})")

        val expectedState = prefs.getString(KEY_TRAKT_OAUTH_STATE, null)?.trim().orEmpty()
        val expectedVerifier = prefs.getString(KEY_TRAKT_OAUTH_CODE_VERIFIER, null)?.trim().orEmpty()
        val receivedState = uri.getQueryParameter("state")?.trim().orEmpty()
        val authCode = uri.getQueryParameter("code")?.trim().orEmpty()
        val oauthError = uri.getQueryParameter("error")?.trim().orEmpty()

        if (oauthError.isNotEmpty()) {
            clearPendingTraktOAuth()
            Log.w(TAG, "Trakt OAuth rejected by provider: $oauthError")
            return ProviderAuthActionResult(success = false, statusMessage = "Trakt OAuth rejected: $oauthError")
        }
        if (authCode.isBlank()) {
            Log.w(TAG, "Trakt OAuth completion failed: missing authorization code")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing OAuth authorization code.")
        }
        if (expectedState.isBlank() || receivedState != expectedState) {
            clearPendingTraktOAuth()
            Log.w(
                TAG,
                "Trakt OAuth completion failed: state mismatch (expectedPresent=${expectedState.isNotBlank()}, receivedPresent=${receivedState.isNotBlank()})"
            )
            return ProviderAuthActionResult(success = false, statusMessage = "Trakt OAuth state mismatch.")
        }
        if (expectedVerifier.isBlank()) {
            clearPendingTraktOAuth()
            Log.w(TAG, "Trakt OAuth completion failed: missing PKCE verifier")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing PKCE verifier for Trakt OAuth.")
        }

        Log.d(TAG, "Exchanging Trakt OAuth code for token")

        val tokenPayload = JSONObject()
            .put("code", authCode)
            .put("client_id", traktClientId)
            .put("client_secret", traktClientSecret)
            .put("redirect_uri", traktRedirectUri)
            .put("grant_type", "authorization_code")
            .put("code_verifier", expectedVerifier)

        val tokenResponse =
            postJsonForObject(
                url = TRAKT_TOKEN_URL,
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                payload = tokenPayload
            )

        clearPendingTraktOAuth()

        if (tokenResponse == null) {
            Log.w(TAG, "Trakt token exchange returned no JSON payload")
            return ProviderAuthActionResult(success = false, statusMessage = "Trakt token exchange failed.")
        }

        val accessToken = tokenResponse.optString("access_token").trim()
        if (accessToken.isBlank()) {
            Log.w(TAG, "Trakt token response missing access token (keys=${jsonKeysForLog(tokenResponse)})")
            return ProviderAuthActionResult(success = false, statusMessage = "Trakt token response missing access token.")
        }
        val refreshToken = tokenResponse.optString("refresh_token").trim().ifBlank { null }
        val expiresInSeconds = tokenResponse.optLong("expires_in", 0L)
        val expiresAtEpochMs =
            if (expiresInSeconds > 0L) {
                System.currentTimeMillis() + (expiresInSeconds * 1000L)
            } else {
                null
            }

        val userHandle = fetchTraktUserHandle(accessToken)
        Log.d(
            TAG,
            "Trakt token exchange succeeded (refreshToken=${refreshToken != null}, expiresAtEpochMs=${expiresAtEpochMs ?: -1L}, userHandlePresent=${!userHandle.isNullOrBlank()})"
        )
        connectProvider(
            provider = WatchProvider.TRAKT,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMs = expiresAtEpochMs,
            userHandle = userHandle
        )

        return ProviderAuthActionResult(
            success = true,
            statusMessage = "Connected Trakt OAuth.",
            authState = authState()
        )
    }

    override suspend fun beginSimklOAuth(): ProviderAuthStartResult? {
        if (simklClientId.isBlank()) {
            Log.w(TAG, "Skipping Simkl OAuth start: missing client id")
            return null
        }
        if (simklRedirectUri.isBlank()) {
            Log.w(TAG, "Skipping Simkl OAuth start: missing redirect uri")
            return null
        }

        val state = generateUrlSafeToken(20)
        prefs.edit().apply {
            putString(KEY_SIMKL_OAUTH_STATE, state)
        }.apply()

        val authorizationUrl =
            Uri.parse(SIMKL_AUTHORIZE_BASE).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", simklClientId)
                .appendQueryParameter("redirect_uri", simklRedirectUri)
                .appendQueryParameter("state", state)
                .build()
                .toString()

        Log.d(TAG, "Prepared Simkl OAuth start (redirectUri=$simklRedirectUri)")

        return ProviderAuthStartResult(
            authorizationUrl = authorizationUrl,
            statusMessage = "Opening Simkl OAuth."
        )
    }

    override suspend fun completeSimklOAuth(callbackUri: String): ProviderAuthActionResult {
        if (simklClientId.isBlank()) {
            Log.w(TAG, "Simkl OAuth completion aborted: missing client id")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing SIMKL_CLIENT_ID.")
        }
        if (simklClientSecret.isBlank()) {
            Log.w(TAG, "Simkl OAuth completion aborted: missing client secret")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing SIMKL_CLIENT_SECRET.")
        }

        val uri = runCatching { Uri.parse(callbackUri) }.getOrNull()
            ?: run {
                Log.w(TAG, "Simkl OAuth completion failed: invalid callback uri")
                return ProviderAuthActionResult(success = false, statusMessage = "Invalid Simkl callback URI.")
            }

        Log.d(TAG, "Completing Simkl OAuth (${uriSummaryForLog(uri)})")

        val expectedState = prefs.getString(KEY_SIMKL_OAUTH_STATE, null)?.trim().orEmpty()
        val receivedState = uri.getQueryParameter("state")?.trim().orEmpty()
        val authCode = uri.getQueryParameter("code")?.trim().orEmpty()
        val oauthError = uri.getQueryParameter("error")?.trim().orEmpty()

        if (oauthError.isNotEmpty()) {
            clearPendingSimklOAuth()
            Log.w(TAG, "Simkl OAuth rejected by provider: $oauthError")
            return ProviderAuthActionResult(success = false, statusMessage = "Simkl OAuth rejected: $oauthError")
        }
        if (authCode.isBlank()) {
            Log.w(TAG, "Simkl OAuth completion failed: missing authorization code")
            return ProviderAuthActionResult(success = false, statusMessage = "Missing Simkl OAuth authorization code.")
        }
        if (expectedState.isBlank() || receivedState != expectedState) {
            clearPendingSimklOAuth()
            Log.w(
                TAG,
                "Simkl OAuth completion failed: state mismatch (expectedPresent=${expectedState.isNotBlank()}, receivedPresent=${receivedState.isNotBlank()})"
            )
            return ProviderAuthActionResult(success = false, statusMessage = "Simkl OAuth state mismatch.")
        }

        Log.d(TAG, "Exchanging Simkl OAuth code for token")

        val tokenPayload = JSONObject()
            .put("code", authCode)
            .put("client_id", simklClientId)
            .put("client_secret", simklClientSecret)
            .put("redirect_uri", simklRedirectUri)
            .put("grant_type", "authorization_code")

        val tokenResponse =
            postJsonForObject(
                url = SIMKL_TOKEN_URL,
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                payload = tokenPayload
            )

        clearPendingSimklOAuth()

        if (tokenResponse == null) {
            Log.w(TAG, "Simkl token exchange returned no JSON payload")
            return ProviderAuthActionResult(success = false, statusMessage = "Simkl token exchange failed.")
        }

        val accessToken = tokenResponse.optString("access_token").trim()
        if (accessToken.isBlank()) {
            Log.w(TAG, "Simkl token response missing access token (keys=${jsonKeysForLog(tokenResponse)})")
            return ProviderAuthActionResult(success = false, statusMessage = "Simkl token response missing access token.")
        }

        val userHandle = fetchSimklUserHandle(accessToken)
        Log.d(TAG, "Simkl token exchange succeeded (userHandlePresent=${!userHandle.isNullOrBlank()})")
        connectProvider(
            provider = WatchProvider.SIMKL,
            accessToken = accessToken,
            userHandle = userHandle
        )

        return ProviderAuthActionResult(
            success = true,
            statusMessage = "Connected Simkl OAuth.",
            authState = authState()
        )
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryLabResult {
        val entries =
            loadEntries()
                .sortedByDescending { item -> item.watchedAtEpochMs }
                .take(limit.coerceAtLeast(1))
                .map { item -> item.toPublicEntry() }

        val status =
            if (entries.isEmpty()) {
                "No local watched entries yet."
            } else {
                "Loaded ${entries.size} local watched entries."
            }

        return WatchHistoryLabResult(
            statusMessage = status,
            entries = entries,
            authState = authState()
        )
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return loadEntries()
            .sortedByDescending { item -> item.watchedAtEpochMs }
            .map { item -> item.toPublicEntry() }
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult {
        if (entries.isEmpty()) {
            val current = loadEntries().sortedByDescending { item -> item.watchedAtEpochMs }.map { it.toPublicEntry() }
            return WatchHistoryLabResult(
                statusMessage = "Remote watched history empty. Kept local history unchanged.",
                entries = current,
                authState = authState()
            )
        }

        val normalized =
            entries.mapNotNull { entry ->
                val contentId = normalizeNuvioMediaId(entry.contentId).contentId.trim()
                if (contentId.isEmpty()) {
                    return@mapNotNull null
                }

                val season = entry.season?.takeIf { value -> value > 0 }
                val episode = entry.episode?.takeIf { value -> value > 0 }
                if (entry.contentType == MetadataLabMediaType.SERIES && (season == null || episode == null)) {
                    return@mapNotNull null
                }

                LocalWatchedItem(
                    contentId = contentId,
                    contentType = entry.contentType,
                    title = entry.title.trim().ifEmpty { contentId },
                    season = season,
                    episode = episode,
                    watchedAtEpochMs = entry.watchedAtEpochMs.takeIf { value -> value > 0 } ?: System.currentTimeMillis()
                )
            }

        val merged = dedupeEntries(normalized)
        if (merged.isNotEmpty()) {
            saveEntries(merged)
        }

        return WatchHistoryLabResult(
            statusMessage = "Reconciled ${merged.size} remote watched entries to local history.",
            entries = merged.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() },
            authState = authState()
        )
    }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = upsertEntry(existing, normalized.toLocalWatchedItem())
        saveEntries(updated)

        val syncedTrakt = syncTraktMark(normalized)
        val syncedSimkl = syncSimklMark(normalized)
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }

        return WatchHistoryLabResult(
            statusMessage =
                "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                    "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}",
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = removeEntry(existing, normalized)
        saveEntries(updated)

        val syncedTrakt = syncTraktUnmark(normalized)
        val syncedSimkl = syncSimklUnmark(normalized)
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }

        return WatchHistoryLabResult(
            statusMessage =
                "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                    "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}",
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun listContinueWatching(limit: Int, nowMs: Long): ContinueWatchingLabResult {
        val targetLimit = limit.coerceAtLeast(1)
        val traktEntries = fetchTraktContinueWatching(nowMs)
        val simklEntries = if (traktEntries.isEmpty()) fetchSimklContinueWatching(nowMs) else emptyList()
        val localEntries = if (traktEntries.isEmpty() && simklEntries.isEmpty()) localContinueWatchingFallback() else emptyList()

        val merged = normalizeContinueWatching(
            entries = traktEntries + simklEntries + localEntries,
            nowMs = nowMs,
            limit = targetLimit
        )

        val status =
            when {
                merged.isNotEmpty() -> "Loaded ${merged.size} continue watching entries."
                traktAccessToken().isNotEmpty() -> "No Trakt continue watching entries available."
                simklAccessToken().isNotEmpty() -> "No Simkl continue watching entries available."
                else -> "No continue watching entries yet."
            }

        return ContinueWatchingLabResult(
            statusMessage = status,
            entries = merged
        )
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int): ProviderLibrarySnapshot {
        val folders = mutableListOf<ProviderLibraryFolder>()
        val items = mutableListOf<ProviderLibraryItem>()

        if (traktAccessToken().isNotEmpty() && traktClientId.isNotBlank()) {
            val trakt = fetchTraktLibrary(limitPerFolder)
            folders += trakt.first
            items += trakt.second
        }

        if (simklAccessToken().isNotEmpty() && simklClientId.isNotBlank()) {
            val simkl = fetchSimklLibrary(limitPerFolder)
            folders += simkl.first
            items += simkl.second
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
            items = items.sortedByDescending { it.addedAtEpochMs }
        )
    }

    override suspend fun fetchProviderComments(query: ProviderCommentQuery): ProviderCommentResult {
        if (traktAccessToken().isBlank() || traktClientId.isBlank()) {
            return ProviderCommentResult(statusMessage = "Trakt is not connected.")
        }

        val traktType =
            when (query.scope) {
                ProviderCommentScope.MOVIE -> "movie"
                ProviderCommentScope.SHOW, ProviderCommentScope.SEASON, ProviderCommentScope.EPISODE -> "show"
            }

        val traktId = resolveTraktId(query.imdbId, query.tmdbId, traktType)
            ?: return ProviderCommentResult(statusMessage = "Unable to resolve Trakt id for comments.")

        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceIn(1, 100)
        val endpoint =
            when (query.scope) {
                ProviderCommentScope.MOVIE -> "/movies/$traktId/comments?page=$page&limit=$limit"
                ProviderCommentScope.SHOW -> "/shows/$traktId/comments?page=$page&limit=$limit"
                ProviderCommentScope.SEASON -> {
                    val season = query.season ?: return ProviderCommentResult(statusMessage = "Season is required.")
                    "/shows/$traktId/seasons/$season/comments?page=$page&limit=$limit"
                }

                ProviderCommentScope.EPISODE -> {
                    val season = query.season ?: return ProviderCommentResult(statusMessage = "Season is required.")
                    val episode = query.episode ?: return ProviderCommentResult(statusMessage = "Episode is required.")
                    "/shows/$traktId/seasons/$season/episodes/$episode/comments?page=$page&limit=$limit"
                }
            }

        val payload = traktGetArray(endpoint) ?: return ProviderCommentResult(statusMessage = "No comments found.")
        val comments =
            buildList {
                for (index in 0 until payload.length()) {
                    val obj = payload.optJSONObject(index) ?: continue
                    val id = obj.opt("id")?.toString()?.trim().orEmpty()
                    if (id.isBlank()) {
                        continue
                    }
                    val user = obj.optJSONObject("user")
                    val username = user?.optString("username")?.trim().orEmpty().ifBlank { "unknown" }
                    val comment = obj.optString("comment").trim()
                    if (comment.isEmpty()) {
                        continue
                    }
                    add(
                        ProviderComment(
                            id = id,
                            username = username,
                            text = comment,
                            spoiler = obj.optBoolean("spoiler", false),
                            createdAtEpochMs = parseIsoToEpochMs(obj.optString("created_at")) ?: System.currentTimeMillis(),
                            likes = obj.optInt("likes", 0)
                        )
                    )
                }
            }

        return ProviderCommentResult(
            statusMessage = if (comments.isEmpty()) "No comments found." else "Loaded ${comments.size} comments.",
            comments = comments
        )
    }

    private fun localContinueWatchingFallback(): List<ContinueWatchingEntry> {
        return loadEntries()
            .sortedByDescending { it.watchedAtEpochMs }
            .map { item ->
                ContinueWatchingEntry(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    title = item.title,
                    season = item.season,
                    episode = item.episode,
                    progressPercent = 100.0,
                    lastUpdatedEpochMs = item.watchedAtEpochMs,
                    provider = WatchProvider.LOCAL
                )
            }
    }

    private fun normalizeContinueWatching(entries: List<ContinueWatchingEntry>, nowMs: Long, limit: Int): List<ContinueWatchingEntry> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val candidates = entries.filter { entry ->
            val progress = entry.progressPercent
            progress >= 2.0 && progress < 85.0 && entry.lastUpdatedEpochMs >= staleCutoff
        }

        val byContent = linkedMapOf<String, ContinueWatchingEntry>()
        candidates.forEach { entry ->
            val key = "${entry.contentType.name}:${entry.contentId}".lowercase(Locale.US)
            val current = byContent[key]
            if (current == null) {
                byContent[key] = entry
                return@forEach
            }

            val sameEpisode = current.season == entry.season && current.episode == entry.episode
            val replacement =
                when {
                    sameEpisode -> if (entry.progressPercent >= current.progressPercent) entry else current
                    entry.lastUpdatedEpochMs > current.lastUpdatedEpochMs -> entry
                    entry.lastUpdatedEpochMs < current.lastUpdatedEpochMs -> current
                    else -> if (entry.progressPercent >= current.progressPercent) entry else current
                }
            byContent[key] = replacement
        }

        return byContent.values
            .sortedByDescending { it.lastUpdatedEpochMs }
            .take(limit)
    }

    private fun fetchTraktContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        val payload = traktGetArray("/sync/playback") ?: return emptyList()
        return buildList {
            for (index in 0 until payload.length()) {
                val obj = payload.optJSONObject(index) ?: continue
                val type = obj.optString("type").trim().lowercase(Locale.US)
                val progress = obj.optDouble("progress", -1.0)
                if (progress < 0) continue

                if (type == "movie") {
                    val movie = obj.optJSONObject("movie") ?: continue
                    val imdbId = movie.optJSONObject("ids")?.optString("imdb")?.trim().orEmpty()
                    if (imdbId.isEmpty()) continue
                    val title = movie.optString("title").trim().ifEmpty { imdbId }
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(
                        ContinueWatchingEntry(
                            contentId = imdbId,
                            contentType = MetadataLabMediaType.MOVIE,
                            title = title,
                            season = null,
                            episode = null,
                            progressPercent = progress,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.TRAKT,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null }
                        )
                    )
                    continue
                }

                if (type == "episode") {
                    val episode = obj.optJSONObject("episode") ?: continue
                    val show = obj.optJSONObject("show") ?: continue
                    val imdbId = show.optJSONObject("ids")?.optString("imdb")?.trim().orEmpty()
                    if (imdbId.isEmpty()) continue
                    val season = episode.optInt("season", 0).takeIf { it > 0 }
                    val number = episode.optInt("number", 0).takeIf { it > 0 }
                    val showTitle = show.optString("title").trim().ifEmpty { imdbId }
                    val episodeTitle = episode.optString("title").trim()
                    val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(
                        ContinueWatchingEntry(
                            contentId = imdbId,
                            contentType = MetadataLabMediaType.SERIES,
                            title = title,
                            season = season,
                            episode = number,
                            progressPercent = progress,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.TRAKT,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null }
                        )
                    )
                }
            }
        }
    }

    private fun fetchSimklContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        val payload = simklGetAny("/sync/playback") ?: return emptyList()
        val array = payload.toJsonArrayOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = obj.optString("type").trim().lowercase(Locale.US)
                val progress = obj.optDouble("progress", -1.0)
                if (progress < 0) continue

                if (type == "movie") {
                    val movie = obj.optJSONObject("movie") ?: continue
                    val ids = movie.optJSONObject("ids")
                    val imdbId = ids?.optString("imdb")?.trim().orEmpty()
                    if (imdbId.isEmpty()) continue
                    val title = movie.optString("title").trim().ifEmpty { imdbId }
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(
                        ContinueWatchingEntry(
                            contentId = imdbId,
                            contentType = MetadataLabMediaType.MOVIE,
                            title = title,
                            season = null,
                            episode = null,
                            progressPercent = progress,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.SIMKL,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null }
                        )
                    )
                    continue
                }

                val show = obj.optJSONObject("show")
                val episode = obj.optJSONObject("episode")
                if (show == null && episode == null) {
                    continue
                }

                val ids = show?.optJSONObject("ids") ?: episode?.optJSONObject("show")
                val imdbId = ids?.optString("imdb")?.trim().orEmpty()
                if (imdbId.isEmpty()) continue

                val showTitle = show?.optString("title")?.trim().orEmpty().ifBlank { imdbId }
                val season = episode?.optInt("season", 0)?.takeIf { it > 0 }
                val number =
                    (episode?.optInt("episode", 0)?.takeIf { it > 0 }
                        ?: episode?.optInt("number", 0)?.takeIf { it > 0 })
                val episodeTitle = episode?.optString("title")?.trim().orEmpty()
                val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"
                val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                add(
                    ContinueWatchingEntry(
                        contentId = imdbId,
                        contentType = MetadataLabMediaType.SERIES,
                        title = title,
                        season = season,
                        episode = number,
                        progressPercent = progress,
                        lastUpdatedEpochMs = pausedAt,
                        provider = WatchProvider.SIMKL,
                        providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null }
                    )
                )
            }
        }
    }

    private fun fetchTraktLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        addTraktFolder(folderItems, "continue-watching", fetchTraktContinueWatching(System.currentTimeMillis()).map {
            ProviderLibraryItem(
                provider = WatchProvider.TRAKT,
                folderId = "continue-watching",
                contentId = it.contentId,
                contentType = it.contentType,
                title = it.title,
                season = it.season,
                episode = it.episode,
                addedAtEpochMs = it.lastUpdatedEpochMs
            )
        }, limitPerFolder)

        addTraktFolder(folderItems, "watched", traktHistoryItems(), limitPerFolder)
        addTraktFolder(folderItems, "watchlist", traktWatchlistItems(), limitPerFolder)
        addTraktFolder(folderItems, "collection", traktCollectionItems(), limitPerFolder)
        addTraktFolder(folderItems, "ratings", traktRatingsItems(), limitPerFolder)

        val folders =
            folderItems.entries
                .filter { it.value.isNotEmpty() }
                .map { (id, values) ->
                    ProviderLibraryFolder(
                        id = id,
                        label = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        provider = WatchProvider.TRAKT,
                        itemCount = values.size
                    )
                }
        return folders to folderItems.values.flatten()
    }

    private fun fetchSimklLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        addSimklFolder(folderItems, "continue-watching", fetchSimklContinueWatching(System.currentTimeMillis()).map {
            ProviderLibraryItem(
                provider = WatchProvider.SIMKL,
                folderId = "continue-watching",
                contentId = it.contentId,
                contentType = it.contentType,
                title = it.title,
                season = it.season,
                episode = it.episode,
                addedAtEpochMs = it.lastUpdatedEpochMs
            )
        }, limitPerFolder)

        val statuses = listOf("watching", "plantowatch", "completed", "onhold", "dropped")
        val types = listOf("shows" to MetadataLabMediaType.SERIES, "movies" to MetadataLabMediaType.MOVIE, "anime" to MetadataLabMediaType.SERIES)
        statuses.forEach { status ->
            types.forEach { (type, contentType) ->
                val folderId = "$status-$type"
                val endpoint = "/sync/all-items/$type/$status"
                val items = parseSimklListItems(endpoint, folderId, contentType)
                addSimklFolder(folderItems, folderId, items, limitPerFolder)
            }
        }

        addSimklFolder(folderItems, "ratings", parseSimklRatingsItems(), limitPerFolder)

        val folders =
            folderItems.entries
                .filter { it.value.isNotEmpty() }
                .map { (id, values) ->
                    ProviderLibraryFolder(
                        id = id,
                        label = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        provider = WatchProvider.SIMKL,
                        itemCount = values.size
                    )
                }
        return folders to folderItems.values.flatten()
    }

    private fun addTraktFolder(
        bucket: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        id: String,
        values: List<ProviderLibraryItem>,
        limit: Int
    ) {
        if (values.isEmpty()) return
        val capped = values.take(limit.coerceAtLeast(1))
        val folderId = id
        bucket.getOrPut(folderId) { mutableListOf() }.addAll(capped.map { it.copy(folderId = folderId) })
    }

    private fun addSimklFolder(
        bucket: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        id: String,
        values: List<ProviderLibraryItem>,
        limit: Int
    ) {
        if (values.isEmpty()) return
        val capped = values.take(limit.coerceAtLeast(1))
        val folderId = id
        bucket.getOrPut(folderId) { mutableListOf() }.addAll(capped.map { it.copy(folderId = folderId) })
    }

    private fun traktHistoryItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watched/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watched/shows") ?: JSONArray()
        return parseTraktItemsFromWatched(movies, MetadataLabMediaType.MOVIE, "watched") +
            parseTraktItemsFromWatched(shows, MetadataLabMediaType.SERIES, "watched")
    }

    private fun traktWatchlistItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watchlist/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watchlist/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "watchlist") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "watchlist")
    }

    private fun traktCollectionItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/collection/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/collection/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "collection") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "collection")
    }

    private fun traktRatingsItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/ratings/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/ratings/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "ratings") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "ratings")
    }

    private fun parseTraktItemsFromWatched(array: JSONArray, contentType: MetadataLabMediaType, folderId: String): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = if (contentType == MetadataLabMediaType.MOVIE) obj.optJSONObject("movie") else obj.optJSONObject("show")
                val ids = node?.optJSONObject("ids")
                val imdbId = ids?.optString("imdb")?.trim().orEmpty()
                if (imdbId.isEmpty()) continue
                val title = node?.optString("title")?.trim().orEmpty().ifBlank { imdbId }
                val addedAt = parseIsoToEpochMs(obj.optString("last_watched_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = imdbId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }
    }

    private fun parseTraktItemsFromList(
        array: JSONArray,
        key: String,
        contentType: MetadataLabMediaType,
        folderId: String
    ): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = obj.optJSONObject(key) ?: continue
                val ids = node.optJSONObject("ids")
                val imdbId = ids?.optString("imdb")?.trim().orEmpty()
                if (imdbId.isEmpty()) continue
                val title = node.optString("title").trim().ifEmpty { imdbId }
                val addedAt = parseIsoToEpochMs(obj.optString("listed_at"))
                    ?: parseIsoToEpochMs(obj.optString("rated_at"))
                    ?: parseIsoToEpochMs(obj.optString("collected_at"))
                    ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = imdbId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }
    }

    private fun parseSimklListItems(
        endpoint: String,
        folderId: String,
        contentType: MetadataLabMediaType
    ): List<ProviderLibraryItem> {
        val payload = simklGetAny(endpoint) ?: return emptyList()
        val array = payload.toJsonArrayOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ids = item.optJSONObject("ids")
                    ?: item.optJSONObject("movie")?.optJSONObject("ids")
                    ?: item.optJSONObject("show")?.optJSONObject("ids")
                val imdbId = ids?.optString("imdb")?.trim().orEmpty()
                if (imdbId.isEmpty()) continue

                val title =
                    item.optString("title").trim().ifEmpty {
                        item.optJSONObject("movie")?.optString("title")?.trim().orEmpty().ifBlank {
                            item.optJSONObject("show")?.optString("title")?.trim().orEmpty().ifBlank { imdbId }
                        }
                    }
                val addedAt = parseIsoToEpochMs(item.optString("last_watched_at"))
                    ?: parseIsoToEpochMs(item.optString("added_to_watchlist_at"))
                    ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = folderId,
                        contentId = imdbId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }
    }

    private fun parseSimklRatingsItems(): List<ProviderLibraryItem> {
        val payload = simklGetAny("/sync/ratings") ?: return emptyList()
        val objectPayload = payload.toJsonObjectOrNull() ?: return emptyList()
        val items = mutableListOf<ProviderLibraryItem>()
        listOf(
            "movies" to MetadataLabMediaType.MOVIE,
            "shows" to MetadataLabMediaType.SERIES,
            "anime" to MetadataLabMediaType.SERIES
        ).forEach { (key, contentType) ->
            val array = objectPayload.optJSONArray(key) ?: return@forEach
            items += parseSimklRatingsArray(array, contentType)
        }
        return items
    }

    private fun parseSimklRatingsArray(array: JSONArray, contentType: MetadataLabMediaType): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ids = item.optJSONObject("ids") ?: continue
                val imdbId = ids.optString("imdb").trim()
                if (imdbId.isEmpty()) continue
                val title = item.optString("title").trim().ifEmpty { imdbId }
                val ratedAt = parseIsoToEpochMs(item.optString("rated_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = "ratings",
                        contentId = imdbId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = ratedAt
                    )
                )
            }
        }
    }

    private fun resolveTraktId(imdbId: String, tmdbId: Int?, traktType: String): String? {
        val normalizedImdbId = imdbId.trim()
        if (normalizedImdbId.isNotEmpty()) {
            val search = traktSearchGetArray(traktType, "imdb", normalizedImdbId)
            val id = extractTraktIdFromSearch(search, traktType)
            if (id != null) {
                return id
            }
        }

        if (tmdbId != null && tmdbId > 0) {
            val search = traktSearchGetArray(traktType, "tmdb", tmdbId.toString())
            val id = extractTraktIdFromSearch(search, traktType)
            if (id != null) {
                return id
            }
        }

        return null
    }

    private fun extractTraktIdFromSearch(search: JSONArray?, traktType: String): String? {
        if (search == null || search.length() == 0) {
            return null
        }
        val first = search.optJSONObject(0) ?: return null
        val node = first.optJSONObject(traktType) ?: return null
        return node.optJSONObject("ids")?.opt("trakt")?.toString()?.trim()?.ifEmpty { null }
    }

    private fun traktSearchGetArray(traktType: String, idType: String, id: String): JSONArray? {
        val safeType = if (traktType == "movie") "movie" else "show"
        val endpoint = "/search/$safeType?id_type=$idType&id=$id"
        val token = traktAccessToken()
        val headers =
            buildMap {
                put("trakt-api-version", "2")
                put("trakt-api-key", traktClientId)
                put("Accept", "application/json")
                if (token.isNotEmpty()) {
                    put("Authorization", "Bearer $token")
                }
            }
        return getJsonArray("https://api.trakt.tv$endpoint", headers)
    }

    private fun traktGetArray(path: String): JSONArray? {
        val token = traktAccessToken()
        if (token.isEmpty() || traktClientId.isBlank()) {
            return null
        }
        return getJsonArray(
            url = "https://api.trakt.tv$path",
            headers =
                mapOf(
                    "Authorization" to "Bearer $token",
                    "trakt-api-version" to "2",
                    "trakt-api-key" to traktClientId,
                    "Accept" to "application/json"
                )
        )
    }

    private fun simklGetAny(path: String): Any? {
        val token = simklAccessToken()
        if (token.isEmpty() || simklClientId.isBlank()) {
            return null
        }
        return getJsonAny(
            url = "https://api.simkl.com$path",
            headers =
                mapOf(
                    "Authorization" to "Bearer $token",
                    "simkl-api-key" to simklClientId,
                    "Accept" to "application/json"
                )
        )
    }

    private fun parseIsoToEpochMs(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching {
                val normalized = if (text.contains(".")) {
                    text.substringBefore('.') + "Z"
                } else {
                    text
                }
                Instant.parse(normalized).toEpochMilli()
            }
            .getOrNull()
    }

    private fun syncStatusLabel(synced: Boolean, token: String, clientId: String): String {
        return when {
            synced -> "ok"
            token.isEmpty() -> "skip(no-token)"
            clientId.isEmpty() -> "skip(no-client-id)"
            else -> "failed"
        }
    }

    private fun normalizeRequest(request: WatchHistoryRequest): NormalizedWatchRequest {
        val normalizedId = normalizeNuvioMediaId(request.contentId)
        val contentId = normalizedId.contentId.trim()
        require(contentId.isNotEmpty()) { "Content ID is required" }

        val isSeries = request.contentType == MetadataLabMediaType.SERIES
        val season = if (isSeries) request.season ?: normalizedId.season else null
        val episode = if (isSeries) request.episode ?: normalizedId.episode else null
        if (isSeries) {
            require(season != null && season > 0) { "Season must be a positive number" }
            require(episode != null && episode > 0) { "Episode must be a positive number" }
        }

        val requestRemoteImdbId = request.remoteImdbId?.trim()
        val remoteImdbId =
            when {
                contentId.startsWith("tt", ignoreCase = true) -> contentId.lowercase()
                requestRemoteImdbId?.startsWith("tt", ignoreCase = true) == true ->
                    requestRemoteImdbId.lowercase()
                else -> null
            }

        val title =
            request.title
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: if (isSeries) {
                    "$contentId S$season E$episode"
                } else {
                    contentId
                }

        return NormalizedWatchRequest(
            contentId = contentId,
            contentType = request.contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = System.currentTimeMillis(),
            remoteImdbId = remoteImdbId
        )
    }

    private fun loadEntries(): List<LocalWatchedItem> {
        val raw = prefs.getString(KEY_LOCAL_WATCHED_ITEMS, null) ?: return emptyList()
        return runCatching {
            val values = mutableListOf<LocalWatchedItem>()
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                LocalWatchedItem.fromJson(obj)?.let(values::add)
            }
            values
        }.getOrElse {
            emptyList()
        }
    }

    private fun saveEntries(items: List<LocalWatchedItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(item.toJson()) }
        prefs.edit().putString(KEY_LOCAL_WATCHED_ITEMS, array.toString()).apply()
    }

    private fun upsertEntry(existing: List<LocalWatchedItem>, next: LocalWatchedItem): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        existing.forEach { item ->
            byKey[watchedKey(item.contentId, item.season, item.episode)] = item
        }
        val key = watchedKey(next.contentId, next.season, next.episode)
        val current = byKey[key]
        if (current == null || next.watchedAtEpochMs >= current.watchedAtEpochMs) {
            byKey[key] = next
        }
        return byKey.values.toList()
    }

    private fun dedupeEntries(items: List<LocalWatchedItem>): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        items.forEach { item ->
            val key = watchedKey(item.contentId, item.season, item.episode)
            val current = byKey[key]
            if (current == null || item.watchedAtEpochMs >= current.watchedAtEpochMs) {
                byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    private fun removeEntry(existing: List<LocalWatchedItem>, request: NormalizedWatchRequest): List<LocalWatchedItem> {
        val removalKey = watchedKey(request.contentId, request.season, request.episode)
        return existing.filterNot { item ->
            watchedKey(item.contentId, item.season, item.episode) == removalKey
        }
    }

    private fun watchedKey(contentId: String, season: Int?, episode: Int?): String {
        return "$contentId::${season ?: -1}::${episode ?: -1}"
    }

    private fun syncTraktMark(request: NormalizedWatchRequest): Boolean {
        val token = traktAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || traktClientId.isEmpty() || imdbId == null) {
            return false
        }

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject()
                                .put("watched_at", watchedAtIso)
                                .put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("number", request.episode)
                                                        .put("watched_at", watchedAtIso)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return traktPost("/sync/history", body)
    }

    private fun syncTraktUnmark(request: NormalizedWatchRequest): Boolean {
        val token = traktAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || traktClientId.isEmpty() || imdbId == null) {
            return false
        }

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject().put("number", request.episode)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return traktPost("/sync/history/remove", body)
    }

    private fun traktPost(path: String, payload: JSONObject): Boolean {
        val token = traktAccessToken()
        if (token.isEmpty() || traktClientId.isEmpty()) {
            return false
        }

        val responseCode =
            postJson(
                url = "https://api.trakt.tv$path",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "trakt-api-version" to "2",
                        "trakt-api-key" to traktClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                payload = payload
            )

        return responseCode in 200..299 || responseCode == 409
    }

    private fun syncSimklMark(request: NormalizedWatchRequest): Boolean {
        val token = simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) {
            return false
        }

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put("watched_at", watchedAtIso)
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("number", request.episode)
                                                        .put("watched_at", watchedAtIso)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return simklPost("/sync/history", body)
    }

    private fun syncSimklUnmark(request: NormalizedWatchRequest): Boolean {
        val token = simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) {
            return false
        }

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject().put("number", request.episode)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return simklPost("/sync/history/remove", body)
    }

    private fun simklPost(path: String, payload: JSONObject): Boolean {
        val token = simklAccessToken()
        if (token.isEmpty() || simklClientId.isEmpty()) {
            return false
        }

        val responseCode =
            postJson(
                url = "https://api.simkl.com$path",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                payload = payload
            )

        return responseCode in 200..299 || responseCode == 409
    }

    private fun postJson(url: String, headers: Map<String, String>, payload: JSONObject): Int? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        return runCatching {
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "HTTP POST $url failed with $responseCode body=${compactForLog(errorBody)}")
            }
            responseCode
        }.onFailure { error ->
            Log.w(TAG, "HTTP POST $url failed with exception", error)
        }.getOrNull().also {
            runCatching {
                connection.inputStream?.close()
                connection.errorStream?.close()
            }
            connection.disconnect()
        }
    }

    private fun postJsonForObject(url: String, headers: Map<String, String>, payload: JSONObject): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        return runCatching {
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            val responseStream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                Log.w(TAG, "HTTP POST $url failed with $responseCode body=${compactForLog(body)}")
                null
            } else if (body.isBlank()) {
                Log.w(TAG, "HTTP POST $url returned empty JSON body")
                null
            } else {
                runCatching { JSONObject(body) }
                    .onFailure { error ->
                        Log.w(TAG, "HTTP POST $url returned malformed JSON body=${compactForLog(body)}", error)
                    }
                    .getOrNull()
            }
        }.onFailure { error ->
            Log.w(TAG, "HTTP POST $url failed with exception", error)
        }.getOrNull().also {
            runCatching {
                connection.inputStream?.close()
                connection.errorStream?.close()
            }
            connection.disconnect()
        }
    }

    private fun getJsonArray(url: String, headers: Map<String, String>): JSONArray? {
        return getJsonAny(url = url, headers = headers).toJsonArrayOrNull()
    }

    private fun getJsonAny(url: String, headers: Map<String, String>): Any? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        return runCatching {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                Log.w(TAG, "HTTP GET $url failed with $responseCode body=${compactForLog(body)}")
                null
            } else if (body.isBlank()) {
                Log.w(TAG, "HTTP GET $url returned empty body")
                null
            } else if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body)
            }
        }.onFailure { error ->
            Log.w(TAG, "HTTP GET $url failed with exception", error)
        }.getOrNull().also {
            runCatching {
                connection.inputStream?.close()
                connection.errorStream?.close()
            }
            connection.disconnect()
        }
    }

    private fun traktSessionOrNull(): WatchProviderSession? {
        val token = traktAccessToken()
        if (token.isBlank()) {
            return null
        }
        val refresh = prefs.getString(KEY_TRAKT_REFRESH_TOKEN, null)?.trim()?.ifBlank { null }
        val expiresAt = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
        val handle = prefs.getString(KEY_TRAKT_HANDLE, null)?.trim()?.ifBlank { null }
        return WatchProviderSession(
            accessToken = token,
            refreshToken = refresh,
            expiresAtEpochMs = expiresAt,
            userHandle = handle
        )
    }

    private fun simklSessionOrNull(): WatchProviderSession? {
        val token = simklAccessToken()
        if (token.isBlank()) {
            return null
        }
        val handle = prefs.getString(KEY_SIMKL_HANDLE, null)?.trim()?.ifBlank { null }
        return WatchProviderSession(
            accessToken = token,
            userHandle = handle
        )
    }

    private fun fetchTraktUserHandle(accessToken: String): String? {
        if (traktClientId.isBlank() || accessToken.isBlank()) {
            Log.w(TAG, "Skipping Trakt profile lookup: missing auth inputs")
            return null
        }

        val response =
            getJsonAny(
                url = "https://api.trakt.tv/users/settings",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "trakt-api-version" to "2",
                        "trakt-api-key" to traktClientId,
                        "Accept" to "application/json"
                    )
            ).toJsonObjectOrNull() ?: run {
                Log.w(TAG, "Trakt profile lookup returned no payload")
                return null
            }

        val user = response.optJSONObject("user")
        val username = user?.optString("username")?.trim().orEmpty()
        if (username.isBlank()) {
            Log.w(TAG, "Trakt profile lookup succeeded but username missing")
        }
        return username.ifBlank { null }
    }

    private fun fetchSimklUserHandle(accessToken: String): String? {
        if (simklClientId.isBlank() || accessToken.isBlank()) {
            Log.w(TAG, "Skipping Simkl profile lookup: missing auth inputs")
            return null
        }

        val response =
            postJsonForObject(
                url = "$SIMKL_API_BASE/users/settings",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                payload = JSONObject()
            ) ?: run {
                Log.w(TAG, "Simkl profile lookup returned no payload")
                return null
            }

        val user = response.optJSONObject("user")
        val name = user?.optString("name")?.trim().orEmpty()
        if (name.isNotBlank()) {
            return name
        }
        val account = response.optJSONObject("account")
        val accountId = account?.opt("id")?.toString()?.trim().orEmpty()
        if (accountId.isBlank()) {
            Log.w(TAG, "Simkl profile lookup succeeded but account identifier missing")
        }
        return accountId.ifBlank { null }
    }

    private fun uriSummaryForLog(uri: Uri): String {
        val statePresent = !uri.getQueryParameter("state").isNullOrBlank()
        val codePresent = !uri.getQueryParameter("code").isNullOrBlank()
        val errorPresent = !uri.getQueryParameter("error").isNullOrBlank()
        return "scheme=${uri.scheme.orEmpty()}, host=${uri.host.orEmpty()}, path=${uri.path.orEmpty()}, statePresent=$statePresent, codePresent=$codePresent, errorPresent=$errorPresent"
    }

    private fun compactForLog(body: String, maxLength: Int = 240): String {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) {
            return "<empty>"
        }
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }

    private fun jsonKeysForLog(jsonObject: JSONObject): String {
        val names = jsonObject.names() ?: return "<none>"
        return (0 until names.length()).joinToString(separator = ",") { index ->
            names.optString(index)
        }
    }

    private fun clearPendingTraktOAuth() {
        prefs.edit().apply {
            remove(KEY_TRAKT_OAUTH_STATE)
            remove(KEY_TRAKT_OAUTH_CODE_VERIFIER)
        }.apply()
    }

    private fun clearPendingSimklOAuth() {
        prefs.edit().apply {
            remove(KEY_SIMKL_OAUTH_STATE)
        }.apply()
    }

    private fun generateUrlSafeToken(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    private fun codeChallengeFromVerifier(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return base64UrlEncoder.encodeToString(digest)
    }

    private fun traktAccessToken(): String {
        return prefs.getString(KEY_TRAKT_TOKEN, null)?.trim().orEmpty()
    }

    private fun simklAccessToken(): String {
        return prefs.getString(KEY_SIMKL_TOKEN, null)?.trim().orEmpty()
    }

    companion object {
        private const val TAG = "RemoteWatchHistoryLab"
        private const val PREFS_NAME = "watch_history_lab"
        private const val KEY_LOCAL_WATCHED_ITEMS = "@user:local:watched_items"
        private const val KEY_TRAKT_TOKEN = "trakt_access_token"
        private const val KEY_TRAKT_REFRESH_TOKEN = "trakt_refresh_token"
        private const val KEY_TRAKT_EXPIRES_AT = "trakt_expires_at"
        private const val KEY_TRAKT_HANDLE = "trakt_user_handle"
        private const val KEY_TRAKT_OAUTH_STATE = "trakt_oauth_state"
        private const val KEY_TRAKT_OAUTH_CODE_VERIFIER = "trakt_oauth_code_verifier"
        private const val KEY_SIMKL_TOKEN = "simkl_access_token"
        private const val KEY_SIMKL_HANDLE = "simkl_user_handle"
        private const val KEY_SIMKL_OAUTH_STATE = "simkl_oauth_state"
        private const val STALE_PLAYBACK_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L
        private const val TRAKT_AUTHORIZE_BASE = "https://trakt.tv/oauth/authorize"
        private const val TRAKT_TOKEN_URL = "https://api.trakt.tv/oauth/token"
        private const val SIMKL_AUTHORIZE_BASE = "https://simkl.com/oauth/authorize"
        private const val SIMKL_TOKEN_URL = "https://api.simkl.com/oauth/token"
        private const val SIMKL_API_BASE = "https://api.simkl.com"
        private val secureRandom = SecureRandom()
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    }
}

private fun Any?.toJsonArrayOrNull(): JSONArray? {
    return this as? JSONArray
}

private fun Any?.toJsonObjectOrNull(): JSONObject? {
    return this as? JSONObject
}

private data class NormalizedWatchRequest(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
    val remoteImdbId: String?
) {
    fun toLocalWatchedItem(): LocalWatchedItem {
        return LocalWatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs
        )
    }
}

private data class LocalWatchedItem(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long
) {
    fun toPublicEntry(): WatchHistoryEntry {
        return WatchHistoryEntry(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("content_id", contentId)
            .put("content_type", contentType.name.lowercase())
            .put("title", title)
            .put("season", season)
            .put("episode", episode)
            .put("watched_at", watchedAtEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): LocalWatchedItem? {
            val contentId = json.optString("content_id").trim()
            if (contentId.isEmpty()) {
                return null
            }

            val contentType =
                when (json.optString("content_type").trim().lowercase()) {
                    "movie" -> MetadataLabMediaType.MOVIE
                    "series" -> MetadataLabMediaType.SERIES
                    else -> return null
                }
            val title = json.optString("title").trim().ifEmpty { contentId }
            val season = json.optInt("season", Int.MIN_VALUE).takeIf { value -> value > 0 }
            val episode = json.optInt("episode", Int.MIN_VALUE).takeIf { value -> value > 0 }
            val watchedAt = json.optLong("watched_at", System.currentTimeMillis())

            return LocalWatchedItem(
                contentId = contentId,
                contentType = contentType,
                title = title,
                season = season,
                episode = episode,
                watchedAtEpochMs = watchedAt
            )
        }
    }
}
