package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.SupabaseSyncAuthState
import com.crispy.tv.player.SupabaseSyncLabResult
import com.crispy.tv.player.SupabaseSyncLabService
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

class RemoteSupabaseSyncLabService(
    context: Context,
    private val httpClient: CrispyHttpClient,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    addonManifestUrlsCsv: String,
    private val watchHistoryService: WatchHistoryService
) : SupabaseSyncLabService {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val baseUrl = supabaseUrl.trim().trimEnd('/')

    @Volatile
    private var cachedSession: SupabaseSession? = null

    override suspend fun initialize(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY.")
        }

        val session = ensureValidSession()
        if (session == null) {
            return@withContext result("Supabase configured. Sign in to enable cloud sync.")
        }

        runCatching {
            pullAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pulled ->
                result(
                    message = pulled.status,
                    pulledAddons = pulled.addons,
                    pulledWatchedItems = pulled.watched
                )
            },
            onFailure = { error ->
                result("Supabase session restored, but startup pull failed: ${error.message ?: "unknown error"}")
            }
        )
    }

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }
        val normalizedEmail = email.trim()
        val normalizedPassword = password.trim()
        if (normalizedEmail.isEmpty() || normalizedPassword.isEmpty()) {
            return@withContext result("Email and password are required for sign-up.")
        }

        runCatching {
            val response =
                sendJsonRequest(
                    method = "POST",
                    url = "$baseUrl/auth/v1/signup",
                    headers = baseHeaders(),
                    payload =
                        JSONObject()
                            .put("email", normalizedEmail)
                            .put("password", normalizedPassword)
                )
            if (!response.isSuccessful()) {
                throw IllegalStateException(extractErrorMessage(response.body) ?: "Supabase sign-up failed")
            }

            val body = JSONObject(response.body.ifBlank { "{}" })
            val session = parseSession(body)
            if (session != null) {
                saveSession(session)
            }

            if (session != null) {
                "Supabase sign-up succeeded and session started."
            } else {
                "Supabase sign-up submitted. Confirm email if your project requires verification."
            }
        }.fold(
            onSuccess = { message -> result(message) },
            onFailure = { error -> result("Supabase sign-up failed: ${error.message ?: "unknown error"}") }
        )
    }

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }
        val normalizedEmail = email.trim()
        val normalizedPassword = password.trim()
        if (normalizedEmail.isEmpty() || normalizedPassword.isEmpty()) {
            return@withContext result("Email and password are required for sign-in.")
        }

        runCatching {
            val response =
                sendJsonRequest(
                    method = "POST",
                    url = "$baseUrl/auth/v1/token?grant_type=password",
                    headers = baseHeaders(),
                    payload =
                        JSONObject()
                            .put("email", normalizedEmail)
                            .put("password", normalizedPassword)
                )
            if (!response.isSuccessful()) {
                throw IllegalStateException(extractErrorMessage(response.body) ?: "Supabase sign-in failed")
            }

            val session = parseSession(JSONObject(response.body.ifBlank { "{}" }))
                ?: throw IllegalStateException("Supabase did not return a session")
            saveSession(session)

            "Supabase sign-in successful."
        }.fold(
            onSuccess = { message -> result(message) },
            onFailure = { error -> result("Supabase sign-in failed: ${error.message ?: "unknown error"}") }
        )
    }

    override suspend fun signOut(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            clearSession()
            return@withContext result("Supabase sync is not configured.")
        }

        runCatching {
            val token = loadSession()?.accessToken.orEmpty()
            if (token.isNotBlank()) {
                sendJsonRequest(
                    method = "POST",
                    url = "$baseUrl/auth/v1/logout",
                    headers = authHeaders(token),
                    payload = null
                )
            }
        }
        clearSession()
        result("Signed out from Supabase sync.")
    }

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val session = ensureValidSession()
            ?: return@withContext result("Sign in to Supabase before pushing local data.")

        runCatching {
            pushAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pushed ->
                result(
                    message = pushed.status,
                    pushedAddons = pushed.addons,
                    pushedWatchedItems = pushed.watched
                )
            },
            onFailure = { error ->
                result("Supabase push failed: ${error.message ?: "unknown error"}")
            }
        )
    }

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val session = ensureValidSession()
            ?: return@withContext result("Sign in to Supabase before pulling cloud data.")

        runCatching {
            pullAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pulled ->
                result(
                    message = pulled.status,
                    pulledAddons = pulled.addons,
                    pulledWatchedItems = pulled.watched
                )
            },
            onFailure = { error ->
                result("Supabase pull failed: ${error.message ?: "unknown error"}")
            }
        )
    }

    override suspend fun syncNow(): SupabaseSyncLabResult {
        return pullAllToLocal()
    }

    override suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        return@withContext result("Sync codes are not supported.")
    }

    override suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        return@withContext result("Sync codes are not supported.")
    }

    override fun authState(): SupabaseSyncAuthState {
        val session = loadSession()
        return SupabaseSyncAuthState(
            configured = isConfigured(),
            authenticated = isConfigured() && session?.accessToken?.isNotBlank() == true,
            anonymous = session?.anonymous == true,
            userId = session?.userId,
            email = session?.email
        )
    }

    private suspend fun pushAllInternal(accessToken: String): SyncCounts {
        val pushedAddons = pushAddons(accessToken)
        val pushedWatched = 0

        val status = "Pushed $pushedAddons addon rows. Watched sync not implemented."
        return SyncCounts(
            status = status,
            addons = pushedAddons,
            watched = pushedWatched
        )
    }

    private suspend fun pullAllInternal(accessToken: String): SyncCounts {
        val pulledAddons = pullAddons(accessToken)
        val pulledWatched = 0

        val status = "Pulled $pulledAddons addon rows. Watched sync not implemented."
        return SyncCounts(
            status = status,
            addons = pulledAddons,
            watched = pulledWatched
        )
    }

    private suspend fun pushAddons(accessToken: String): Int {
        val localRows = addonRegistry.exportCloudAddons()
        val payloadRows = JSONArray()
        localRows.forEach { row ->
            payloadRows.put(
                JSONObject()
                    .put("url", row.manifestUrl)
                    .put("enabled", true)
            )
        }
        callRpc(
            functionName = "replace_household_addons",
            bearerToken = accessToken,
            payload = JSONObject().put("p_addons", payloadRows)
        )
        return localRows.size
    }

    private suspend fun pullAddons(accessToken: String): Int {
        val response =
            callRpc(
                functionName = "get_household_addons",
                bearerToken = accessToken,
                payload = JSONObject()
            )

        val array =
            when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray("items") ?: JSONArray()
                else -> JSONArray()
            }
        val rows = mutableListOf<CloudAddonRow>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isEmpty()) {
                continue
            }
            val enabled = item.optBoolean("enabled", true)
            if (!enabled) continue
            rows += CloudAddonRow(manifestUrl = url, sortOrder = index)
        }

        addonRegistry.reconcileCloudAddons(rows)
        return rows.size
    }

    private suspend fun pushWatched(accessToken: String): Int {
        return 0
    }

    private suspend fun pullWatched(accessToken: String): Int {
        return 0
    }

    private fun parseWatchedEntries(raw: Any?): List<WatchHistoryEntry> {
        val array = when (raw) {
            is JSONArray -> raw
            is JSONObject -> raw.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }

        val entries = mutableListOf<WatchHistoryEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val contentId = item.optString("content_id").trim()
            if (contentId.isEmpty()) {
                continue
            }

            val season = item.optInt("season", Int.MIN_VALUE).takeIf { it > 0 }
            val episode = item.optInt("episode", Int.MIN_VALUE).takeIf { it > 0 }
            val contentType =
                when (item.optString("content_type").trim().lowercase()) {
                    "movie" -> MetadataLabMediaType.MOVIE
                    "series" -> MetadataLabMediaType.SERIES
                    else -> if (season != null && episode != null) MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE
                }
            val title = item.optString("title").trim().ifEmpty { contentId }
            val watchedAt = normalizeEpochMs(item.opt("watched_at"))

            entries += WatchHistoryEntry(
                contentId = contentId,
                contentType = contentType,
                title = title,
                season = season,
                episode = episode,
                watchedAtEpochMs = watchedAt
            )
        }
        return entries
    }

    private fun normalizeEpochMs(raw: Any?): Long {
        val now = System.currentTimeMillis()
        val numericValue =
            when (raw) {
                is Number -> raw.toLong()
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) {
                        return now
                    }
                    trimmed.toLongOrNull() ?: runCatching {
                        Instant.parse(trimmed).toEpochMilli()
                    }.getOrElse {
                        return now
                    }
                }
                else -> return now
            }

        return if (numericValue in 1 until 10_000_000_000L) {
            numericValue * 1000L
        } else {
            numericValue
        }
    }

    private fun isTraktConnected(): Boolean {
        return watchHistoryService.authState().traktAuthenticated
    }

    private suspend fun ensureValidSession(): SupabaseSession? {
        val session = loadSession() ?: return null
        val expiresAt = session.expiresAtEpochSec
        val nowSec = System.currentTimeMillis() / 1000L
        if (expiresAt == null || expiresAt > nowSec + SESSION_EXPIRY_SKEW_SEC) {
            return session
        }

        if (session.refreshToken.isBlank()) {
            clearSession()
            return null
        }

        val refreshed = refreshSession(session.refreshToken)
        if (refreshed != null) {
            saveSession(refreshed)
            return refreshed
        }

        clearSession()
        return null
    }

    private suspend fun refreshSession(refreshToken: String): SupabaseSession? {
        val response =
            sendJsonRequest(
                method = "POST",
                url = "$baseUrl/auth/v1/token?grant_type=refresh_token",
                headers = baseHeaders(),
                payload = JSONObject().put("refresh_token", refreshToken)
            )
        if (!response.isSuccessful()) {
            return null
        }

        return parseSession(JSONObject(response.body.ifBlank { "{}" }))
    }

    private fun parseSession(json: JSONObject): SupabaseSession? {
        val accessToken = json.optString("access_token").trim()
        if (accessToken.isEmpty()) {
            return null
        }

        val refreshToken = json.optString("refresh_token").trim()
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0 }
        val expiresIn = json.optLong("expires_in", 0L).takeIf { it > 0 }
        val resolvedExpiresAt =
            expiresAt ?: expiresIn?.let { seconds ->
                (System.currentTimeMillis() / 1000L) + seconds
            }

        val user = json.optJSONObject("user")
        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = resolvedExpiresAt,
            userId = user?.optString("id")?.trim().orEmpty().ifEmpty { null },
            email = user?.optString("email")?.trim().orEmpty().ifEmpty { null },
            anonymous = user?.optBoolean("is_anonymous", false) == true
        )
    }

    private fun saveSession(session: SupabaseSession) {
        cachedSession = session
        prefs.edit().putString(KEY_SESSION, session.toJson().toString()).apply()
    }

    private fun clearSession() {
        cachedSession = null
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun loadSession(): SupabaseSession? {
        val existing = cachedSession
        if (existing != null) {
            return existing
        }

        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        val parsed = runCatching {
            SupabaseSession.fromJson(JSONObject(raw))
        }.getOrNull()
        cachedSession = parsed
        return parsed
    }

    private suspend fun callRpc(functionName: String, bearerToken: String, payload: JSONObject): Any? {
        val response =
            sendJsonRequest(
                method = "POST",
                url = "$baseUrl/rest/v1/rpc/$functionName",
                headers = authHeaders(bearerToken),
                payload = payload
            )
        if (!response.isSuccessful()) {
            throw IllegalStateException(extractErrorMessage(response.body) ?: "Supabase RPC '$functionName' failed")
        }
        return parseJsonBody(response.body)
    }

    private fun extractSyncCode(raw: Any?): String? {
        return when (raw) {
            is JSONArray -> {
                val first = raw.optJSONObject(0)
                first?.optString("code")?.trim()?.ifEmpty { null }
            }
            is JSONObject -> raw.optString("code").trim().ifEmpty { null }
            is String -> raw.trim().ifEmpty { null }
            else -> null
        }
    }

    private fun extractClaimResult(raw: Any?): ClaimResult {
        val candidate =
            when (raw) {
                is JSONArray -> raw.optJSONObject(0)
                is JSONObject -> raw
                else -> null
            }

        if (candidate == null) {
            return ClaimResult(success = false, message = "Sync code claim returned an empty response.")
        }

        val success =
            when {
                candidate.has("success") -> candidate.optBoolean("success", false)
                candidate.has("result_owner_id") -> candidate.optString("result_owner_id").trim().isNotEmpty()
                else -> false
            }

        val message =
            listOf("message", "msg", "error", "error_description")
                .firstNotNullOfOrNull { key ->
                    candidate.optString(key).trim().takeIf { it.isNotEmpty() }
                }
                .orEmpty()

        return ClaimResult(success = success, message = message)
    }

    private fun parseJsonBody(body: String): Any? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> trimmed
        }
    }

    private fun result(
        message: String,
        syncCode: String? = null,
        pushedAddons: Int = 0,
        pushedWatchedItems: Int = 0,
        pulledAddons: Int = 0,
        pulledWatchedItems: Int = 0
    ): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(
            statusMessage = message,
            authState = authState(),
            syncCode = syncCode,
            pushedAddons = pushedAddons,
            pushedWatchedItems = pushedWatchedItems,
            pulledAddons = pulledAddons,
            pulledWatchedItems = pulledWatchedItems
        )
    }

    private fun isConfigured(): Boolean {
        return baseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
    }

    private fun baseHeaders(): Map<String, String> {
        return mapOf(
            "apikey" to supabaseAnonKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
    }

    private fun authHeaders(accessToken: String): Map<String, String> {
        return baseHeaders() + mapOf("Authorization" to "Bearer $accessToken")
    }

    private fun extractErrorMessage(rawBody: String): String? {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        return runCatching {
            val parsed = parseJsonBody(trimmed)
            when (parsed) {
                is JSONObject -> {
                    listOf("message", "msg", "error_description", "error")
                        .firstNotNullOfOrNull { key ->
                            parsed.optString(key).trim().takeIf { it.isNotEmpty() }
                        }
                }
                is JSONArray -> {
                    val first = parsed.optJSONObject(0)
                    listOf("message", "msg", "error_description", "error")
                        .firstNotNullOfOrNull { key ->
                            first?.optString(key)?.trim()?.takeIf { it.isNotEmpty() }
                        }
                }
                is String -> parsed.takeIf { it.isNotBlank() }
                else -> null
            }
        }.getOrNull() ?: trimmed
    }

    private suspend fun sendJsonRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: JSONObject?
    ): HttpResult {
        val normalizedMethod = method.trim().uppercase(Locale.US)
        val okHeaders = headers.toOkHttpHeaders()
        val response =
            when (normalizedMethod) {
                "GET" ->
                    httpClient.get(
                        url = url.toHttpUrl(),
                        headers = okHeaders,
                        callTimeoutMs = 10_000L,
                    )

                "POST" ->
                    httpClient.postJson(
                        url = url.toHttpUrl(),
                        jsonBody = payload?.toString() ?: "{}",
                        headers = okHeaders,
                        callTimeoutMs = 10_000L,
                    )

                else -> throw IllegalArgumentException("Unsupported HTTP method for Supabase: $method")
            }

        return HttpResult(code = response.code, body = response.body)
    }

    private fun Map<String, String>.toOkHttpHeaders(): Headers {
        if (isEmpty()) return Headers.headersOf()
        val builder = Headers.Builder()
        forEach { (name, value) ->
            builder.add(name, value)
        }
        return builder.build()
    }

    companion object {
        private const val PREFS_NAME = "supabase_sync_lab"
        private const val KEY_SESSION = "@supabase:session"
        private const val DEVICE_NAME = "crispy-android-lab"
        private const val SESSION_EXPIRY_SKEW_SEC = 30L
    }
}

private data class SyncCounts(
    val status: String,
    val addons: Int,
    val watched: Int
)

private data class ClaimResult(
    val success: Boolean,
    val message: String
)

private data class HttpResult(
    val code: Int,
    val body: String
) {
    fun isSuccessful(): Boolean = code in 200..299
}

private data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long?,
    val userId: String?,
    val email: String?,
    val anonymous: Boolean
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("expires_at", expiresAtEpochSec)
            .put("user_id", userId)
            .put("email", email)
            .put("anonymous", anonymous)
    }

    companion object {
        fun fromJson(json: JSONObject): SupabaseSession? {
            val accessToken = json.optString("access_token").trim()
            if (accessToken.isEmpty()) {
                return null
            }

            return SupabaseSession(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token").trim(),
                expiresAtEpochSec = json.optLong("expires_at", 0L).takeIf { it > 0 },
                userId = json.optString("user_id").trim().ifEmpty { null },
                email = json.optString("email").trim().ifEmpty { null },
                anonymous = json.optBoolean("anonymous", false)
            )
        }
    }
}
