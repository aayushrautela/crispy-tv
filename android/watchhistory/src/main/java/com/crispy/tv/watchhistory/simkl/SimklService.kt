package com.crispy.tv.watchhistory.simkl

import android.os.SystemClock
import android.util.Log
import com.crispy.tv.watchhistory.SIMKL_API_BASE
import com.crispy.tv.watchhistory.SIMKL_APP_NAME
import com.crispy.tv.watchhistory.SIMKL_AUTHORIZE_BASE
import com.crispy.tv.watchhistory.SIMKL_TOKEN_URL
import com.crispy.tv.watchhistory.WatchHistoryHttp
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class SimklService(
    private val http: WatchHistoryHttp,
    private val sessionStore: ProviderSessionStore,
    private val simklClientId: String,
    private val simklClientSecret: String,
    private val simklRedirectUri: String,
    private val appName: String = SIMKL_APP_NAME,
    private val appVersion: String = "dev",
    private val logTag: String = "SimklService",
) {
    private sealed interface PlaybackDecision {
        data class Return(val value: JSONArray) : PlaybackDecision

        data class Await(val deferred: CompletableDeferred<JSONArray>) : PlaybackDecision

        data class Fetch(val deferred: CompletableDeferred<JSONArray>) : PlaybackDecision
    }

    private val rateLimitMutex = Mutex()
    private var lastApiCallAtElapsedMs: Long = 0L

    private val playbackMutex = Mutex()
    private var playbackCachedAtElapsedMs: Long = 0L
    private var playbackCachedToken: String = ""
    private var playbackCache: JSONArray? = null
    private var playbackInFlight: CompletableDeferred<JSONArray>? = null

    private val userSettingsMutex = Mutex()
    private var userSettingsCachedAtElapsedMs: Long = 0L
    private var userSettingsCachedToken: String = ""
    private var userSettingsCache: JSONObject? = null

    private val scrobbleMutex = Mutex()
    private val lastScrobblePauseAtByKeyElapsedMs = LinkedHashMap<String, Long>()

    fun userAgent(): String = "$appName/$appVersion"

    internal data class SimklIds(
        val imdbId: String?,
        val tmdbId: Long? = null,
        val simklId: Long? = null,
        val malId: String? = null,
    )

    internal sealed interface SimklScrobbleContent {
        val title: String
        val year: Int?
        val ids: SimklIds

        data class Movie(
            override val title: String,
            override val year: Int?,
            override val ids: SimklIds,
        ) : SimklScrobbleContent

        data class Episode(
            val showTitle: String,
            val showYear: Int?,
            val season: Int,
            val episode: Int,
            override val title: String,
            override val year: Int?,
            override val ids: SimklIds,
        ) : SimklScrobbleContent
    }

    fun authorizeUrl(state: String, codeChallenge: String?): String {
        val builder = SIMKL_AUTHORIZE_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", simklClientId)
            .addQueryParameter("redirect_uri", simklRedirectUri)
            .addQueryParameter("state", state)

        if (!codeChallenge.isNullOrBlank()) {
            builder.addQueryParameter("code_challenge", codeChallenge)
            builder.addQueryParameter("code_challenge_method", "S256")
        }

        return builder.build().toString()
    }

    suspend fun exchangeToken(code: String, codeVerifier: String?): JSONObject? {
        val payload =
            JSONObject()
                .put("grant_type", "authorization_code")
                .put("code", code)
                .put("client_id", simklClientId)
                .put("client_secret", simklClientSecret)
                .put("redirect_uri", simklRedirectUri)

        if (!codeVerifier.isNullOrBlank()) {
            payload.put("code_verifier", codeVerifier)
        }

        val response =
            http.postJsonRaw(
                url = SIMKL_TOKEN_URL,
                headers =
                    mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to userAgent(),
                    ),
                payload = payload,
            ) ?: return null

        if (response.code !in 200..299) {
            Log.w(logTag, "Simkl token exchange non-2xx: ${response.code}")
            return null
        }

        return runCatching { JSONObject(response.body) }
            .onFailure { Log.w(logTag, "Simkl token exchange JSON parse failed", it) }
            .getOrNull()
    }

    suspend fun scrobbleStart(content: SimklScrobbleContent, progressPercent: Double): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val payload = buildScrobblePayload(content = content, progressPercent = progressPercent)
        val response = apiRequestRaw(method = "POST", path = "/scrobble/start", query = emptyMap(), accessToken = token, payload = payload)
            ?: return false
        return (response.code in 200..299) || response.code == 409
    }

    suspend fun scrobblePause(content: SimklScrobbleContent, progressPercent: Double, force: Boolean = false): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val key = scrobbleKey(content)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        scrobbleMutex.withLock {
            val last = lastScrobblePauseAtByKeyElapsedMs[key]
            if (!force && last != null && nowElapsedMs - last < SIMKL_SCROBBLE_DEBOUNCE_MS) {
                return true
            }
            lastScrobblePauseAtByKeyElapsedMs[key] = nowElapsedMs
        }

        val payload = buildScrobblePayload(content = content, progressPercent = progressPercent)
        val response = apiRequestRaw(method = "POST", path = "/scrobble/pause", query = emptyMap(), accessToken = token, payload = payload)
            ?: return false
        return (response.code in 200..299) || response.code == 409
    }

    suspend fun scrobbleStop(content: SimklScrobbleContent, progressPercent: Double): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val payload = buildScrobblePayload(content = content, progressPercent = progressPercent)
        val response = apiRequestRaw(method = "POST", path = "/scrobble/stop", query = emptyMap(), accessToken = token, payload = payload)

        if (response != null && response.code in 200..299) {
            return true
        }

        val progress = progressPercent.coerceIn(0.0, 100.0)
        if (progress >= SIMKL_COMPLETION_THRESHOLD_PERCENT) {
            val historyPayload = buildHistoryFallbackPayload(content)
            if (historyPayload != null) {
                return addToHistory(historyPayload)
            }
        }

        return response?.code == 409
    }

    suspend fun fetchUserHandle(accessToken: String): String? {
        val settings = fetchUserSettings(accessToken) ?: return null

        val user = settings.optJSONObject("user")
        val name = user?.optString("name")?.trim().orEmpty()
        if (name.isNotBlank()) return name

        val account = settings.optJSONObject("account")
        val accountId = account?.opt("id")?.toString()?.trim().orEmpty()
        return accountId.ifBlank { null }
    }

    suspend fun getPlaybackStatus(forceRefresh: Boolean = false): JSONArray {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return JSONArray()

        val nowElapsedMs = SystemClock.elapsedRealtime()

        if (!forceRefresh) {
            val decision =
                playbackMutex.withLock {
                    val cached = playbackCache
                    if (
                        cached != null &&
                        playbackCachedToken == token &&
                        nowElapsedMs - playbackCachedAtElapsedMs < PLAYBACK_CACHE_TTL_MS
                    ) {
                        return@withLock PlaybackDecision.Return(cached)
                    }

                    val inFlight = playbackInFlight
                    if (inFlight != null) {
                        return@withLock PlaybackDecision.Await(inFlight)
                    }

                    val deferred = CompletableDeferred<JSONArray>()
                    playbackInFlight = deferred
                    return@withLock PlaybackDecision.Fetch(deferred)
                }

            when (decision) {
                is PlaybackDecision.Return -> return decision.value
                is PlaybackDecision.Await -> return decision.deferred.await()
                is PlaybackDecision.Fetch -> {
                    val fetched =
                        runCatching { apiGetJsonArray(path = "/sync/playback", accessToken = token) }
                            .getOrNull()
                            ?: JSONArray()

                    playbackMutex.withLock {
                        playbackCache = fetched
                        playbackCachedAtElapsedMs = nowElapsedMs
                        playbackCachedToken = token
                        playbackInFlight = null
                        decision.deferred.complete(fetched)
                    }

                    return fetched
                }
            }
        }

        val fetched =
            runCatching { apiGetJsonArray(path = "/sync/playback", accessToken = token) }
                .getOrNull()
                ?: JSONArray()

        playbackMutex.withLock {
            playbackCache = fetched
            playbackCachedAtElapsedMs = nowElapsedMs
            playbackCachedToken = token
        }

        return fetched
    }

    suspend fun getActivities(): JSONObject? {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return null
        return apiGetJsonObject(path = "/sync/activities", accessToken = token)
    }

    suspend fun getAllItemsResponse(type: String? = null, status: String? = null, dateFrom: String? = null): Any? {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return null

        val path = buildSyncAllItemsPath(type = type, status = status)
        val query = if (dateFrom.isNullOrBlank()) emptyMap() else mapOf("date_from" to dateFrom)
        return apiGetAny(path = path, query = query, accessToken = token)
    }

    suspend fun getRatingsResponse(type: String? = null, ratingFilter: String? = null): Any? {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return null

        val path = buildSyncRatingsPath(type = type, ratingFilter = ratingFilter)
        return apiGetAny(path = path, query = emptyMap(), accessToken = token)
    }

    suspend fun addToHistory(payload: JSONObject): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false
        return apiPostOkOrConflict(path = "/sync/history", payload = payload, accessToken = token)
    }

    suspend fun removeFromHistory(payload: JSONObject): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false
        return apiPostOkOrConflict(path = "/sync/history/remove", payload = payload, accessToken = token)
    }

    suspend fun addToList(typeKey: String, imdbId: String, status: String): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val payload = JSONObject().put(
            typeKey,
            JSONArray().put(
                JSONObject()
                    .put("to", status)
                    .put("ids", JSONObject().put("imdb", imdbId)),
            ),
        )
        return apiPostOkOrConflict(path = "/sync/add-to-list", payload = payload, accessToken = token)
    }

    suspend fun removeFromList(typeKey: String, imdbId: String): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val payload = JSONObject().put(
            typeKey,
            JSONArray().put(
                JSONObject().put("ids", JSONObject().put("imdb", imdbId)),
            ),
        )
        return apiPostOkOrConflict(path = "/sync/remove-from-list", payload = payload, accessToken = token)
    }

    suspend fun addRating(typeKey: String, imdbId: String, rating: Int): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false

        val ratingValue = rating.coerceIn(1, 10)
        val payload = JSONObject().put(
            typeKey,
            JSONArray().put(
                JSONObject()
                    .put("ids", JSONObject().put("imdb", imdbId))
                    .put("rating", ratingValue),
            ),
        )
        return apiPostOkOrConflict(path = "/sync/ratings", payload = payload, accessToken = token)
    }

    suspend fun getUserSettings(forceRefresh: Boolean = false): JSONObject? {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return null
        return getUserSettingsForToken(accessToken = token, forceRefresh = forceRefresh)
    }

    private suspend fun getUserSettingsForToken(accessToken: String, forceRefresh: Boolean): JSONObject? {
        val nowElapsedMs = SystemClock.elapsedRealtime()

        userSettingsMutex.withLock {
            if (!forceRefresh &&
                userSettingsCache != null &&
                userSettingsCachedToken == accessToken &&
                nowElapsedMs - userSettingsCachedAtElapsedMs < USER_SETTINGS_CACHE_TTL_MS
            ) {
                return userSettingsCache
            }
        }

        val settings = fetchUserSettings(accessToken)

        userSettingsMutex.withLock {
            userSettingsCache = settings
            userSettingsCachedAtElapsedMs = nowElapsedMs
            userSettingsCachedToken = accessToken
        }

        return settings
    }

    private suspend fun fetchUserSettings(accessToken: String): JSONObject? {
        return apiPostJsonObject(
            path = "/users/settings",
            payload = JSONObject(),
            accessToken = accessToken,
        )
    }

    private fun buildScrobblePayload(content: SimklScrobbleContent, progressPercent: Double): JSONObject {
        val progress = progressPercent.coerceIn(0.0, 100.0)
        val ids = buildIdsJson(content.ids)

        return when (content) {
            is SimklScrobbleContent.Movie -> {
                val movie = JSONObject().put("title", content.title)
                if (content.year != null) movie.put("year", content.year)
                movie.put("ids", ids)
                JSONObject()
                    .put("progress", progress)
                    .put("movie", movie)
            }

            is SimklScrobbleContent.Episode -> {
                val show = JSONObject().put("title", content.showTitle)
                if (content.showYear != null) show.put("year", content.showYear)
                show.put("ids", ids)

                val episode =
                    JSONObject()
                        .put("season", content.season)
                        .put("number", content.episode)

                JSONObject()
                    .put("progress", progress)
                    .put("show", show)
                    .put("episode", episode)
            }
        }
    }

    private fun buildHistoryFallbackPayload(content: SimklScrobbleContent): JSONObject? {
        val ids = buildIdsJson(content.ids)
        if (ids.length() == 0) return null

        return when (content) {
            is SimklScrobbleContent.Movie -> {
                JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids)))
            }

            is SimklScrobbleContent.Episode -> {
                val seasonObj =
                    JSONObject().put(
                        "number",
                        content.season,
                    ).put(
                        "episodes",
                        JSONArray().put(JSONObject().put("number", content.episode)),
                    )
                val showObj =
                    JSONObject()
                        .put("ids", ids)
                        .put("seasons", JSONArray().put(seasonObj))
                JSONObject().put("shows", JSONArray().put(showObj))
            }
        }
    }

    private fun buildIdsJson(ids: SimklIds): JSONObject {
        val obj = JSONObject()

        val imdb = ids.imdbId?.trim()?.lowercase(Locale.US)
        if (!imdb.isNullOrBlank()) {
            obj.put("imdb", if (imdb.startsWith("tt")) imdb else "tt$imdb")
        }
        if (ids.tmdbId != null) obj.put("tmdb", ids.tmdbId)
        if (ids.simklId != null) obj.put("simkl", ids.simklId)
        if (!ids.malId.isNullOrBlank()) obj.put("mal", ids.malId)

        return obj
    }

    private fun scrobbleKey(content: SimklScrobbleContent): String {
        val idPart = content.ids.imdbId?.ifBlank { null }
            ?: content.ids.tmdbId?.toString()
            ?: content.title

        return when (content) {
            is SimklScrobbleContent.Movie -> "movie:$idPart"
            is SimklScrobbleContent.Episode -> "episode:$idPart:${content.season}:${content.episode}"
        }
    }

    private suspend fun apiGetAny(path: String, query: Map<String, String>, accessToken: String): Any? {
        return apiRequest(method = "GET", path = path, query = query, accessToken = accessToken, payload = null)
    }

    private suspend fun apiGetJsonObject(path: String, accessToken: String): JSONObject? {
        val any = apiRequest(method = "GET", path = path, query = emptyMap(), accessToken = accessToken, payload = null)
        return any as? JSONObject
    }

    private suspend fun apiGetJsonArray(path: String, accessToken: String): JSONArray? {
        val any = apiRequest(method = "GET", path = path, query = emptyMap(), accessToken = accessToken, payload = null)
        return any as? JSONArray
    }

    private suspend fun apiPostJsonObject(path: String, payload: JSONObject, accessToken: String): JSONObject? {
        val any = apiRequest(method = "POST", path = path, query = emptyMap(), accessToken = accessToken, payload = payload)
        return any as? JSONObject
    }

    private suspend fun apiPostOkOrConflict(path: String, payload: JSONObject, accessToken: String): Boolean {
        val response = apiRequestRaw(method = "POST", path = path, query = emptyMap(), accessToken = accessToken, payload = payload) ?: return false
        return (response.code in 200..299) || response.code == 409
    }

    private suspend fun apiRequest(method: String, path: String, query: Map<String, String>, accessToken: String, payload: JSONObject?): Any? {
        val response = apiRequestRaw(method = method, path = path, query = query, accessToken = accessToken, payload = payload) ?: return null

        if (response.code == 409) {
            return null
        }
        if (response.code !in 200..299) {
            return null
        }
        if (response.code == 204) {
            return JSONObject()
        }

        val body = response.body.trim()
        if (body.isEmpty()) return JSONObject()

        return runCatching {
            when {
                body.startsWith('[') -> JSONArray(body)
                body.startsWith('{') -> JSONObject(body)
                else -> body
            }
        }.onFailure { Log.w(logTag, "Simkl JSON parse failed: ${response.code} ${response.url}", it) }
            .getOrNull()
    }

    private suspend fun apiRequestRaw(
        method: String,
        path: String,
        query: Map<String, String>,
        accessToken: String,
        payload: JSONObject?,
    ): com.crispy.tv.network.CrispyHttpResponse? {
        if (simklClientId.isBlank() || accessToken.isBlank()) return null

        waitForRateLimit()

        val url = apiUrl(path = path, query = query)
        val headers =
            mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer $accessToken",
                "simkl-api-key" to simklClientId,
                "User-Agent" to userAgent(),
            )

        val response =
            when (method.uppercase()) {
                "GET" -> http.getRaw(url = url, headers = headers)
                "POST" -> {
                    val jsonPayload = payload ?: JSONObject()
                    http.postJsonRaw(url = url, headers = headers, payload = jsonPayload)
                }

                else -> null
            } ?: return null

        if (response.code == 409) {
            Log.d(logTag, "Simkl 409 Conflict: ${response.url}")
        } else if (response.code !in 200..299) {
            Log.w(logTag, "Simkl HTTP ${response.code}: ${response.url}")
        }

        return response
    }

    private fun apiUrl(path: String, query: Map<String, String>): String {
        val normalized = path.trim().removePrefix("/")
        val builder = SIMKL_API_BASE.toHttpUrl().newBuilder()
        if (normalized.isNotEmpty()) {
            normalized.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        }
        for ((k, v) in query) {
            builder.addQueryParameter(k, v)
        }
        return builder.build().toString()
    }

    private suspend fun waitForRateLimit() {
        rateLimitMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastApiCallAtElapsedMs
            if (delta < MIN_API_INTERVAL_MS) {
                delay(MIN_API_INTERVAL_MS - delta)
            }
            lastApiCallAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun buildSyncAllItemsPath(type: String?, status: String?): String {
        val base = "sync/all-items"
        val normalizedType = type?.trim()?.ifBlank { null }
        val normalizedStatus = status?.trim()?.ifBlank { null }

        return when {
            normalizedType != null && normalizedStatus != null -> "/$base/$normalizedType/$normalizedStatus"
            normalizedType != null -> "/$base/$normalizedType"
            normalizedStatus != null -> "/$base/$normalizedStatus"
            else -> "/$base"
        }
    }

    private fun buildSyncRatingsPath(type: String?, ratingFilter: String?): String {
        val base = "sync/ratings"
        val normalizedType = type?.trim()?.ifBlank { null }
        val normalizedFilter = ratingFilter?.trim()?.ifBlank { null }

        return when {
            normalizedType != null && normalizedFilter != null -> "/$base/$normalizedType/$normalizedFilter"
            normalizedType != null -> "/$base/$normalizedType"
            else -> "/$base"
        }
    }

    private companion object {
        private const val MIN_API_INTERVAL_MS = 500L
        private const val PLAYBACK_CACHE_TTL_MS = 10_000L
        private const val USER_SETTINGS_CACHE_TTL_MS = 5 * 60 * 1000L

        private const val SIMKL_SCROBBLE_DEBOUNCE_MS = 15_000L
        private const val SIMKL_COMPLETION_THRESHOLD_PERCENT = 80.0
    }
}
