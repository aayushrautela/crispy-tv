package com.crispy.rewrite.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.domain.metadata.normalizeNuvioMediaId
import com.crispy.rewrite.network.CrispyHttpClient
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
import com.crispy.rewrite.player.ProviderRecommendationsResult
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabResult
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchProviderAuthState
import com.crispy.rewrite.player.WatchProviderSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.time.Instant
import java.util.Locale

class RemoteWatchHistoryLabService(
    context: Context,
    private val httpClient: CrispyHttpClient,
    private val traktClientId: String,
    private val simklClientId: String,
    private val traktClientSecret: String = BuildConfig.TRAKT_CLIENT_SECRET,
    private val traktRedirectUri: String = BuildConfig.TRAKT_REDIRECT_URI,
    private val simklClientSecret: String = BuildConfig.SIMKL_CLIENT_SECRET,
    private val simklRedirectUri: String = BuildConfig.SIMKL_REDIRECT_URI
) : WatchHistoryLabService {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val providerCacheDir = File(appContext.filesDir, "watch_provider_cache").apply { mkdirs() }

    private val traktRefreshMutex = Mutex()

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
                    remove(KEY_SIMKL_TOKEN)
                    remove(KEY_SIMKL_HANDLE)
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
                    remove(KEY_TRAKT_TOKEN)
                    remove(KEY_TRAKT_REFRESH_TOKEN)
                    remove(KEY_TRAKT_EXPIRES_AT)
                    remove(KEY_TRAKT_HANDLE)
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
                .appendQueryParameter("app-name", SIMKL_APP_NAME)
                .appendQueryParameter("app-version", simklAppVersion())
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
                url = simklUrlWithRequiredParams(SIMKL_TOKEN_URL),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to simklUserAgent()
                ),
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

    override suspend fun markWatched(
        request: WatchHistoryRequest,
        source: WatchProvider?
    ): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = upsertEntry(existing, normalized.toLocalWatchedItem())
        saveEntries(updated)

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> syncTraktMark(normalized)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> syncTraktMark(normalized)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> syncSimklMark(normalized)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> syncSimklMark(normalized)
            }
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT ->
                    "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)}"

                WatchProvider.SIMKL ->
                    "Marked watched locally. simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"

                WatchProvider.LOCAL -> "Marked watched locally."
                null ->
                    "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                        "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"
            }

        return WatchHistoryLabResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun unmarkWatched(
        request: WatchHistoryRequest,
        source: WatchProvider?
    ): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = removeEntry(existing, normalized)
        saveEntries(updated)

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> syncTraktUnmark(normalized)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> syncTraktUnmark(normalized)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> syncSimklUnmark(normalized)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> syncSimklUnmark(normalized)
            }
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT ->
                    "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)}"

                WatchProvider.SIMKL ->
                    "Removed watched entry locally. simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"

                WatchProvider.LOCAL -> "Removed watched entry locally."
                null ->
                    "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                        "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"
            }

        return WatchHistoryLabResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun setInWatchlist(
        request: WatchHistoryRequest,
        inWatchlist: Boolean,
        source: WatchProvider?
    ): WatchHistoryLabResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryLabResult(statusMessage = "Watchlist is unavailable for local watch history.")
        }

        val normalized = normalizeContentRequest(request)
        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> syncTraktWatchlist(normalized, inWatchlist)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> syncTraktWatchlist(normalized, inWatchlist)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> syncSimklWatchlist(normalized, inWatchlist)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> syncSimklWatchlist(normalized, inWatchlist)
            }
        if (syncedTrakt) invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) invalidateProviderLibraryCache(WatchProvider.SIMKL)

        val verb = if (inWatchlist) "Saved" else "Removed"
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT ->
                    "$verb. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)}"

                WatchProvider.SIMKL ->
                    "$verb. simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"

                WatchProvider.LOCAL -> "$verb."
                null ->
                    "$verb. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                        "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"
            }

        return WatchHistoryLabResult(
            statusMessage = statusMessage,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun setRating(
        request: WatchHistoryRequest,
        rating: Int?,
        source: WatchProvider?
    ): WatchHistoryLabResult {
        if (source == WatchProvider.LOCAL) {
            return WatchHistoryLabResult(statusMessage = "Rating is unavailable for local watch history.")
        }

        val normalized = normalizeContentRequest(request)
        val ratingValue = rating?.coerceIn(1, 10)
        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> syncTraktRating(normalized, ratingValue)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> syncTraktRating(normalized, ratingValue)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> syncSimklRating(normalized, ratingValue)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> syncSimklRating(normalized, ratingValue)
            }
        if (syncedTrakt) invalidateProviderLibraryCache(WatchProvider.TRAKT)
        if (syncedSimkl) invalidateProviderLibraryCache(WatchProvider.SIMKL)

        val verb = if (ratingValue == null) "Removed rating" else "Rated $ratingValue/10"
        val statusMessage =
            when (source) {
                WatchProvider.TRAKT ->
                    "$verb. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)}"

                WatchProvider.SIMKL ->
                    "$verb. simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"

                WatchProvider.LOCAL -> "$verb."
                null ->
                    "$verb. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                        "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"
            }

        return WatchHistoryLabResult(
            statusMessage = statusMessage,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun removeFromPlayback(playbackId: String, source: WatchProvider?): WatchHistoryLabResult {
        val id = playbackId.trim()
        val existing = loadEntries()
        val entries = existing.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }

        if (id.isEmpty()) {
            return WatchHistoryLabResult(
                statusMessage = "Playback id missing.",
                entries = entries,
                authState = authState()
            )
        }

        val syncedTrakt =
            when (source) {
                WatchProvider.TRAKT -> syncTraktRemovePlayback(id)
                WatchProvider.SIMKL, WatchProvider.LOCAL -> false
                null -> syncTraktRemovePlayback(id)
            }
        val syncedSimkl =
            when (source) {
                WatchProvider.SIMKL -> syncSimklRemovePlayback(id)
                WatchProvider.TRAKT, WatchProvider.LOCAL -> false
                null -> syncSimklRemovePlayback(id)
            }

        val statusMessage =
            when (source) {
                WatchProvider.TRAKT ->
                    "Removed from continue watching. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)}"

                WatchProvider.SIMKL ->
                    "Removed from continue watching. simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"

                WatchProvider.LOCAL -> "Removed from continue watching."
                null ->
                    "Removed from continue watching. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                        "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}"
            }

        return WatchHistoryLabResult(
            statusMessage = statusMessage,
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    private data class NormalizedContentRequest(
        val contentId: String,
        val contentType: MetadataLabMediaType,
        val title: String,
        val remoteImdbId: String?
    )

    private fun normalizeContentRequest(request: WatchHistoryRequest): NormalizedContentRequest {
        val normalizedId = normalizeNuvioMediaId(request.contentId)
        val contentId = normalizedId.contentId.trim()
        require(contentId.isNotEmpty()) { "Content ID is required" }

        val requestRemoteImdbId = request.remoteImdbId?.trim()
        val remoteImdbId =
            when {
                contentId.startsWith("tt", ignoreCase = true) -> contentId.lowercase()
                requestRemoteImdbId?.startsWith("tt", ignoreCase = true) == true -> requestRemoteImdbId.lowercase()
                else -> null
            }

        val title =
            request.title
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: contentId

        return NormalizedContentRequest(
            contentId = contentId,
            contentType = request.contentType,
            title = title,
            remoteImdbId = remoteImdbId
        )
    }

    private fun traktIdsForContent(contentId: String, remoteImdbId: String?): JSONObject? {
        val ids = JSONObject()
        if (!remoteImdbId.isNullOrBlank()) {
            ids.put("imdb", remoteImdbId)
        }

        val tmdbId =
            Regex("""\\btmdb:(?:movie:|show:)?(\\d+)""", RegexOption.IGNORE_CASE)
                .find(contentId)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
        if (tmdbId > 0) {
            ids.put("tmdb", tmdbId)
        }

        return if (ids.length() > 0) ids else null
    }

    private suspend fun syncTraktWatchlist(request: NormalizedContentRequest, inWatchlist: Boolean): Boolean {
        val ids = traktIdsForContent(request.contentId, request.remoteImdbId) ?: return false
        val payload = JSONObject()
        if (request.contentType == MetadataLabMediaType.MOVIE) {
            payload.put("movies", JSONArray().put(JSONObject().put("ids", ids)))
        } else {
            payload.put("shows", JSONArray().put(JSONObject().put("ids", ids)))
        }
        val path = if (inWatchlist) "/sync/watchlist" else "/sync/watchlist/remove"
        return traktPost(path, payload)
    }

    private suspend fun syncTraktRating(request: NormalizedContentRequest, rating: Int?): Boolean {
        val ids = traktIdsForContent(request.contentId, request.remoteImdbId) ?: return false
        val payload = JSONObject()
        val item = JSONObject().put("ids", ids)
        if (rating != null) {
            item.put("rating", rating)
        }

        if (request.contentType == MetadataLabMediaType.MOVIE) {
            payload.put("movies", JSONArray().put(item))
        } else {
            payload.put("shows", JSONArray().put(item))
        }

        val path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        return traktPost(path, payload)
    }

    private suspend fun syncSimklWatchlist(request: NormalizedContentRequest, inWatchlist: Boolean): Boolean {
        val imdbId = request.remoteImdbId ?: return false
        if (simklClientId.isBlank()) return false
        if (simklAccessToken().isEmpty()) return false

        val key = if (request.contentType == MetadataLabMediaType.MOVIE) "movies" else "shows"
        val payload = JSONObject()
        val item = JSONObject().put("ids", JSONObject().put("imdb", imdbId))
        if (inWatchlist) {
            item.put("to", "plantowatch")
            payload.put(key, JSONArray().put(item))
            return simklPost("/sync/add-to-list", payload)
        }

        payload.put(key, JSONArray().put(item))
        return simklPost("/sync/remove-from-list", payload)
    }

    private suspend fun syncSimklRating(request: NormalizedContentRequest, rating: Int?): Boolean {
        val value = rating ?: return false
        val imdbId = request.remoteImdbId ?: return false
        if (simklClientId.isBlank()) return false
        if (simklAccessToken().isEmpty()) return false

        val key = if (request.contentType == MetadataLabMediaType.MOVIE) "movies" else "shows"
        val payload = JSONObject()
        val item =
            JSONObject()
                .put("ids", JSONObject().put("imdb", imdbId))
                .put("rating", value.coerceIn(1, 10))
        payload.put(key, JSONArray().put(item))
        return simklPost("/sync/ratings", payload)
    }

    private suspend fun invalidateProviderLibraryCache(provider: WatchProvider) {
        withContext(Dispatchers.IO) {
            runCatching { providerLibraryCacheFile(provider).delete() }
        }
    }

    override suspend fun listContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?
    ): ContinueWatchingLabResult {
        val targetLimit = limit.coerceAtLeast(1)
        Log.d(TAG, "listContinueWatching: source=$source, limit=$targetLimit")

        if (source != null) {
            if (source == WatchProvider.LOCAL) {
                val local = localContinueWatchingFallback().take(targetLimit)
                Log.d(TAG, "listContinueWatching: LOCAL fallback returned ${local.size} entries")
                val status = if (local.isNotEmpty()) {
                    "Loaded ${local.size} local continue watching entries."
                } else {
                    "No local continue watching entries yet."
                }
                return ContinueWatchingLabResult(
                    statusMessage = status,
                    entries = local
                )
            }

            val entries =
                when (source) {
                    WatchProvider.TRAKT -> {
                        val hasToken = traktAccessToken().isNotEmpty()
                        val hasClientId = traktClientId.isNotBlank()
                        Log.d(TAG, "listContinueWatching: TRAKT hasToken=$hasToken, hasClientId=$hasClientId")
                        if (hasToken && hasClientId) {
                            try {
                                fetchTraktContinueWatching(nowMs)
                            } catch (error: Throwable) {
                                Log.w(TAG, "listContinueWatching: TRAKT fetch failed, falling back to cache", error)
                                val cached = getCachedContinueWatching(limit = targetLimit, nowMs = nowMs, source = WatchProvider.TRAKT)
                                return cached.copy(statusMessage = "Trakt temporarily unavailable. ${cached.statusMessage}")
                            }
                        } else {
                            Log.w(TAG, "listContinueWatching: TRAKT skipped — missing token or clientId")
                            emptyList()
                        }
                    }

                    WatchProvider.SIMKL -> {
                        val hasToken = simklAccessToken().isNotEmpty()
                        val hasClientId = simklClientId.isNotBlank()
                        Log.d(TAG, "listContinueWatching: SIMKL hasToken=$hasToken, hasClientId=$hasClientId")
                        if (hasToken && hasClientId) {
                            try {
                                fetchSimklContinueWatching(nowMs)
                            } catch (error: Throwable) {
                                Log.w(TAG, "listContinueWatching: SIMKL fetch failed, falling back to cache", error)
                                val cached = getCachedContinueWatching(limit = targetLimit, nowMs = nowMs, source = WatchProvider.SIMKL)
                                return cached.copy(statusMessage = "Simkl temporarily unavailable. ${cached.statusMessage}")
                            }
                        } else {
                            Log.w(TAG, "listContinueWatching: SIMKL skipped — missing token or clientId")
                            emptyList()
                        }
                    }

                    WatchProvider.LOCAL -> emptyList()
                }

            Log.d(TAG, "listContinueWatching: raw entries from $source: ${entries.size}")
            for ((i, e) in entries.withIndex()) {
                Log.d(TAG, "  raw[$i]: type=${e.contentType}, id=${e.contentId}, title=${e.title}, " +
                        "progress=${e.progressPercent}%, upNext=${e.isUpNextPlaceholder}, " +
                        "S${e.season}E${e.episode}")
            }

            val normalized = normalizeContinueWatching(entries = entries, nowMs = nowMs, limit = targetLimit)
            Log.d(TAG, "listContinueWatching: normalized entries: ${normalized.size}")
            for ((i, e) in normalized.withIndex()) {
                Log.d(TAG, "  norm[$i]: type=${e.contentType}, id=${e.contentId}, title=${e.title}, " +
                        "progress=${e.progressPercent}%, upNext=${e.isUpNextPlaceholder}, " +
                        "S${e.season}E${e.episode}")
            }

            val status =
                when (source) {
                    WatchProvider.TRAKT -> {
                        if (traktClientId.isBlank()) {
                            "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties."
                        } else if (traktAccessToken().isBlank()) {
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
                        } else if (simklAccessToken().isBlank()) {
                            "Connect Simkl to load continue watching."
                        } else if (normalized.isNotEmpty()) {
                            "Loaded ${normalized.size} Simkl continue watching entries."
                        } else {
                            "No Simkl continue watching entries available."
                        }
                    }

                    WatchProvider.LOCAL -> "Local source selected."
                }
            Log.d(TAG, "listContinueWatching: statusMessage=\"$status\"")

            val result = ContinueWatchingLabResult(
                statusMessage = status,
                entries = normalized
            )
            writeContinueWatchingCache(source, result)
            return result
        }

        Log.d(TAG, "listContinueWatching: no source specified, trying Trakt then Simkl then Local")
        val traktEntries =
            try {
                fetchTraktContinueWatching(nowMs)
            } catch (error: Throwable) {
                Log.w(TAG, "listContinueWatching: Trakt fetch failed", error)
                emptyList()
            }
        Log.d(TAG, "listContinueWatching: traktEntries=${traktEntries.size}")
        val simklEntries =
            if (traktEntries.isEmpty()) {
                try {
                    fetchSimklContinueWatching(nowMs)
                } catch (error: Throwable) {
                    Log.w(TAG, "listContinueWatching: Simkl fetch failed", error)
                    emptyList()
                }
            } else {
                emptyList()
            }
        Log.d(TAG, "listContinueWatching: simklEntries=${simklEntries.size}")
        if (traktEntries.isEmpty() && simklEntries.isEmpty()) {
            val cached = getCachedContinueWatching(limit = targetLimit, nowMs = nowMs, source = null)
            if (cached.entries.isNotEmpty()) {
                return cached
            }
            val local = localContinueWatchingFallback().take(targetLimit)
            Log.d(TAG, "listContinueWatching: both empty, local fallback=${local.size}")
            val status = if (local.isNotEmpty()) {
                "Loaded ${local.size} local continue watching entries."
            } else {
                "No continue watching entries yet."
            }
            return ContinueWatchingLabResult(
                statusMessage = status,
                entries = local
            )
        }

        val merged = normalizeContinueWatching(
            entries = traktEntries + simklEntries,
            nowMs = nowMs,
            limit = targetLimit
        )
        Log.d(TAG, "listContinueWatching: merged & normalized=${merged.size}")

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

    override suspend fun listProviderLibrary(
        limitPerFolder: Int,
        source: WatchProvider?
    ): ProviderLibrarySnapshot {
        Log.d(TAG, "listProviderLibrary: source=$source, limitPerFolder=$limitPerFolder")
        if (source != null) {
            val selected =
                when (source) {
                    WatchProvider.LOCAL -> {
                        Log.d(TAG, "listProviderLibrary: LOCAL source — no provider library")
                        null
                    }
                    WatchProvider.TRAKT -> {
                        val hasToken = traktAccessToken().isNotEmpty()
                        val hasClientId = traktClientId.isNotBlank()
                        Log.d(TAG, "listProviderLibrary: TRAKT hasToken=$hasToken, hasClientId=$hasClientId")
                        if (hasToken && hasClientId) {
                            try {
                                fetchTraktLibrary(limitPerFolder)
                            } catch (error: Throwable) {
                                Log.w(TAG, "listProviderLibrary: TRAKT fetch failed, falling back to cache", error)
                                val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = WatchProvider.TRAKT)
                                return cached.copy(statusMessage = "Trakt temporarily unavailable. ${cached.statusMessage}")
                            }
                        } else {
                            Log.w(TAG, "listProviderLibrary: TRAKT skipped — missing token or clientId")
                            emptyList<ProviderLibraryFolder>() to emptyList()
                        }
                    }

                    WatchProvider.SIMKL -> {
                        val hasToken = simklAccessToken().isNotEmpty()
                        val hasClientId = simklClientId.isNotBlank()
                        Log.d(TAG, "listProviderLibrary: SIMKL hasToken=$hasToken, hasClientId=$hasClientId")
                        if (hasToken && hasClientId) {
                            try {
                                fetchSimklLibrary(limitPerFolder)
                            } catch (error: Throwable) {
                                Log.w(TAG, "listProviderLibrary: SIMKL fetch failed, falling back to cache", error)
                                val cached = getCachedProviderLibrary(limitPerFolder = limitPerFolder, source = WatchProvider.SIMKL)
                                return cached.copy(statusMessage = "Simkl temporarily unavailable. ${cached.statusMessage}")
                            }
                        } else {
                            Log.w(TAG, "listProviderLibrary: SIMKL skipped — missing token or clientId")
                            emptyList<ProviderLibraryFolder>() to emptyList()
                        }
                    }
                }

            val status =
                when (source) {
                    WatchProvider.LOCAL -> "Local source selected. Provider library unavailable."
                    WatchProvider.TRAKT -> {
                        if (traktClientId.isBlank()) {
                            "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties."
                        } else if (traktAccessToken().isBlank()) {
                            "Connect Trakt to load provider library."
                        } else if (selected != null && selected.first.isNotEmpty()) {
                            "Loaded ${selected.first.size} Trakt folders."
                        } else {
                            "No Trakt library data available."
                        }
                    }

                    WatchProvider.SIMKL -> {
                        if (simklClientId.isBlank()) {
                            "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties."
                        } else if (simklAccessToken().isBlank()) {
                            "Connect Simkl to load provider library."
                        } else if (selected != null && selected.first.isNotEmpty()) {
                            "Loaded ${selected.first.size} Simkl folders."
                        } else {
                            "No Simkl library data available."
                        }
                    }
                }

            Log.d(TAG, "listProviderLibrary: status='$status', folders=${selected?.first?.size ?: 0}, items=${selected?.second?.size ?: 0}")
            if (selected != null) {
                for (f in selected.first) {
                    Log.d(TAG, "  folder: id=${f.id}, label=${f.label}, count=${f.itemCount}")
                }
            }

            val snapshot = ProviderLibrarySnapshot(
                statusMessage = status,
                folders = selected?.first.orEmpty().sortedBy { it.label.lowercase(Locale.US) },
                items = selected?.second.orEmpty().sortedByDescending { it.addedAtEpochMs }
            )
            if (source == WatchProvider.TRAKT || source == WatchProvider.SIMKL) {
                writeProviderLibraryCache(source, snapshot)
            }
            return snapshot
        }

        val folders = mutableListOf<ProviderLibraryFolder>()
        val items = mutableListOf<ProviderLibraryItem>()

        if (traktAccessToken().isNotEmpty() && traktClientId.isNotBlank()) {
            try {
                val trakt = fetchTraktLibrary(limitPerFolder)
                folders += trakt.first
                items += trakt.second
            } catch (error: Throwable) {
                Log.w(TAG, "listProviderLibrary: Trakt fetch failed", error)
            }
        }

        if (simklAccessToken().isNotEmpty() && simklClientId.isNotBlank()) {
            try {
                val simkl = fetchSimklLibrary(limitPerFolder)
                folders += simkl.first
                items += simkl.second
            } catch (error: Throwable) {
                Log.w(TAG, "listProviderLibrary: Simkl fetch failed", error)
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
            items = items.sortedByDescending { it.addedAtEpochMs }
        )
    }

    override suspend fun listProviderRecommendations(
        limit: Int,
        source: WatchProvider?
    ): ProviderRecommendationsResult {
        val targetLimit = limit.coerceAtLeast(1)
        if (source == WatchProvider.LOCAL) {
            return ProviderRecommendationsResult(statusMessage = "Local source selected. Recommendations unavailable.")
        }

        return when (source) {
            WatchProvider.TRAKT -> loadTraktRecommendations(limit = targetLimit)
            WatchProvider.SIMKL -> loadSimklRecommendations(limit = targetLimit)
            null -> {
                val traktResult = loadTraktRecommendations(limit = targetLimit)
                if (traktResult.items.isNotEmpty()) {
                    traktResult
                } else {
                    val simklResult = loadSimklRecommendations(limit = targetLimit)
                    if (simklResult.items.isNotEmpty()) {
                        simklResult
                    } else {
                        ProviderRecommendationsResult(
                            statusMessage = when {
                                traktResult.statusMessage.contains("Connect", ignoreCase = true) &&
                                    simklResult.statusMessage.contains("Connect", ignoreCase = true) -> {
                                    "Connect Trakt or Simkl to load For You recommendations."
                                }

                                simklResult.statusMessage.isNotBlank() -> simklResult.statusMessage
                                else -> traktResult.statusMessage
                            }
                        )
                    }
                }
            }

            else -> ProviderRecommendationsResult(statusMessage = "Recommendations unavailable.")
        }
    }

    private suspend fun loadTraktRecommendations(limit: Int): ProviderRecommendationsResult {
        if (traktClientId.isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties.")
        }
        if (traktAccessToken().isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Connect Trakt to load For You recommendations.")
        }

        return try {
            val items = fetchTraktRecommendationsMixed(limit = limit)
            ProviderRecommendationsResult(
                statusMessage = if (items.isEmpty()) "No Trakt recommendations available." else "Loaded ${items.size} Trakt recommendations.",
                items = items
            )
        } catch (error: Throwable) {
            Log.w(TAG, "listProviderRecommendations: Trakt fetch failed", error)
            ProviderRecommendationsResult(statusMessage = "Trakt recommendations are temporarily unavailable.")
        }
    }

    private suspend fun loadSimklRecommendations(limit: Int): ProviderRecommendationsResult {
        if (simklClientId.isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties.")
        }
        if (simklAccessToken().isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Connect Simkl to load For You recommendations.")
        }

        return try {
            val items = fetchSimklRecommendationsMixed(limit = limit)
            ProviderRecommendationsResult(
                statusMessage = if (items.isEmpty()) "No Simkl recommendations available." else "Loaded ${items.size} Simkl recommendations.",
                items = items
            )
        } catch (error: Throwable) {
            Log.w(TAG, "listProviderRecommendations: Simkl fetch failed", error)
            ProviderRecommendationsResult(statusMessage = "Simkl recommendations are temporarily unavailable.")
        }
    }

    override suspend fun getCachedContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?
    ): ContinueWatchingLabResult {
        val targetLimit = limit.coerceAtLeast(1)

        if (source == WatchProvider.LOCAL) {
            val local = localContinueWatchingFallback().take(targetLimit)
            val status = if (local.isNotEmpty()) "Loaded ${local.size} local continue watching entries." else "No continue watching entries yet."
            return ContinueWatchingLabResult(statusMessage = status, entries = local)
        }

        val providers =
            when (source) {
                WatchProvider.TRAKT -> listOf(WatchProvider.TRAKT)
                WatchProvider.SIMKL -> listOf(WatchProvider.SIMKL)
                null -> listOf(WatchProvider.TRAKT, WatchProvider.SIMKL)
                else -> emptyList()
            }

        val mergedEntries = mutableListOf<ContinueWatchingEntry>()
        val statusParts = mutableListOf<String>()
        for (provider in providers) {
            val cached = readContinueWatchingCache(provider) ?: continue
            val age = formatCacheAge(nowMs = nowMs, updatedAtEpochMs = cached.updatedAtEpochMs)
            val count = cached.value.entries.size
            statusParts += "${provider.name.lowercase(Locale.US)}=$count (${age} old)"
            mergedEntries += cached.value.entries
        }

        if (mergedEntries.isEmpty()) {
            val label =
                when (source) {
                    WatchProvider.TRAKT -> "Trakt"
                    WatchProvider.SIMKL -> "Simkl"
                    else -> "provider"
                }
            return ContinueWatchingLabResult(statusMessage = "No cached $label continue watching entries.")
        }

        val normalized = normalizeContinueWatching(entries = mergedEntries, nowMs = nowMs, limit = targetLimit)
        val status = "Loaded cached continue watching (${statusParts.joinToString(", ")})."
        return ContinueWatchingLabResult(statusMessage = status, entries = normalized)
    }

    override suspend fun getCachedProviderLibrary(
        limitPerFolder: Int,
        source: WatchProvider?
    ): ProviderLibrarySnapshot {
        if (source == WatchProvider.LOCAL) {
            return ProviderLibrarySnapshot(statusMessage = "Local source selected. Provider library unavailable.")
        }

        fun applyLimit(snapshot: ProviderLibrarySnapshot): ProviderLibrarySnapshot {
            val targetLimit = limitPerFolder.coerceAtLeast(1)
            val byKey = snapshot.items.groupBy { "${it.provider.name}:${it.folderId}" }
            val limitedFolders = mutableListOf<ProviderLibraryFolder>()
            val limitedItems = mutableListOf<ProviderLibraryItem>()
            for (folder in snapshot.folders) {
                val key = "${folder.provider.name}:${folder.id}"
                val items = byKey[key].orEmpty().sortedByDescending { it.addedAtEpochMs }.take(targetLimit)
                limitedItems += items
                limitedFolders += folder.copy(itemCount = items.size)
            }
            return snapshot.copy(
                folders = limitedFolders,
                items = limitedItems,
            )
        }

        val providers =
            when (source) {
                WatchProvider.TRAKT -> listOf(WatchProvider.TRAKT)
                WatchProvider.SIMKL -> listOf(WatchProvider.SIMKL)
                null -> listOf(WatchProvider.TRAKT, WatchProvider.SIMKL)
                else -> emptyList()
            }

        val mergedFolders = mutableListOf<ProviderLibraryFolder>()
        val mergedItems = mutableListOf<ProviderLibraryItem>()
        val statusParts = mutableListOf<String>()
        val nowMs = System.currentTimeMillis()

        for (provider in providers) {
            val cached = readProviderLibraryCache(provider) ?: continue
            val age = formatCacheAge(nowMs = nowMs, updatedAtEpochMs = cached.updatedAtEpochMs)
            statusParts += "${provider.name.lowercase(Locale.US)}=${cached.value.folders.size} folders (${age} old)"
            mergedFolders += cached.value.folders
            mergedItems += cached.value.items
        }

        if (mergedFolders.isEmpty() && mergedItems.isEmpty()) {
            val label =
                when (source) {
                    WatchProvider.TRAKT -> "Trakt"
                    WatchProvider.SIMKL -> "Simkl"
                    else -> "provider"
                }
            return ProviderLibrarySnapshot(statusMessage = "No cached $label library data available.")
        }

        val limited = applyLimit(
            ProviderLibrarySnapshot(
                statusMessage = "Loaded cached provider library (${statusParts.joinToString(", ")}).",
                folders = mergedFolders.sortedBy { it.label.lowercase(Locale.US) },
                items = mergedItems.sortedByDescending { it.addedAtEpochMs },
            )
        )

        return limited.copy(
            folders = limited.folders.sortedBy { it.label.lowercase(Locale.US) },
            items = limited.items.sortedByDescending { it.addedAtEpochMs },
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
        Log.d(TAG, "normalizeContinueWatching: input=${entries.size}, limit=$limit")
        if (entries.isEmpty()) {
            Log.d(TAG, "normalizeContinueWatching: empty input, returning empty")
            return emptyList()
        }

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val candidates =
            entries
                .asSequence()
                .map { entry ->
                    val progress = entry.progressPercent.coerceIn(0.0, 100.0)
                    entry.copy(progressPercent = progress)
                }
                .filter { entry ->
                    val keep = entry.lastUpdatedEpochMs >= staleCutoff
                    if (!keep) Log.d(TAG, "normalizeContinueWatching: STALE — ${entry.title} (${entry.contentId}), lastUpdated=${entry.lastUpdatedEpochMs}, cutoff=$staleCutoff")
                    keep
                }
                .filter { entry ->
                    val keep = if (entry.isUpNextPlaceholder) {
                        entry.progressPercent <= 0.0
                    } else {
                        entry.progressPercent >= CONTINUE_WATCHING_MIN_PROGRESS_PERCENT &&
                            entry.progressPercent < CONTINUE_WATCHING_COMPLETION_PERCENT
                    }
                    if (!keep) Log.d(TAG, "normalizeContinueWatching: PROGRESS_FILTER — ${entry.title} (${entry.contentId}), progress=${entry.progressPercent}%, upNext=${entry.isUpNextPlaceholder}")
                    keep
                }
                .toList()
        Log.d(TAG, "normalizeContinueWatching: after stale+progress filter: ${candidates.size}")

        val byContent = linkedMapOf<String, ContinueWatchingEntry>()
        candidates.forEach { entry ->
            val key = "${entry.contentType.name}:${entry.contentId}".lowercase(Locale.US)
            val current = byContent[key]
            if (current == null) {
                byContent[key] = entry
                return@forEach
            }
            Log.d(TAG, "normalizeContinueWatching: DEDUP key=$key — picking preferred between existing and new entry")
            byContent[key] = choosePreferredContinueWatching(current, entry)
        }

        Log.d(TAG, "normalizeContinueWatching: after dedup: ${byContent.size}")

        val sorted = byContent.values
            .sortedWith(
                compareByDescending<ContinueWatchingEntry> { it.progressPercent > 0.0 }
                    .thenByDescending { it.lastUpdatedEpochMs }
            )
            .take(limit)

        Log.d(TAG, "normalizeContinueWatching: final output: ${sorted.size} items")
        for ((i, e) in sorted.withIndex()) {
            Log.d(TAG, "  final[$i]: ${e.title} (${e.contentId}), progress=${e.progressPercent}%, upNext=${e.isUpNextPlaceholder}, S${e.season}E${e.episode}")
        }
        return sorted
    }

    private fun choosePreferredContinueWatching(current: ContinueWatchingEntry, incoming: ContinueWatchingEntry): ContinueWatchingEntry {
        val sameEpisode =
            current.contentType == MetadataLabMediaType.MOVIE ||
                (current.season == incoming.season &&
                    current.episode == incoming.episode &&
                    current.isUpNextPlaceholder == incoming.isUpNextPlaceholder)

        if (sameEpisode) {
            val delta = 0.5
            return when {
                incoming.progressPercent > current.progressPercent + delta -> incoming
                current.progressPercent > incoming.progressPercent + delta -> current
                incoming.lastUpdatedEpochMs >= current.lastUpdatedEpochMs -> incoming
                else -> current
            }
        }

        return when {
            incoming.lastUpdatedEpochMs > current.lastUpdatedEpochMs -> incoming
            incoming.lastUpdatedEpochMs < current.lastUpdatedEpochMs -> current
            incoming.progressPercent > current.progressPercent -> incoming
            else -> current
        }
    }

    private data class TraktNextEpisode(
        val season: Int,
        val episode: Int,
        val title: String?
    )

    private suspend fun fetchTraktContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        Log.d(TAG, "fetchTraktContinueWatching: starting, nowMs=$nowMs")
        val payload = traktGetArray("/sync/playback")
        if (payload == null) {
            throw IllegalStateException("Trakt /sync/playback returned null")
        }
        Log.d(TAG, "fetchTraktContinueWatching: /sync/playback returned ${payload.length()} items")

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        Log.d(TAG, "fetchTraktContinueWatching: staleCutoff=${java.util.Date(staleCutoff)}")
        val nextEpisodeCache = mutableMapOf<String, TraktNextEpisode?>()

        fun normalizedImdbId(raw: String): String {
            val id = raw.trim()
            if (id.isEmpty()) {
                return ""
            }
            val normalized = if (id.startsWith("tt", ignoreCase = true)) id else "tt$id"
            return normalized.lowercase(Locale.US)
        }

        fun normalizedContentIdFromIds(ids: JSONObject?): String {
            val imdbId = normalizedImdbId(ids?.optString("imdb")?.trim().orEmpty())
            if (imdbId.isNotEmpty()) return imdbId

            val tmdbId = ids?.optInt("tmdb", 0) ?: 0
            if (tmdbId > 0) return "tmdb:$tmdbId"

            return ""
        }

        suspend fun nextEpisodeForShow(showTraktId: String): TraktNextEpisode? {
            val id = showTraktId.trim()
            if (id.isEmpty()) {
                return null
            }
            if (nextEpisodeCache.containsKey(id)) {
                return nextEpisodeCache[id]
            }

            val endpoint = "/shows/$id/progress/watched?hidden=true&specials=false&count_specials=false"
            val obj = traktGetObject(endpoint)
            val next = obj?.optJSONObject("next_episode")
            val season = next?.optInt("season", 0) ?: 0
            val number = next?.optInt("number", 0) ?: 0

            val result =
                if (season <= 0 || number <= 0) {
                    null
                } else {
                    TraktNextEpisode(
                        season = season,
                        episode = number,
                        title = next?.optString("title")?.trim()?.ifEmpty { null },
                    )
                }

            nextEpisodeCache[id] = result
            return result
        }

        val playbackItems =
            buildList<Pair<Long, JSONObject>> {
                for (index in 0 until payload.length()) {
                    val obj = payload.optJSONObject(index) ?: continue
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(pausedAt to obj)
                }
            }
                .sortedByDescending { (pausedAt, _) -> pausedAt }
                .take(CONTINUE_WATCHING_PLAYBACK_LIMIT)
        Log.d(TAG, "fetchTraktContinueWatching: playbackItems count=${playbackItems.size}")

        val existingSeriesTraktIds = mutableSetOf<String>()
        val playbackEntries = mutableListOf<ContinueWatchingEntry>()

        for ((pausedAt, obj) in playbackItems) {
                    val type = obj.optString("type").trim().lowercase(Locale.US)
                    val progress = obj.optDouble("progress", -1.0)
                    val rawTitle = when (type) {
                        "movie" -> obj.optJSONObject("movie")?.optString("title")
                        "episode" -> obj.optJSONObject("show")?.optString("title")
                        else -> null
                    } ?: "?"
                    if (progress < 0) {
                        Log.d(TAG, "  playback skip: type=$type title=$rawTitle — invalid progress ($progress)")
                        continue
                    }
                    if (pausedAt < staleCutoff) {
                        Log.d(TAG, "  playback skip: type=$type title=$rawTitle — stale (paused ${java.util.Date(pausedAt)})")
                        continue
                    }
                    if (progress < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) {
                        Log.d(TAG, "  playback skip: type=$type title=$rawTitle — progress too low ($progress%)")
                        continue
                    }

                    if (type == "movie") {
                        if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                            Log.d(TAG, "  playback skip: movie title=$rawTitle — completed ($progress%)")
                            continue
                        }

                        val movie = obj.optJSONObject("movie") ?: continue
                        val contentId = normalizedContentIdFromIds(movie.optJSONObject("ids"))
                        if (contentId.isEmpty()) {
                            Log.d(TAG, "  playback skip: movie title=$rawTitle — missing id (imdb/tmdb)")
                            continue
                        }
                        val title = movie.optString("title").trim().ifEmpty { contentId }
                        Log.d(TAG, "  playback ADD movie: id=$contentId title=$title progress=$progress%")
                        playbackEntries.add(
                            ContinueWatchingEntry(
                                contentId = contentId,
                                contentType = MetadataLabMediaType.MOVIE,
                                title = title,
                                season = null,
                                episode = null,
                                progressPercent = progress,
                                lastUpdatedEpochMs = pausedAt,
                                provider = WatchProvider.TRAKT,
                                providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                                isUpNextPlaceholder = false
                            )
                        )
                        continue
                    }

                    if (type == "episode") {
                        val episode = obj.optJSONObject("episode") ?: continue
                        val show = obj.optJSONObject("show") ?: continue
                        val ids = show.optJSONObject("ids")
                        val contentId = normalizedContentIdFromIds(ids)
                        if (contentId.isEmpty()) {
                            Log.d(TAG, "  playback skip: episode show=$rawTitle — missing id (imdb/tmdb)")
                            continue
                        }

                        val showTraktId = ids?.opt("trakt")?.toString()?.trim().orEmpty()
                        if (showTraktId.isNotEmpty()) {
                            existingSeriesTraktIds.add(showTraktId)
                        }
                        val episodeSeason = episode.optInt("season", 0).takeIf { it > 0 }
                        val episodeNumber = episode.optInt("number", 0).takeIf { it > 0 }
                        val showTitle = show.optString("title").trim().ifEmpty { contentId }
                        val episodeTitle = episode.optString("title").trim()
                        val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"

                        if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                            Log.d(TAG, "  playback episode completed: show=$showTitle traktId=$showTraktId S${episodeSeason}E${episodeNumber} ($progress%) — looking up next episode")
                            if (showTraktId.isBlank()) {
                                Log.d(TAG, "  playback skip: show=$showTitle — missing trakt id for next-episode lookup")
                                continue
                            }
                            val next = nextEpisodeForShow(showTraktId)
                            if (next == null) {
                                Log.d(TAG, "  playback skip: show=$showTitle — no next episode from Trakt progress API")
                                continue
                            }
                            Log.d(TAG, "  playback ADD upNext: show=$showTitle S${next.season}E${next.episode} (next=${next.title})")
                            playbackEntries.add(
                                ContinueWatchingEntry(
                                    contentId = contentId,
                                    contentType = MetadataLabMediaType.SERIES,
                                    title = showTitle,
                                    season = next.season,
                                    episode = next.episode,
                                    progressPercent = 0.0,
                                    lastUpdatedEpochMs = pausedAt,
                                    provider = WatchProvider.TRAKT,
                                    providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                                    isUpNextPlaceholder = true
                                )
                            )
                            continue
                        }

                        Log.d(TAG, "  playback ADD episode in-progress: show=$showTitle S${episodeSeason}E${episodeNumber} progress=$progress%")
                        playbackEntries.add(
                            ContinueWatchingEntry(
                                contentId = contentId,
                                contentType = MetadataLabMediaType.SERIES,
                                title = title,
                                season = episodeSeason,
                                episode = episodeNumber,
                                progressPercent = progress,
                                lastUpdatedEpochMs = pausedAt,
                                provider = WatchProvider.TRAKT,
                                providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                                isUpNextPlaceholder = false
                            )
                        )
                    }
        }
        Log.d(TAG, "fetchTraktContinueWatching: playbackEntries=${playbackEntries.size} (movies=${playbackEntries.count { it.contentType == MetadataLabMediaType.MOVIE }}, series=${playbackEntries.count { it.contentType == MetadataLabMediaType.SERIES }}, upNext=${playbackEntries.count { it.isUpNextPlaceholder }})")

        val existingSeriesIds =
            playbackEntries
                .asSequence()
                .filter { it.contentType == MetadataLabMediaType.SERIES }
                .map { it.contentId.lowercase(Locale.US) }
                .toSet()
        Log.d(TAG, "fetchTraktContinueWatching: existingSeriesIds=$existingSeriesIds")

        Log.d(TAG, "fetchTraktContinueWatching: fetching /sync/watched/shows for additional up-next")
        val watchedShows = traktGetArray("/sync/watched/shows")
        if (watchedShows == null) {
            Log.w(TAG, "fetchTraktContinueWatching: /sync/watched/shows returned null")
            return playbackEntries
        }
        Log.d(TAG, "fetchTraktContinueWatching: /sync/watched/shows returned ${watchedShows.length()} shows")

        data class WatchedShowCandidate(
            val contentId: String,
            val traktId: String,
            val title: String,
            val lastWatchedAtMs: Long,
        )

        val candidateBuffer = mutableListOf<WatchedShowCandidate>()
        for (index in 0 until watchedShows.length()) {
            val obj = watchedShows.optJSONObject(index) ?: continue
            val lastWatchedAt = parseIsoToEpochMs(obj.optString("last_watched_at")) ?: continue
            if (lastWatchedAt < staleCutoff) {
                continue
            }

            val show = obj.optJSONObject("show") ?: continue
            val ids = show.optJSONObject("ids")
            val contentId = normalizedContentIdFromIds(ids)
            if (contentId.isEmpty()) continue
            if (contentId.lowercase(Locale.US) in existingSeriesIds) {
                Log.d(TAG, "  watched-show skip: ${show.optString("title")} ($contentId) — already in playback entries")
                continue
            }

            val traktId = ids?.opt("trakt")?.toString()?.trim().orEmpty()
            if (traktId.isEmpty()) continue
            if (existingSeriesTraktIds.contains(traktId)) {
                Log.d(TAG, "  watched-show skip: ${show.optString("title")} (trakt=$traktId) — already in playback entries")
                continue
            }

            val title = show.optString("title").trim().ifEmpty { contentId }
            Log.d(TAG, "  watched-show candidate: title=$title id=$contentId trakt=$traktId lastWatched=${java.util.Date(lastWatchedAt)}")
            candidateBuffer.add(
                WatchedShowCandidate(
                    contentId = contentId,
                    traktId = traktId,
                    title = title,
                    lastWatchedAtMs = lastWatchedAt,
                )
            )
        }

        val candidates =
            candidateBuffer
                .sortedByDescending { it.lastWatchedAtMs }
                .distinctBy { it.contentId.lowercase(Locale.US) }
                .take(CONTINUE_WATCHING_UPNEXT_SHOW_LIMIT)
        Log.d(TAG, "fetchTraktContinueWatching: watched-show candidates=${candidates.size}")

        val upNextFromWatchedShows = mutableListOf<ContinueWatchingEntry>()
        for (candidate in candidates) {
            Log.d(TAG, "  watched-show nextEpisode lookup: title=${candidate.title} trakt=${candidate.traktId}")
            val next = nextEpisodeForShow(candidate.traktId)
            if (next == null) {
                Log.d(TAG, "  watched-show skip: ${candidate.title} — no next episode")
                continue
            }
            Log.d(TAG, "  watched-show ADD upNext: ${candidate.title} S${next.season}E${next.episode} (${next.title})")
            upNextFromWatchedShows.add(
                ContinueWatchingEntry(
                    contentId = candidate.contentId,
                    contentType = MetadataLabMediaType.SERIES,
                    title = candidate.title,
                    season = next.season,
                    episode = next.episode,
                    progressPercent = 0.0,
                    lastUpdatedEpochMs = candidate.lastWatchedAtMs,
                    provider = WatchProvider.TRAKT,
                    providerPlaybackId = null,
                    isUpNextPlaceholder = true,
                )
            )
        }
        Log.d(TAG, "fetchTraktContinueWatching: upNextFromWatchedShows=${upNextFromWatchedShows.size}")
        Log.d(TAG, "fetchTraktContinueWatching: TOTAL returning ${playbackEntries.size + upNextFromWatchedShows.size} entries (playback=${playbackEntries.size}, upNext=${upNextFromWatchedShows.size})")

        return playbackEntries + upNextFromWatchedShows
    }

    private suspend fun fetchSimklContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        fun normalizedImdbId(raw: String): String {
            val cleaned = raw.trim().lowercase(Locale.US)
            if (cleaned.isEmpty()) return ""
            return if (cleaned.startsWith("tt")) cleaned else "tt$cleaned"
        }

        fun normalizedContentIdFromIds(ids: JSONObject?): String {
            val imdbId = normalizedImdbId(ids?.optString("imdb")?.trim().orEmpty())
            if (imdbId.isNotEmpty()) return imdbId

            val tmdbAny = ids?.opt("tmdb")
            val tmdbId =
                when (tmdbAny) {
                    is Number -> tmdbAny.toInt()
                    is String -> tmdbAny.toIntOrNull() ?: 0
                    else -> 0
                }
            if (tmdbId > 0) return "tmdb:$tmdbId"

            return ""
        }

        val payload = simklGetAny("/sync/playback") ?: throw IllegalStateException("Simkl /sync/playback returned null")
        val array = payload.toJsonArrayOrNull() ?: throw IllegalStateException("Simkl /sync/playback returned non-array")
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = obj.optString("type").trim().lowercase(Locale.US)
                val progress = obj.optDouble("progress", -1.0)
                if (progress < 0) continue

                if (type == "movie") {
                    val movie = obj.optJSONObject("movie") ?: continue
                    val ids = movie.optJSONObject("ids")
                    val contentId = normalizedContentIdFromIds(ids)
                    if (contentId.isEmpty()) continue
                    val title = movie.optString("title").trim().ifEmpty { contentId }
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(
                        ContinueWatchingEntry(
                            contentId = contentId,
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
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue

                val showTitle = show?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                val season = episode?.optInt("season", 0)?.takeIf { it > 0 }
                val number =
                    (episode?.optInt("episode", 0)?.takeIf { it > 0 }
                        ?: episode?.optInt("number", 0)?.takeIf { it > 0 })
                val episodeTitle = episode?.optString("title")?.trim().orEmpty()
                val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"
                val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                add(
                    ContinueWatchingEntry(
                        contentId = contentId,
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

    private suspend fun fetchTraktLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        Log.d(TAG, "fetchTraktLibrary: starting with limitPerFolder=$limitPerFolder")
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        val cwEntries = fetchTraktContinueWatching(System.currentTimeMillis())
        Log.d(TAG, "fetchTraktLibrary: continue-watching entries from fetchTraktContinueWatching=${cwEntries.size}")
        addTraktFolder(folderItems, "continue-watching", cwEntries.map {
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
        Log.d(TAG, "fetchTraktLibrary: continue-watching folder size=${folderItems["continue-watching"]?.size ?: 0}")

        val watchedItems = traktHistoryItems()
        Log.d(TAG, "fetchTraktLibrary: watched items from traktHistoryItems=${watchedItems.size}")
        addTraktFolder(folderItems, "watched", watchedItems, limitPerFolder)

        val watchlistItems = traktWatchlistItems()
        Log.d(TAG, "fetchTraktLibrary: watchlist items from traktWatchlistItems=${watchlistItems.size}")
        addTraktFolder(folderItems, "watchlist", watchlistItems, limitPerFolder)

        val collectionItems = traktCollectionItems()
        Log.d(TAG, "fetchTraktLibrary: collection items from traktCollectionItems=${collectionItems.size}")
        addTraktFolder(folderItems, "collection", collectionItems, limitPerFolder)

        val ratingsItems = traktRatingsItems()
        Log.d(TAG, "fetchTraktLibrary: ratings items from traktRatingsItems=${ratingsItems.size}")
        addTraktFolder(folderItems, "ratings", ratingsItems, limitPerFolder)

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
        Log.d(TAG, "fetchTraktLibrary: returning ${folders.size} non-empty folders, ${folderItems.values.flatten().size} total items")
        folders.forEach { f -> Log.d(TAG, "fetchTraktLibrary:   folder=${f.id} count=${f.itemCount}") }
        return folders to folderItems.values.flatten()
    }

    private suspend fun fetchSimklLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
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
        for (status in statuses) {
            for ((type, contentType) in types) {
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
        if (values.isEmpty()) {
            Log.d(TAG, "addTraktFolder($id): skipped (empty values)")
            return
        }
        val capped = values.take(limit.coerceAtLeast(1))
        val folderId = id
        bucket.getOrPut(folderId) { mutableListOf() }.addAll(capped.map { it.copy(folderId = folderId) })
        Log.d(TAG, "addTraktFolder($id): added ${capped.size}/${values.size} items (limit=$limit)")
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

    private suspend fun traktHistoryItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watched/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watched/shows") ?: JSONArray()
        Log.d(TAG, "traktHistoryItems: movies=${movies.length()}, shows=${shows.length()}")
        val result = parseTraktItemsFromWatched(movies, MetadataLabMediaType.MOVIE, "watched") +
            parseTraktItemsFromWatched(shows, MetadataLabMediaType.SERIES, "watched")
        Log.d(TAG, "traktHistoryItems: parsed ${result.size} total items")
        return result
    }

    private suspend fun traktWatchlistItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watchlist/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watchlist/shows") ?: JSONArray()
        Log.d(TAG, "traktWatchlistItems: movies=${movies.length()}, shows=${shows.length()}")
        val result = parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "watchlist") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "watchlist")
        Log.d(TAG, "traktWatchlistItems: parsed ${result.size} total items")
        return result
    }

    private suspend fun traktCollectionItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/collection/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/collection/shows") ?: JSONArray()
        Log.d(TAG, "traktCollectionItems: movies=${movies.length()}, shows=${shows.length()}")
        val result = parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "collection") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "collection")
        Log.d(TAG, "traktCollectionItems: parsed ${result.size} total items")
        return result
    }

    private suspend fun traktRatingsItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/ratings/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/ratings/shows") ?: JSONArray()
        Log.d(TAG, "traktRatingsItems: movies=${movies.length()}, shows=${shows.length()}")
        val result = parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "ratings") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "ratings")
        Log.d(TAG, "traktRatingsItems: parsed ${result.size} total items")
        return result
    }

    private suspend fun fetchTraktRecommendationsMixed(limit: Int): List<ProviderLibraryItem> {
        val movies = traktGetArray("/recommendations/movies?limit=$limit&extended=images") ?: JSONArray()
        val shows = traktGetArray("/recommendations/shows?limit=$limit&extended=images") ?: JSONArray()

        val movieItems = parseTraktRecommendationsArray(movies, MetadataLabMediaType.MOVIE)
        val showItems = parseTraktRecommendationsArray(shows, MetadataLabMediaType.SERIES)

        val merged = mutableListOf<ProviderLibraryItem>()
        val maxSize = maxOf(movieItems.size, showItems.size)
        for (index in 0 until maxSize) {
            movieItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
            showItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
        }
        return merged.take(limit)
    }

    private suspend fun fetchSimklRecommendationsMixed(limit: Int): List<ProviderLibraryItem> {
        val perType = (limit.coerceAtLeast(1) + 1) / 2
        val movieRefs = fetchSimklRandomRefs(type = "movie", limit = perType)
        val showRefs = fetchSimklRandomRefs(type = "tv", limit = perType)

        val movieItems = movieRefs.mapIndexed { index, ref ->
            resolveSimklRecommendationRef(ref = ref, contentType = MetadataLabMediaType.MOVIE, rank = index)
        }
        val showItems = showRefs.mapIndexed { index, ref ->
            resolveSimklRecommendationRef(ref = ref, contentType = MetadataLabMediaType.SERIES, rank = index)
        }

        val merged = mutableListOf<ProviderLibraryItem>()
        val maxSize = maxOf(movieItems.size, showItems.size)
        for (index in 0 until maxSize) {
            movieItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
            showItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
        }

        return merged.take(limit)
    }

    private suspend fun fetchSimklRandomRefs(type: String, limit: Int): List<SimklRandomRef> {
        val payload =
            simklGetAny(
                "/search/random/?service=simkl&type=$type&limit=${limit.coerceAtLeast(1)}"
            ) ?: return emptyList()
        return extractSimklRandomRefs(payload = payload, expectedType = type)
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(1))
    }

    private suspend fun resolveSimklRecommendationRef(
        ref: SimklRandomRef,
        contentType: MetadataLabMediaType,
        rank: Int
    ): ProviderLibraryItem {
        val endpoint = when (ref.type) {
            "movie", "movies" -> "/movies/${ref.id}?extended=full"
            "anime" -> "/anime/${ref.id}?extended=full"
            else -> "/tv/${ref.id}?extended=full"
        }
        val details = simklGetAny(endpoint).toJsonObjectOrNull()

        val ids = details?.optJSONObject("ids")
        val resolvedContentId = normalizedContentIdFromIds(ids)
        val contentId = resolvedContentId.ifEmpty { "simkl:${ref.id}" }
        val title = details?.optString("title")?.trim().orEmpty().ifBlank { ref.title }
        val addedAt =
            parseIsoToEpochMs(details?.optString("released"))
                ?: parseIsoToEpochMs(details?.optString("release_date"))
                ?: (System.currentTimeMillis() - rank)

        return ProviderLibraryItem(
            provider = WatchProvider.SIMKL,
            folderId = "for-you",
            contentId = contentId,
            contentType = contentType,
            title = title,
            addedAtEpochMs = addedAt
        )
    }

    private fun extractSimklRandomRefs(payload: Any, expectedType: String): List<SimklRandomRef> {
        val values = mutableListOf<SimklRandomRef>()

        fun fromJsonObject(obj: JSONObject): SimklRandomRef? {
            val idFromField = obj.optInt("id", 0).takeIf { it > 0 }
            val url = obj.optString("url").trim()
            val parsedFromUrl = parseSimklRefFromUrl(url)
            val id = idFromField ?: parsedFromUrl?.id ?: return null
            val type = parsedFromUrl?.type ?: expectedType
            val title = parsedFromUrl?.title ?: "Simkl #$id"
            return SimklRandomRef(id = id, type = type, title = title)
        }

        when (payload) {
            is JSONObject -> {
                fromJsonObject(payload)?.let(values::add)
            }

            is JSONArray -> {
                for (index in 0 until payload.length()) {
                    val node = payload.opt(index)
                    when (node) {
                        is JSONObject -> fromJsonObject(node)?.let(values::add)
                        is JSONArray -> {
                            for (inner in 0 until node.length()) {
                                val entry = node.optJSONObject(inner) ?: continue
                                fromJsonObject(entry)?.let(values::add)
                            }
                        }
                    }
                }
            }
        }

        return values
    }

    private fun parseSimklRefFromUrl(rawUrl: String): SimklRandomRef? {
        val pattern = Regex("""/([a-zA-Z]+)/([0-9]+)(?:/([a-zA-Z0-9\-]+))?""")
        val match = pattern.find(rawUrl) ?: return null
        val type = match.groupValues.getOrNull(1)?.trim()?.lowercase(Locale.US).orEmpty()
        val id = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val titleSlug = match.groupValues.getOrNull(3)?.trim().orEmpty()
        val title = titleSlug.replace('-', ' ').trim().ifEmpty { "Simkl #$id" }
        return SimklRandomRef(id = id, type = type, title = title)
    }

    private data class SimklRandomRef(
        val id: Int,
        val type: String,
        val title: String
    )

    private fun parseTraktRecommendationsArray(
        array: JSONArray,
        contentType: MetadataLabMediaType
    ): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val media = node.optJSONObject("movie")
                    ?: node.optJSONObject("show")
                    ?: node
                val contentId = normalizedContentIdFromIds(media.optJSONObject("ids"))
                if (contentId.isEmpty()) {
                    continue
                }
                val title = media.optString("title").trim().ifEmpty { contentId }
                val rankedAt = parseIsoToEpochMs(node.optString("listed_at"))
                    ?: parseIsoToEpochMs(node.optString("updated_at"))
                    ?: parseIsoToEpochMs(media.optString("listed_at"))
                    ?: parseIsoToEpochMs(media.optString("updated_at"))
                    ?: parseIsoToEpochMs(node.optString("released"))
                    ?: parseIsoToEpochMs(media.optString("released"))
                    ?: (System.currentTimeMillis() - index)
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = "for-you",
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = rankedAt
                    )
                )
            }
        }
    }

    private fun normalizedImdbIdForContent(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
        if (cleaned.isEmpty()) return ""
        return if (cleaned.startsWith("tt")) cleaned else "tt$cleaned"
    }

    private fun normalizedContentIdFromIds(ids: JSONObject?): String {
        val imdbId = normalizedImdbIdForContent(ids?.optString("imdb")?.trim().orEmpty())
        if (imdbId.isNotEmpty()) return imdbId

        val tmdbAny = ids?.opt("tmdb")
        val tmdbId =
            when (tmdbAny) {
                is Number -> tmdbAny.toInt()
                is String -> tmdbAny.toIntOrNull() ?: 0
                else -> 0
            }
        if (tmdbId > 0) return "tmdb:$tmdbId"

        return ""
    }

    private fun parseTraktItemsFromWatched(array: JSONArray, contentType: MetadataLabMediaType, folderId: String): List<ProviderLibraryItem> {
        var skippedNoId = 0
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = if (contentType == MetadataLabMediaType.MOVIE) obj.optJSONObject("movie") else obj.optJSONObject("show")
                val contentId = normalizedContentIdFromIds(node?.optJSONObject("ids"))
                if (contentId.isEmpty()) { skippedNoId++; continue }
                val title = node?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                val addedAt = parseIsoToEpochMs(obj.optString("last_watched_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }.also {
            if (skippedNoId > 0) Log.d(TAG, "parseTraktItemsFromWatched($folderId, $contentType): skipped $skippedNoId items with no supported id (imdb/tmdb)")
        }
    }

    private fun parseTraktItemsFromList(
        array: JSONArray,
        key: String,
        contentType: MetadataLabMediaType,
        folderId: String
    ): List<ProviderLibraryItem> {
        var skippedNoNode = 0
        var skippedNoId = 0
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = obj.optJSONObject(key) ?: run { skippedNoNode++; continue }
                val contentId = normalizedContentIdFromIds(node.optJSONObject("ids"))
                if (contentId.isEmpty()) { skippedNoId++; continue }
                val title = node.optString("title").trim().ifEmpty { contentId }
                val addedAt = parseIsoToEpochMs(obj.optString("listed_at"))
                    ?: parseIsoToEpochMs(obj.optString("rated_at"))
                    ?: parseIsoToEpochMs(obj.optString("collected_at"))
                    ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }.also {
            if (skippedNoNode > 0 || skippedNoId > 0) {
                Log.d(TAG, "parseTraktItemsFromList($folderId, $key): skipped noNode=$skippedNoNode, noId=$skippedNoId out of ${array.length()}")
            }
        }
    }

    private suspend fun parseSimklListItems(
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
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue

                val title =
                    item.optString("title").trim().ifEmpty {
                        item.optJSONObject("movie")?.optString("title")?.trim().orEmpty().ifBlank {
                            item.optJSONObject("show")?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                        }
                    }
                val addedAt = parseIsoToEpochMs(item.optString("last_watched_at"))
                    ?: parseIsoToEpochMs(item.optString("added_to_watchlist_at"))
                    ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt
                    )
                )
            }
        }
    }

    private suspend fun parseSimklRatingsItems(): List<ProviderLibraryItem> {
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
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue
                val title = item.optString("title").trim().ifEmpty { contentId }
                val ratedAt = parseIsoToEpochMs(item.optString("rated_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = "ratings",
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = ratedAt
                    )
                )
            }
        }
    }

    private suspend fun resolveTraktId(imdbId: String, tmdbId: Int?, traktType: String): String? {
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

    private fun traktHeaders(accessToken: String?): Headers {
        val builder = Headers.Builder()
            .add("trakt-api-version", "2")
            .add("trakt-api-key", traktClientId)
            .add("Accept", "application/json")
        if (!accessToken.isNullOrBlank()) {
            builder.add("Authorization", "Bearer $accessToken")
        }
        return builder.build()
    }

    private fun updateTraktTokens(accessToken: String, refreshToken: String?, expiresAtEpochMs: Long?) {
        prefs.edit().apply {
            putString(KEY_TRAKT_TOKEN, accessToken.trim())
            putString(KEY_TRAKT_REFRESH_TOKEN, refreshToken?.trim()?.ifBlank { null })
            if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
                putLong(KEY_TRAKT_EXPIRES_AT, expiresAtEpochMs)
            } else {
                remove(KEY_TRAKT_EXPIRES_AT)
            }
        }.apply()
    }

    private suspend fun refreshTraktToken(refreshToken: String): String? {
        if (traktClientId.isBlank() || traktClientSecret.isBlank() || traktRedirectUri.isBlank()) {
            Log.w(TAG, "Skipping Trakt token refresh: missing client credentials")
            return null
        }

        val response =
            postJsonForObject(
                url = TRAKT_TOKEN_URL,
                headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                payload =
                    JSONObject()
                        .put("refresh_token", refreshToken)
                        .put("client_id", traktClientId)
                        .put("client_secret", traktClientSecret)
                        .put("redirect_uri", traktRedirectUri)
                        .put("grant_type", "refresh_token"),
            ) ?: return null

        val accessToken = response.optString("access_token").trim()
        if (accessToken.isBlank()) {
            Log.w(TAG, "Trakt token refresh succeeded but access_token missing keys=${jsonKeysForLog(response)}")
            return null
        }
        val newRefreshToken = response.optString("refresh_token").trim().ifEmpty { refreshToken }
        val expiresInSeconds = response.optLong("expires_in", -1L).takeIf { it > 0L }
        val expiresAtMs = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
        updateTraktTokens(accessToken = accessToken, refreshToken = newRefreshToken, expiresAtEpochMs = expiresAtMs)
        return accessToken
    }

    private suspend fun ensureTraktAccessToken(forceRefresh: Boolean = false): String? {
        val token = traktAccessToken().trim()
        if (token.isBlank() || traktClientId.isBlank()) {
            return null
        }

        val refreshToken = prefs.getString(KEY_TRAKT_REFRESH_TOKEN, null)?.trim()?.ifBlank { null }
        val expiresAt = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
        val nowMs = System.currentTimeMillis()
        val shouldRefresh =
            forceRefresh ||
                (expiresAt != null && refreshToken != null && nowMs >= (expiresAt - 60_000L))
        if (!shouldRefresh || refreshToken == null) {
            return token
        }

        return traktRefreshMutex.withLock {
            val currentToken = traktAccessToken().trim()
            if (currentToken.isBlank()) return@withLock null
            val currentRefresh = prefs.getString(KEY_TRAKT_REFRESH_TOKEN, null)?.trim()?.ifBlank { null }
                ?: return@withLock currentToken
            val currentExpiresAt = prefs.getLong(KEY_TRAKT_EXPIRES_AT, -1L).takeIf { it > 0L }
            val refreshNow =
                forceRefresh ||
                    (currentExpiresAt != null && nowMs >= (currentExpiresAt - 60_000L))
            if (!refreshNow) {
                return@withLock currentToken
            }

            refreshTraktToken(currentRefresh) ?: currentToken
        }
    }

    private suspend fun traktSearchGetArray(traktType: String, idType: String, id: String): JSONArray? {
        val safeType = if (traktType == "movie") "movie" else "show"
        val endpoint = "/search/$safeType?id_type=$idType&id=$id"
        if (traktClientId.isBlank()) return null

        val token = ensureTraktAccessToken(forceRefresh = false)
        val url = "https://api.trakt.tv$endpoint"
        val response =
            runCatching { httpClient.get(url = url.toHttpUrl(), headers = traktHeaders(token)) }
                .onFailure { error ->
                    Log.w(TAG, "Trakt search GET failed: $url", error)
                }
                .getOrNull()
                ?: return null

        val body = response.body
        if (response.code !in 200..299) {
            Log.w(TAG, "Trakt search GET $url failed with ${response.code} body=${compactForLog(body)}")
            return null
        }
        if (body.isBlank()) {
            return null
        }
        return runCatching { JSONArray(body) }
            .onFailure { error ->
                Log.w(TAG, "Trakt search GET $url returned malformed JSON body=${compactForLog(body)}", error)
            }
            .getOrNull()
    }

    private suspend fun traktGetArray(path: String): JSONArray? {
        var token = ensureTraktAccessToken(forceRefresh = false)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "traktGetArray($path) skipped: missing token/clientId")
            return null
        }

        val url = "https://api.trakt.tv$path"
        Log.d(TAG, "traktGetArray($path) requesting…")

        fun parse(body: String): JSONArray? {
            if (body.isBlank()) return null
            return runCatching { JSONArray(body) }
                .onFailure { error ->
                    Log.w(TAG, "traktGetArray($path) malformed JSON body=${compactForLog(body)}", error)
                }
                .getOrNull()
        }

        var response =
            runCatching { httpClient.get(url = url.toHttpUrl(), headers = traktHeaders(token)) }
                .onFailure { error -> Log.w(TAG, "traktGetArray($path) GET failed", error) }
                .getOrNull()
                ?: return null

        if (response.code == 401) {
            val refreshed = ensureTraktAccessToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                token = refreshed
                response =
                    runCatching { httpClient.get(url = url.toHttpUrl(), headers = traktHeaders(token)) }
                        .onFailure { error -> Log.w(TAG, "traktGetArray($path) retry GET failed", error) }
                        .getOrNull()
                        ?: return null
            }
        }

        if (response.code !in 200..299) {
            Log.w(TAG, "traktGetArray($path) failed with ${response.code} body=${compactForLog(response.body)}")
            return null
        }

        val parsed = parse(response.body)
        Log.d(TAG, "traktGetArray($path) → ${parsed?.length() ?: "null"} items")
        return parsed
    }

    private suspend fun traktGetObject(path: String): JSONObject? {
        var token = ensureTraktAccessToken(forceRefresh = false)
        if (token.isNullOrBlank()) {
            Log.w(TAG, "traktGetObject($path) skipped: missing token/clientId")
            return null
        }

        val url = "https://api.trakt.tv$path"
        Log.d(TAG, "traktGetObject($path) requesting…")

        fun parse(body: String): JSONObject? {
            if (body.isBlank()) return null
            return runCatching { JSONObject(body) }
                .onFailure { error ->
                    Log.w(TAG, "traktGetObject($path) malformed JSON body=${compactForLog(body)}", error)
                }
                .getOrNull()
        }

        var response =
            runCatching { httpClient.get(url = url.toHttpUrl(), headers = traktHeaders(token)) }
                .onFailure { error -> Log.w(TAG, "traktGetObject($path) GET failed", error) }
                .getOrNull()
                ?: return null

        if (response.code == 401) {
            val refreshed = ensureTraktAccessToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                token = refreshed
                response =
                    runCatching { httpClient.get(url = url.toHttpUrl(), headers = traktHeaders(token)) }
                        .onFailure { error -> Log.w(TAG, "traktGetObject($path) retry GET failed", error) }
                        .getOrNull()
                        ?: return null
            }
        }

        if (response.code !in 200..299) {
            Log.w(TAG, "traktGetObject($path) failed with ${response.code} body=${compactForLog(response.body)}")
            return null
        }

        val parsed = parse(response.body)
        Log.d(TAG, "traktGetObject($path) → ${if (parsed != null) "object" else "null"}")
        return parsed
    }

    private suspend fun simklGetAny(path: String): Any? {
        val token = simklAccessToken()
        if (token.isEmpty() || simklClientId.isBlank()) {
            return null
        }
        return getJsonAny(
            url = simklApiUrl(path),
            headers =
                mapOf(
                    "Authorization" to "Bearer $token",
                    "simkl-api-key" to simklClientId,
                    "Accept" to "application/json",
                    "User-Agent" to simklUserAgent()
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

    private fun traktIdsForRequest(request: NormalizedWatchRequest): JSONObject? {
        val ids = JSONObject()
        request.remoteImdbId?.trim()?.takeIf { it.isNotBlank() }?.let { ids.put("imdb", it) }

        val tmdbId =
            runCatching {
                // Matches tmdb:123, tmdb:movie:123, tmdb:show:123, and tmdb:123:1:2.
                Regex("""\btmdb:(?:movie:|show:)?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(request.contentId)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }.getOrNull()
                ?.takeIf { it > 0 }
        if (tmdbId != null) {
            ids.put("tmdb", tmdbId)
        }

        return if (ids.length() == 0) null else ids
    }

    private suspend fun syncTraktMark(request: NormalizedWatchRequest): Boolean {
        val ids = traktIdsForRequest(request) ?: return false
        if (traktClientId.isBlank()) {
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
                                .put("ids", ids)
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", ids)
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

    private suspend fun syncTraktUnmark(request: NormalizedWatchRequest): Boolean {
        val ids = traktIdsForRequest(request) ?: return false
        if (traktClientId.isBlank()) {
            return false
        }

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", ids)
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", ids)
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

    private suspend fun syncTraktRemovePlayback(playbackId: String): Boolean {
        var token = ensureTraktAccessToken(forceRefresh = false)
        val id = playbackId.trim()
        if (token.isNullOrBlank() || traktClientId.isBlank() || id.isEmpty()) {
            return false
        }

        val url = "https://api.trakt.tv/sync/playback/$id"

        suspend fun doDelete(currentToken: String) =
            try {
                httpClient.delete(url = url.toHttpUrl(), headers = traktHeaders(currentToken))
            } catch (error: Throwable) {
                Log.w(TAG, "Trakt DELETE failed: $url", error)
                null
            }

        var response = doDelete(token) ?: return false
        if (response.code == 401) {
            val refreshed = ensureTraktAccessToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                token = refreshed
                response = doDelete(token) ?: return false
            }
        }

        return response.code in 200..299 || response.code == 404
    }

    private fun syncSimklRemovePlayback(playbackId: String): Boolean {
        return false
    }

    private suspend fun traktPost(path: String, payload: JSONObject): Boolean {
        var token = ensureTraktAccessToken(forceRefresh = false)
        if (token.isNullOrBlank() || traktClientId.isBlank()) {
            return false
        }

        val url = "https://api.trakt.tv$path"

        suspend fun doPost(currentToken: String) =
            try {
                httpClient.postJson(
                    url = url.toHttpUrl(),
                    jsonBody = payload.toString(),
                    headers = traktHeaders(currentToken).newBuilder().add("Content-Type", "application/json").build(),
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Trakt POST failed: $url", error)
                null
            }

        var response = doPost(token) ?: return false
        if (response.code == 401) {
            val refreshed = ensureTraktAccessToken(forceRefresh = true)
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                token = refreshed
                response = doPost(token) ?: return false
            }
        }

        if (response.code !in 200..299 && response.code != 409) {
            Log.w(TAG, "Trakt POST $url failed with ${response.code} body=${compactForLog(response.body)}")
        }

        return response.code in 200..299 || response.code == 409
    }

    private suspend fun syncSimklMark(request: NormalizedWatchRequest): Boolean {
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

    private suspend fun syncSimklUnmark(request: NormalizedWatchRequest): Boolean {
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

    private suspend fun simklPost(path: String, payload: JSONObject): Boolean {
        val token = simklAccessToken()
        if (token.isEmpty() || simklClientId.isEmpty()) {
            return false
        }

        val responseCode =
            postJson(
                url = simklApiUrl(path),
                headers =
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to simklUserAgent()
                    ),
                payload = payload
            )

        return responseCode in 200..299 || responseCode == 409
    }

    private data class CachedSnapshot<T>(
        val updatedAtEpochMs: Long,
        val value: T,
    )

    private fun continueWatchingCacheFile(provider: WatchProvider): File {
        val name = provider.name.lowercase(Locale.US)
        return File(providerCacheDir, "${name}_continue_watching.json")
    }

    private fun providerLibraryCacheFile(provider: WatchProvider): File {
        val name = provider.name.lowercase(Locale.US)
        return File(providerCacheDir, "${name}_library.json")
    }

    private fun formatCacheAge(nowMs: Long, updatedAtEpochMs: Long): String {
        val deltaMs = (nowMs - updatedAtEpochMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        val hours = deltaMs / 3_600_000L
        val days = deltaMs / 86_400_000L
        return when {
            deltaMs < 60_000L -> "just now"
            minutes < 60L -> "${minutes}m"
            hours < 24L -> "${hours}h"
            else -> "${days}d"
        }
    }

    private suspend fun writeFileAtomic(file: File, text: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    // Fallback to direct write if rename fails.
                    file.writeText(text)
                    runCatching { tmp.delete() }
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to write cache file: ${file.absolutePath}", error)
            }
        }
    }

    private suspend fun writeContinueWatchingCache(provider: WatchProvider, result: ContinueWatchingLabResult) {
        if (provider == WatchProvider.LOCAL) return

        val updatedAt = System.currentTimeMillis()
        val entriesJson = JSONArray()
        for (entry in result.entries) {
            entriesJson.put(
                JSONObject()
                    .put("contentId", entry.contentId)
                    .put("contentType", entry.contentType.name)
                    .put("title", entry.title)
                    .put("season", entry.season)
                    .put("episode", entry.episode)
                    .put("progressPercent", entry.progressPercent)
                    .put("lastUpdatedEpochMs", entry.lastUpdatedEpochMs)
                    .put("provider", entry.provider.name)
                    .put("providerPlaybackId", entry.providerPlaybackId)
                    .put("isUpNextPlaceholder", entry.isUpNextPlaceholder)
            )
        }

        val json =
            JSONObject()
                .put("updatedAtEpochMs", updatedAt)
                .put("statusMessage", result.statusMessage)
                .put("entries", entriesJson)

        writeFileAtomic(continueWatchingCacheFile(provider), json.toString())
    }

    private suspend fun readContinueWatchingCache(provider: WatchProvider): CachedSnapshot<ContinueWatchingLabResult>? {
        if (provider == WatchProvider.LOCAL) return null

        val file = continueWatchingCacheFile(provider)
        val text =
            withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching { file.readText() }.getOrNull()
            } ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val updatedAt = root.optLong("updatedAtEpochMs", -1L)
        if (updatedAt <= 0L) return null

        val entriesArray = root.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<ContinueWatchingEntry>()
        for (index in 0 until entriesArray.length()) {
            val obj = entriesArray.optJSONObject(index) ?: continue
            val contentId = obj.optString("contentId").trim()
            if (contentId.isBlank()) continue
            val contentType =
                runCatching { MetadataLabMediaType.valueOf(obj.optString("contentType").trim()) }.getOrNull()
                    ?: continue
            val title = obj.optString("title").trim().ifEmpty { contentId }
            val season = obj.optInt("season", 0).takeIf { it > 0 }
            val episode = obj.optInt("episode", 0).takeIf { it > 0 }
            val progress = obj.optDouble("progressPercent", 0.0)
            val lastUpdated = obj.optLong("lastUpdatedEpochMs", updatedAt)
            val providerValue =
                runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider
            val playbackId = obj.optString("providerPlaybackId").trim().ifEmpty { null }
            val isUpNext = obj.optBoolean("isUpNextPlaceholder", false)

            entries.add(
                ContinueWatchingEntry(
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    season = season,
                    episode = episode,
                    progressPercent = progress,
                    lastUpdatedEpochMs = lastUpdated,
                    provider = providerValue,
                    providerPlaybackId = playbackId,
                    isUpNextPlaceholder = isUpNext,
                )
            )
        }

        val statusMessage = root.optString("statusMessage").trim().ifEmpty { "Cached continue watching." }
        return CachedSnapshot(
            updatedAtEpochMs = updatedAt,
            value = ContinueWatchingLabResult(statusMessage = statusMessage, entries = entries),
        )
    }

    private suspend fun writeProviderLibraryCache(provider: WatchProvider, snapshot: ProviderLibrarySnapshot) {
        if (provider == WatchProvider.LOCAL) return

        val updatedAt = System.currentTimeMillis()
        val foldersJson = JSONArray()
        for (folder in snapshot.folders) {
            foldersJson.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("label", folder.label)
                    .put("provider", folder.provider.name)
                    .put("itemCount", folder.itemCount)
            )
        }

        val itemsJson = JSONArray()
        for (item in snapshot.items) {
            itemsJson.put(
                JSONObject()
                    .put("provider", item.provider.name)
                    .put("folderId", item.folderId)
                    .put("contentId", item.contentId)
                    .put("contentType", item.contentType.name)
                    .put("title", item.title)
                    .put("season", item.season)
                    .put("episode", item.episode)
                    .put("addedAtEpochMs", item.addedAtEpochMs)
            )
        }

        val json =
            JSONObject()
                .put("updatedAtEpochMs", updatedAt)
                .put("statusMessage", snapshot.statusMessage)
                .put("folders", foldersJson)
                .put("items", itemsJson)

        writeFileAtomic(providerLibraryCacheFile(provider), json.toString())
    }

    private suspend fun readProviderLibraryCache(provider: WatchProvider): CachedSnapshot<ProviderLibrarySnapshot>? {
        if (provider == WatchProvider.LOCAL) return null

        val file = providerLibraryCacheFile(provider)
        val text =
            withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching { file.readText() }.getOrNull()
            } ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val updatedAt = root.optLong("updatedAtEpochMs", -1L)
        if (updatedAt <= 0L) return null

        val foldersArray = root.optJSONArray("folders") ?: JSONArray()
        val folders = mutableListOf<ProviderLibraryFolder>()
        for (index in 0 until foldersArray.length()) {
            val obj = foldersArray.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            val label = obj.optString("label").trim()
            if (id.isBlank() || label.isBlank()) continue
            val providerValue =
                runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider
            val itemCount = obj.optInt("itemCount", 0).coerceAtLeast(0)
            folders.add(
                ProviderLibraryFolder(
                    id = id,
                    label = label,
                    provider = providerValue,
                    itemCount = itemCount,
                )
            )
        }

        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<ProviderLibraryItem>()
        for (index in 0 until itemsArray.length()) {
            val obj = itemsArray.optJSONObject(index) ?: continue
            val folderId = obj.optString("folderId").trim()
            val contentId = obj.optString("contentId").trim()
            if (folderId.isBlank() || contentId.isBlank()) continue
            val contentType =
                runCatching { MetadataLabMediaType.valueOf(obj.optString("contentType").trim()) }.getOrNull()
                    ?: continue
            val title = obj.optString("title").trim().ifEmpty { contentId }
            val season = obj.optInt("season", 0).takeIf { it > 0 }
            val episode = obj.optInt("episode", 0).takeIf { it > 0 }
            val addedAtEpochMs = obj.optLong("addedAtEpochMs", updatedAt)
            val providerValue =
                runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider

            items.add(
                ProviderLibraryItem(
                    provider = providerValue,
                    folderId = folderId,
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    season = season,
                    episode = episode,
                    addedAtEpochMs = addedAtEpochMs,
                )
            )
        }

        val statusMessage = root.optString("statusMessage").trim().ifEmpty { "Cached provider library." }
        return CachedSnapshot(
            updatedAtEpochMs = updatedAt,
            value = ProviderLibrarySnapshot(statusMessage = statusMessage, folders = folders, items = items),
        )
    }

    private suspend fun postJson(url: String, headers: Map<String, String>, payload: JSONObject): Int? {
        return runCatching {
            val response =
                httpClient.postJson(
                    url = url.toHttpUrl(),
                    jsonBody = payload.toString(),
                    headers = headers.toOkHttpHeaders(),
                )

            if (response.code !in 200..299) {
                Log.w(TAG, "HTTP POST $url failed with ${response.code} body=${compactForLog(response.body)}")
            }
            response.code
        }.onFailure { error ->
            Log.w(TAG, "HTTP POST $url failed with exception", error)
        }.getOrNull()
    }

    private suspend fun deleteRequest(url: String, headers: Map<String, String>): Int? {
        return runCatching {
            val response =
                httpClient.delete(
                    url = url.toHttpUrl(),
                    headers = headers.toOkHttpHeaders(),
                )
            if (response.code !in 200..299 && response.code != 404) {
                Log.w(TAG, "HTTP DELETE $url failed with ${response.code} body=${compactForLog(response.body)}")
            }
            response.code
        }.onFailure { error ->
            Log.w(TAG, "HTTP DELETE $url failed with exception", error)
        }.getOrNull()
    }

    private suspend fun postJsonForObject(url: String, headers: Map<String, String>, payload: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    httpClient.postJson(
                        url = url.toHttpUrl(),
                        jsonBody = payload.toString(),
                        headers = headers.toOkHttpHeaders(),
                    )

                val body = response.body
                if (response.code !in 200..299) {
                    Log.w(TAG, "HTTP POST $url failed with ${response.code} body=${compactForLog(body)}")
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
            }.getOrNull()
        }
    }

    private suspend fun getJsonArray(url: String, headers: Map<String, String>): JSONArray? {
        return getJsonAny(url = url, headers = headers).toJsonArrayOrNull()
    }

    private suspend fun getJsonAny(url: String, headers: Map<String, String>): Any? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    httpClient.get(
                        url = url.toHttpUrl(),
                        headers = headers.toOkHttpHeaders(),
                    )

                val body = response.body
                if (response.code !in 200..299) {
                    Log.w(TAG, "HTTP GET $url failed with ${response.code} body=${compactForLog(body)}")
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
            }.getOrNull()
        }
    }

    private fun Map<String, String>.toOkHttpHeaders(): Headers {
        if (isEmpty()) return Headers.headersOf()
        val builder = Headers.Builder()
        forEach { (key, value) ->
            builder.add(key, value)
        }
        return builder.build()
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

    private suspend fun fetchTraktUserHandle(accessToken: String): String? {
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

    private suspend fun fetchSimklUserHandle(accessToken: String): String? {
        if (simklClientId.isBlank() || accessToken.isBlank()) {
            Log.w(TAG, "Skipping Simkl profile lookup: missing auth inputs")
            return null
        }

        val response =
            postJsonForObject(
                url = simklApiUrl("/users/settings"),
                headers =
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to simklUserAgent()
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

    private fun simklApiUrl(path: String): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return simklUrlWithRequiredParams("$SIMKL_API_BASE$normalizedPath")
    }

    private fun simklUrlWithRequiredParams(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("client_id").isNullOrBlank() && simklClientId.isNotBlank()) {
            builder.appendQueryParameter("client_id", simklClientId)
        }
        if (uri.getQueryParameter("app-name").isNullOrBlank()) {
            builder.appendQueryParameter("app-name", SIMKL_APP_NAME)
        }
        if (uri.getQueryParameter("app-version").isNullOrBlank()) {
            builder.appendQueryParameter("app-version", simklAppVersion())
        }
        return builder.build().toString()
    }

    private fun simklAppVersion(): String {
        return BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
    }

    private fun simklUserAgent(): String {
        return "$SIMKL_APP_NAME/${simklAppVersion()}"
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
        private const val CONTINUE_WATCHING_MIN_PROGRESS_PERCENT = 2.0
        private const val CONTINUE_WATCHING_COMPLETION_PERCENT = 85.0
        private const val CONTINUE_WATCHING_PLAYBACK_LIMIT = 30
        private const val CONTINUE_WATCHING_UPNEXT_SHOW_LIMIT = 30
        private const val TRAKT_AUTHORIZE_BASE = "https://trakt.tv/oauth/authorize"
        private const val TRAKT_TOKEN_URL = "https://api.trakt.tv/oauth/token"
        private const val SIMKL_AUTHORIZE_BASE = "https://simkl.com/oauth/authorize"
        private const val SIMKL_TOKEN_URL = "https://api.simkl.com/oauth/token"
        private const val SIMKL_API_BASE = "https://api.simkl.com"
        private const val SIMKL_APP_NAME = "crispytv"
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
