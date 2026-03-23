package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class SupabaseAccountClient(
    appContext: Context,
    private val httpClient: CrispyHttpClient,
    supabaseUrl: String,
    private val supabasePublishableKey: String,
) {
    private val baseUrl: String = supabaseUrl.trim().trimEnd('/')
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionMutex = Mutex()

    @Volatile
    private var cachedSession: Session? = null

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSec: Long?,
        val userId: String?,
        val email: String?,
        val anonymous: Boolean,
    )

    data class SignUpResult(
        val session: Session?,
        val message: String,
    )

    fun isConfigured(): Boolean {
        return baseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()
    }

    fun clearLocalSession() {
        saveSession(null)
    }

    fun currentSession(): Session? {
        return loadSession()
    }

    suspend fun ensureValidSession(): Session? {
        val existing = loadSession() ?: return null
        if (!shouldRefresh(existing)) {
            return existing
        }

        return sessionMutex.withLock {
            val latest = loadSession() ?: return@withLock null
            if (!shouldRefresh(latest)) {
                return@withLock latest
            }
            if (latest.refreshToken.isBlank()) {
                saveSession(null)
                return@withLock null
            }

            when (val refreshResult = refreshSession(latest.refreshToken)) {
                is RefreshResult.Success -> {
                    saveSession(refreshResult.session)
                    refreshResult.session
                }

                RefreshResult.InvalidSession -> {
                    saveSession(null)
                    null
                }

                is RefreshResult.TransientFailure -> latest
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Session {
        checkConfigured()
        val url = "$baseUrl/auth/v1/token?grant_type=password".toHttpUrl()
        val payload = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val session = parseSession(JSONObject(body))
            ?: throw IllegalStateException("Sign-in did not return a session.")
        saveSession(session)
        return session
    }

    suspend fun signUpWithEmail(email: String, password: String): SignUpResult {
        checkConfigured()
        val url = "$baseUrl/auth/v1/signup".toHttpUrl()
        val payload = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val json = JSONObject(body)
        val session = parseSession(json)
        if (session != null) {
            saveSession(session)
            return SignUpResult(session = session, message = "Account created and signed in.")
        }
        val hasUser = json.optJSONObject("user")?.optString("id")?.isNotBlank() == true
        return if (hasUser) {
            SignUpResult(
                session = null,
                message = "Account created. Confirm your email, then sign in."
            )
        } else {
            SignUpResult(session = null, message = "Account created.")
        }
    }

    suspend fun signOut() {
        if (!isConfigured()) {
            saveSession(null)
            return
        }
        val session = loadSession()
        if (session != null) {
            runCatching {
                val url = "$baseUrl/auth/v1/logout".toHttpUrl()
                httpClient.postJson(url, "{}", authHeaders(session.accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
            }
        }
        saveSession(null)
    }

    private fun checkConfigured() {
        if (!isConfigured()) throw IllegalStateException("Supabase is not configured.")
    }

    private suspend fun refreshSession(refreshToken: String): RefreshResult {
        val url = "$baseUrl/auth/v1/token?grant_type=refresh_token".toHttpUrl()
        val payload = JSONObject().put("refresh_token", refreshToken).toString()
        val response =
            runCatching {
                httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
            }.getOrElse { error ->
                return RefreshResult.TransientFailure(error.message)
            }

        if (response.code !in 200..299) {
            return if (isInvalidRefreshResponse(response)) {
                RefreshResult.InvalidSession
            } else {
                RefreshResult.TransientFailure(extractErrorMessage(response.body))
            }
        }

        val session = parseSession(JSONObject(response.body))
            ?: return RefreshResult.TransientFailure("Refresh did not return a session.")
        return RefreshResult.Success(session)
    }

    private fun parseSession(json: JSONObject): Session? {
        val accessToken = json.optString("access_token").trim()
        if (accessToken.isBlank()) return null

        val refreshToken = json.optString("refresh_token").trim()
        val expiresAt =
            json.optLong("expires_at", -1L).takeIf { it > 0L }
                ?: json.optLong("expires_in", -1L)
                    .takeIf { it > 0L }
                    ?.let { (System.currentTimeMillis() / 1000L) + it }

        val user = json.optJSONObject("user")
        val userId = user?.optString("id")?.trim().orEmpty().ifBlank { null }
        val email = user?.optString("email")?.trim().orEmpty().ifBlank { null }
        val anonymous = user?.optBoolean("is_anonymous", false) ?: false

        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            email = email,
            anonymous = anonymous
        )
    }

    private fun baseHeaders(): Headers {
        return Headers.Builder()
            .add("apikey", supabasePublishableKey)
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    private fun authHeaders(token: String): Headers {
        return Headers.Builder()
            .addAll(baseHeaders())
            .add("Authorization", "Bearer ${token.trim()}")
            .build()
    }

    private fun requireSuccess(response: CrispyHttpResponse): String {
        if (response.code in 200..299) return response.body
        val message = extractErrorMessage(response.body)
        throw IllegalStateException(message ?: "HTTP ${response.code}")
    }

    private fun extractErrorMessage(rawBody: String): String? {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val parsed = parseJsonBody(trimmed)
            val obj =
                when (parsed) {
                    is JSONObject -> parsed
                    is JSONArray -> parsed.optJSONObject(0)
                    else -> null
                }
            obj?.firstNonBlank(
                "message",
                "msg",
                "error_description",
                "error"
            ) ?: (parsed as? String)?.takeIf { it.isNotBlank() } ?: trimmed
        }.getOrElse { trimmed }
    }

    private fun parseJsonBody(body: String): Any {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> trimmed
        }
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun loadSession(): Session? {
        cachedSession?.let { return it }
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        val parsed = runCatching {
            val json = JSONObject(raw)
            val accessToken = json.optString("access_token").trim()
            if (accessToken.isBlank()) return null
            val refreshToken = json.optString("refresh_token").trim()
            val expiresAt = json.optLong("expires_at", -1L).takeIf { it > 0L }
            val userId = json.optString("user_id").trim().ifBlank { null }
            val email = json.optString("email").trim().ifBlank { null }
            val anonymous = json.optBoolean("anonymous", false)
            Session(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSec = expiresAt,
                userId = userId,
                email = email,
                anonymous = anonymous
            )
        }.getOrNull()
        cachedSession = parsed
        return parsed
    }

    private fun saveSession(session: Session?) {
        cachedSession = session
        if (session == null) {
            prefs.edit().remove(KEY_SESSION).apply()
            return
        }
        val json =
            JSONObject()
                .put("access_token", session.accessToken)
                .put("refresh_token", session.refreshToken)
                .put("expires_at", session.expiresAtEpochSec ?: JSONObject.NULL)
                .put("user_id", session.userId ?: JSONObject.NULL)
                .put("email", session.email ?: JSONObject.NULL)
                .put("anonymous", session.anonymous)
                .toString()

        prefs.edit().putString(KEY_SESSION, json).apply()
    }

    private fun shouldRefresh(session: Session): Boolean {
        val expiresAt = session.expiresAtEpochSec ?: return false
        val nowEpochSec = System.currentTimeMillis() / 1000L
        return expiresAt <= nowEpochSec + SESSION_EXPIRY_SKEW_SEC
    }

    private fun isInvalidRefreshResponse(response: CrispyHttpResponse): Boolean {
        if (response.code !in 400..401) {
            return false
        }
        val message = extractErrorMessage(response.body)?.lowercase().orEmpty()
        if (message.isBlank()) {
            return false
        }
        return message.contains("invalid refresh token") ||
            message.contains("invalid grant") ||
            message.contains("refresh token") && message.contains("invalid") ||
            message.contains("refresh token") && message.contains("expired") ||
            message.contains("session_not_found")
    }

    private sealed interface RefreshResult {
        data class Success(val session: Session) : RefreshResult

        data class TransientFailure(val message: String?) : RefreshResult

        data object InvalidSession : RefreshResult
    }

    private companion object {
        private const val PREFS_NAME = "supabase_sync_lab"
        private const val KEY_SESSION = "@supabase:session"

        private const val CALL_TIMEOUT_MS = 10_000L
        private const val SESSION_EXPIRY_SKEW_SEC = 30L
    }
}
