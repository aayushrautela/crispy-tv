package com.crispy.tv.supabase

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.CrispyHttpClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class SupabaseLabSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var cachedSession: SupabaseSession? = null

    suspend fun getValidSession(httpClient: CrispyHttpClient): SupabaseSession? {
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

        val refreshed = refreshSession(httpClient, session.refreshToken)
        if (refreshed != null) {
            saveSession(refreshed)
            return refreshed
        }

        clearSession()
        return null
    }

    private suspend fun refreshSession(httpClient: CrispyHttpClient, refreshToken: String): SupabaseSession? {
        val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return null

        val url = "$baseUrl/auth/v1/token?grant_type=refresh_token".toHttpUrl()
        val response =
            httpClient.postJson(
                url = url,
                jsonBody = JSONObject().put("refresh_token", refreshToken).toString(),
                headers = baseHeaders(),
                callTimeoutMs = 10_000L,
            )
        if (response.code !in 200..299) {
            return null
        }

        val json = runCatching { JSONObject(response.body.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        return parseSessionFromAuthResponse(json)
    }

    private fun baseHeaders(): Headers {
        return Headers.Builder()
            .add("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    private fun parseSessionFromAuthResponse(json: JSONObject): SupabaseSession? {
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
            anonymous = user?.optBoolean("is_anonymous", false) == true,
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
        cachedSession?.let { return it }

        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        val parsed =
            runCatching {
                SupabaseSession.fromJson(JSONObject(raw))
            }.getOrNull()
        cachedSession = parsed
        return parsed
    }

    data class SupabaseSession(
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

    companion object {
        private const val PREFS_NAME = "supabase_sync_lab"
        private const val KEY_SESSION = "@supabase:session"
        private const val SESSION_EXPIRY_SKEW_SEC = 30L
    }
}
