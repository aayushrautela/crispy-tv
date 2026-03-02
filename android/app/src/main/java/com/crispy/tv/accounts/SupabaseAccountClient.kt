package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class SupabaseAccountClient(
    appContext: Context,
    private val httpClient: CrispyHttpClient,
    supabaseUrl: String,
    private val supabaseAnonKey: String,
) {
    private val baseUrl: String = supabaseUrl.trim().trimEnd('/')
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    data class HouseholdMembership(
        val householdId: String,
        val role: String,
    )

    data class Profile(
        val id: String,
        val householdId: String,
        val name: String,
        val avatar: String?,
        val orderIndex: Int,
        val lastActiveAt: String?,
    )

    data class ProfileData(
        val settings: Map<String, String>,
        val catalogPrefs: Map<String, String>,
        val traktAuth: Map<String, String>,
        val simklAuth: Map<String, String>,
        val updatedAt: String?,
    )

    data class HouseholdAddon(
        val url: String,
        val enabled: Boolean,
        val name: String?,
    )

    fun isConfigured(): Boolean {
        return baseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
    }

    fun clearLocalSession() {
        saveSession(null)
    }

    suspend fun ensureValidSession(): Session? {
        val existing = loadSession() ?: return null
        val expiresAt = existing.expiresAtEpochSec
        if (expiresAt == null) return existing

        val nowEpochSec = System.currentTimeMillis() / 1000L
        if (expiresAt > nowEpochSec + SESSION_EXPIRY_SKEW_SEC) return existing

        if (existing.refreshToken.isBlank()) {
            saveSession(null)
            return null
        }

        val refreshed = runCatching { refreshSession(existing.refreshToken) }.getOrNull()
        if (refreshed == null) {
            saveSession(null)
            return null
        }
        saveSession(refreshed)
        return refreshed
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

    suspend fun ensureHouseholdMembership(accessToken: String): HouseholdMembership {
        checkConfigured()
        val url = "$baseUrl/rest/v1/rpc/ensure_household_membership".toHttpUrl()
        val response = httpClient.postJson(url, "{}", authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val parsed = parseJsonBody(body)

        val obj =
            when (parsed) {
                is JSONArray -> parsed.optJSONObject(0)
                is JSONObject -> parsed
                else -> null
            } ?: throw IllegalStateException("Unexpected ensure_household_membership response.")

        val householdId = obj.optString("household_id").trim()
        val role = obj.optString("role").trim()
        if (householdId.isBlank()) throw IllegalStateException("Missing household_id.")
        if (role.isBlank()) throw IllegalStateException("Missing role.")
        return HouseholdMembership(householdId = householdId, role = role)
    }

    suspend fun listProfiles(accessToken: String, householdId: String): List<Profile> {
        checkConfigured()
        val url =
            "$baseUrl/rest/v1/profiles".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "select",
                    "id,household_id,name,avatar,order_index,last_active_at,created_at"
                )
                .addQueryParameter("household_id", "eq.${householdId.trim()}")
                .addQueryParameter("order", "order_index.asc,created_at.asc")
                .build()

        val response = httpClient.get(url, authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val hId = obj.optString("household_id").trim()
                val name = obj.optString("name").trim()
                val avatar = obj.optString("avatar").trim().ifBlank { null }
                val orderIndex = obj.optInt("order_index", 0)
                val lastActiveAt = obj.optString("last_active_at").trim().ifBlank { null }
                if (id.isBlank() || hId.isBlank() || name.isBlank()) continue
                add(
                    Profile(
                        id = id,
                        householdId = hId,
                        name = name,
                        avatar = avatar,
                        orderIndex = orderIndex,
                        lastActiveAt = lastActiveAt
                    )
                )
            }
        }
    }

    suspend fun createProfile(
        accessToken: String,
        householdId: String,
        name: String,
        orderIndex: Int,
        createdByUserId: String?,
    ): Profile {
        checkConfigured()
        val url =
            "$baseUrl/rest/v1/profiles".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "select",
                    "id,household_id,name,avatar,order_index,last_active_at,created_at"
                )
                .build()

        val payload =
            JSONObject()
                .put("household_id", householdId.trim())
                .put("name", name.trim())
                .put("order_index", orderIndex)
                .apply {
                    if (!createdByUserId.isNullOrBlank()) put("created_by", createdByUserId.trim())
                }
                .toString()

        val headers =
            Headers.Builder()
                .addAll(authHeaders(accessToken))
                .add("Prefer", "return=representation")
                .build()

        val response = httpClient.postJson(url, payload, headers, callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val arr = JSONArray(body)
        val obj = arr.optJSONObject(0) ?: throw IllegalStateException("Unexpected create profile response.")
        val id = obj.optString("id").trim()
        val hId = obj.optString("household_id").trim()
        val profileName = obj.optString("name").trim()
        val avatar = obj.optString("avatar").trim().ifBlank { null }
        val createdOrderIndex = obj.optInt("order_index", orderIndex)
        val lastActiveAt = obj.optString("last_active_at").trim().ifBlank { null }
        if (id.isBlank() || hId.isBlank() || profileName.isBlank()) {
            throw IllegalStateException("Missing profile fields in response.")
        }
        return Profile(
            id = id,
            householdId = hId,
            name = profileName,
            avatar = avatar,
            orderIndex = createdOrderIndex,
            lastActiveAt = lastActiveAt
        )
    }

    suspend fun getProfileData(accessToken: String, profileId: String): ProfileData {
        checkConfigured()
        val url =
            "$baseUrl/rest/v1/profile_data".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "select",
                    "settings,catalog_prefs,trakt_auth,simkl_auth,updated_at"
                )
                .addQueryParameter("profile_id", "eq.${profileId.trim()}")
                .addQueryParameter("limit", "1")
                .build()

        val response = httpClient.get(url, authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val arr = JSONArray(body)
        val obj = arr.optJSONObject(0) ?: JSONObject()

        val settings = obj.optJSONObject("settings")?.toStringMap() ?: emptyMap()
        val catalogPrefs = obj.optJSONObject("catalog_prefs")?.toStringMap() ?: emptyMap()
        val traktAuth = obj.optJSONObject("trakt_auth")?.toStringMap() ?: emptyMap()
        val simklAuth = obj.optJSONObject("simkl_auth")?.toStringMap() ?: emptyMap()
        val updatedAt = obj.optString("updated_at").trim().ifBlank { null }

        return ProfileData(
            settings = settings,
            catalogPrefs = catalogPrefs,
            traktAuth = traktAuth,
            simklAuth = simklAuth,
            updatedAt = updatedAt
        )
    }

    suspend fun upsertProfileData(
        accessToken: String,
        profileId: String,
        settings: Map<String, String>,
        catalogPrefs: Map<String, String>,
        traktAuth: Map<String, String>,
        simklAuth: Map<String, String>,
    ) {
        checkConfigured()
        val url = "$baseUrl/rest/v1/rpc/upsert_profile_data".toHttpUrl()
        val payload =
            JSONObject()
                .put("p_profile_id", profileId.trim())
                .put("p_settings", settings.toJsonObject())
                .put("p_catalog_prefs", catalogPrefs.toJsonObject())
                .put("p_trakt_auth", traktAuth.toJsonObject())
                .put("p_simkl_auth", simklAuth.toJsonObject())
                .toString()

        val response = httpClient.postJson(url, payload, authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        requireSuccess(response)
    }

    suspend fun getHouseholdAddons(accessToken: String): List<HouseholdAddon> {
        checkConfigured()
        val url = "$baseUrl/rest/v1/rpc/get_household_addons".toHttpUrl()
        val response = httpClient.postJson(url, "{}", authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val urlValue = obj.optString("url").trim()
                if (urlValue.isBlank()) continue
                val enabled = obj.optBoolean("enabled", true)
                val name = obj.optString("name").trim().ifBlank { null }
                add(HouseholdAddon(url = urlValue, enabled = enabled, name = name))
            }
        }
    }

    suspend fun replaceHouseholdAddons(accessToken: String, addons: List<HouseholdAddon>): Int? {
        checkConfigured()
        val url = "$baseUrl/rest/v1/rpc/replace_household_addons".toHttpUrl()

        val arr = JSONArray()
        for (addon in addons) {
            val obj =
                JSONObject()
                    .put("url", addon.url.trim())
                    .put("enabled", addon.enabled)
                    .apply {
                        if (!addon.name.isNullOrBlank()) put("name", addon.name.trim())
                    }
            arr.put(obj)
        }

        val payload = JSONObject().put("p_addons", arr).toString()
        val response = httpClient.postJson(url, payload, authHeaders(accessToken), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        return parseIntBody(body)
    }

    private fun checkConfigured() {
        if (!isConfigured()) throw IllegalStateException("Supabase is not configured.")
    }

    private suspend fun refreshSession(refreshToken: String): Session {
        val url = "$baseUrl/auth/v1/token?grant_type=refresh_token".toHttpUrl()
        val payload = JSONObject().put("refresh_token", refreshToken).toString()
        val response = httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        return parseSession(JSONObject(body))
            ?: throw IllegalStateException("Refresh did not return a session.")
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
            .add("apikey", supabaseAnonKey)
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

    private fun JSONObject.toStringMap(): Map<String, String> {
        val iter = keys()
        val result = mutableMapOf<String, String>()
        while (iter.hasNext()) {
            val key = iter.next()
            val rawValue = opt(key)
            if (rawValue == null || rawValue == JSONObject.NULL) continue
            result[key] = rawValue.toString()
        }
        return result
    }

    private fun Map<String, String>.toJsonObject(): JSONObject {
        val obj = JSONObject()
        for (key in keys.sorted()) {
            obj.put(key, getValue(key))
        }
        return obj
    }

    private fun parseIntBody(body: String): Int? {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null
        return trimmed.toIntOrNull() ?: runCatching {
            fun extractIntFromObject(obj: JSONObject?): Int? {
                if (obj == null) return null
                val iter = obj.keys()
                if (!iter.hasNext()) return null
                val firstKey = iter.next()
                val value = obj.opt(firstKey)
                return when (value) {
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
            }

            when (val parsed = parseJsonBody(trimmed)) {
                is JSONObject -> extractIntFromObject(parsed)
                is JSONArray -> extractIntFromObject(parsed.optJSONObject(0))
                else -> null
            }
        }.getOrNull()
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun loadSession(): Session? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
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
    }

    private fun saveSession(session: Session?) {
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

    private companion object {
        private const val PREFS_NAME = "supabase_sync_lab"
        private const val KEY_SESSION = "@supabase:session"

        private const val CALL_TIMEOUT_MS = 10_000L
        private const val SESSION_EXPIRY_SKEW_SEC = 30L
    }
}
