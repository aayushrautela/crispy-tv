package com.crispy.tv.accounts

import com.crispy.tv.backend.jsonPrimitiveOrNull
import com.crispy.tv.backend.optJsonObject
import com.crispy.tv.backend.optLongOrDefault
import com.crispy.tv.backend.optStringOrEmpty
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Supabase's auth endpoints, in `commonMain`.
 *
 * **Three things had to be changed to get here, and each is a port rather than
 * a shim.** The constructor's `appContext: Context` was deleted rather than
 * replaced — it was read nowhere, so `Context` was pinning the file for a value
 * it did not want. `SecureTokenStore` became [AccountSessionStore], because the
 * store is Android Keystore and this class is not. And `nowMs` is a **required**
 * slot: see its KDoc, which is the one place worth reading in this file.
 */
class SupabaseAccountClient(
    private val httpClient: CrispyHttpClient,
    supabaseUrl: String,
    private val supabasePublishableKey: String,
    private val tokenStore: AccountSessionStore,
    /**
     * Wall-clock milliseconds since the epoch, and **required rather than
     * defaulted for a reason that is not "be strict"**: a defaulted
     * implementation would have to be `System.currentTimeMillis()`, which is
     * JVM API and cannot exist in a source set that compiles for Kotlin/Native.
     * A default would therefore have compiled on every target this repository
     * currently builds and silently kept the pin it was added to remove.
     *
     * **`:platform-core`'s `MonotonicClock` is the wrong clock, not a competing
     * one.** `shouldRefresh` compares this value against the server's
     * `expires_at`, which is epoch seconds off the wall clock, and a monotonic
     * source has an arbitrary origin — so reusing it would have produced a
     * number that is a correct duration and a nonsense instant. That is a
     * category error rather than a duplication, which is why there is no second
     * clock interface here.
     */
    private val nowMs: () -> Long,
) : AccountApi {
    private val baseUrl: String = supabaseUrl.trim().trimEnd('/')
    private val sessionMutex = Mutex()

    override fun isConfigured(): Boolean {
        return baseUrl.isNotBlank() && supabasePublishableKey.isNotBlank()
    }

    override fun currentSession(): Session? {
        return tokenStore.current()
    }

    override suspend fun ensureValidSession(): Session? {
        val existing = loadSession() ?: return null
        if (!shouldRefresh(existing)) {
            return existing
        }

        if (!isConfigured()) {
            saveSession(null)
            return null
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

    override suspend fun signInWithEmail(email: String, password: String): Session {
        checkConfigured()
        val url = "$baseUrl/auth/v1/token?grant_type=password"
        val payload = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        val response = httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        // A malformed body threw `org.json`'s `JSONException` here and throws
        // `SerializationException` now. Nothing in the repository names the old
        // type -- zero `catch` sites, zero imports -- so the throw reaches the
        // same handler either way. That count is why this is a comment rather
        // than a migration note.
        val session = parseSession(Json.parseToJsonElement(body).jsonObject)
            ?: throw IllegalStateException("Sign-in did not return a session.")
        saveSession(session)
        return session
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
        metadata: Map<String, String?>,
    ): SignUpResult {
        checkConfigured()
        val url = "$baseUrl/auth/v1/signup"
        val payload = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            // A JSON null and an absent key are different events here: the
            // caller supplied the key with a null value, and dropping the key
            // would make the signup payload say something the caller did not.
            // This is the one distinction `JsonElement` was adopted to keep.
            val data = buildJsonObject {
                metadata.forEach { (key, value) ->
                    if (value != null) put(key, value) else put(key, JsonNull)
                }
            }
            if (data.isNotEmpty()) {
                put("data", data)
            }
        }.toString()
        val response = httpClient.postJson(url, payload, baseHeaders(), callTimeoutMs = CALL_TIMEOUT_MS)
        val body = requireSuccess(response)
        val json = Json.parseToJsonElement(body).jsonObject
        val session = parseSession(json)
        if (session != null) {
            saveSession(session)
            return SignUpResult(session = session, message = "Account created and signed in.")
        }
        val hasUser = json.optJsonObject("user")?.optStringOrEmpty("id")?.isNotBlank() == true
        return if (hasUser) {
            SignUpResult(
                session = null,
                message = "Account created. Confirm your email, then sign in."
            )
        } else {
            SignUpResult(session = null, message = "Account created.")
        }
    }

    override suspend fun signOut() {
        if (!isConfigured()) return
        val session = tokenStore.current()
        if (session != null && !session.accessToken.startsWith("cp_pat_")) {
            runCatching {
                val url = "$baseUrl/auth/v1/logout"
                httpClient.postJson(
                    url,
                    buildJsonObject { put("scope", "global") }.toString(),
                    authHeaders(session.accessToken),
                    callTimeoutMs = CALL_TIMEOUT_MS,
                )
            }
        }
        // Local clearing is performed by the caller (AccountBootstrapRepository.signOut) so that
        // server-side revocation and local wipes run under a single lock.
    }

    private fun checkConfigured() {
        if (!isConfigured()) throw IllegalStateException("Supabase is not configured.")
    }

    private suspend fun refreshSession(refreshToken: String): RefreshResult {
        val url = "$baseUrl/auth/v1/token?grant_type=refresh_token"
        val payload = buildJsonObject { put("refresh_token", refreshToken) }.toString()
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

        // **Not the same shape as the two parses above, deliberately.** Those
        // two receive a body that has already passed `requireSuccess`, so a
        // parse failure here is a contract violation. This one receives a raw
        // error body, and it is reached only when the refresh *failed*, where a
        // malformed body is expected rather than alarming. Sharing one
        // `parseJsonOrNull` helper across all three would have turned this case
        // into a `null` return -- an ordinary "no session" -- instead of a
        // throw, which is the one answer the caller here cannot act on.
        val session = parseSession(Json.parseToJsonElement(response.body).jsonObject)
            ?: return RefreshResult.TransientFailure("Refresh did not return a session.")
        return RefreshResult.Success(session)
    }

    private fun parseSession(json: JsonObject): Session? {
        val accessToken = json.optStringOrEmpty("access_token").trim()
        if (accessToken.isBlank()) return null

        val refreshToken = json.optStringOrEmpty("refresh_token").trim()
        val expiresAt =
            json.optLongOrDefault("expires_at", -1L).takeIf { it > 0L }
                ?: json.optLongOrDefault("expires_in", -1L)
                    .takeIf { it > 0L }
                    ?.let { (nowMs() / 1000L) + it }

        val user = json.optJsonObject("user")
        val userId = user?.optStringOrEmpty("id")?.trim().orEmpty().ifBlank { null }
        val email = user?.optStringOrEmpty("email")?.trim().orEmpty().ifBlank { null }
        // The `?: false` was dead before this port: `optBoolean` answers a
        // non-null `Boolean`, so a nullable-chain safe call already produced a
        // `Boolean`. It is live now because the nullable read is honest about a
        // JSON null, and `false` is the answer for "not stated".
        val anonymous = user?.jsonPrimitiveOrNull("is_anonymous")?.booleanOrNull ?: false

        return Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSec = expiresAt,
            userId = userId,
            email = email,
            anonymous = anonymous
        )
    }

    private fun baseHeaders(): Map<String, String> =
        mapOf(
            "apikey" to supabasePublishableKey,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )

    private fun authHeaders(token: String): Map<String, String> =
        baseHeaders() + ("Authorization" to "Bearer ${token.trim()}")

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
                    is JsonObject -> parsed
                    is JsonArray -> parsed.getOrNull(0) as? JsonObject
                    else -> null
                }
            obj?.firstNonBlank(
                "message",
                "msg",
                "error_description",
                "error"
            ) ?: (parsed as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: trimmed
        }.getOrElse { trimmed }
    }

    /**
     * A non-JSON body becomes a [JsonPrimitive] rather than a raw `String`,
     * because the old `Any` return had two shapes and the port has one node
     * type. That is what lets [extractErrorMessage] recover the original text
     * with `contentOrNull` -- and it is the reason the `startsWith("?")` pair
     * collapsed to one test: both arms used to build a different `org.json`
     * type, and they now build the same thing.
     *
     * **The throw is left standing on purpose.** The `runCatching` in
     * [extractErrorMessage] is this function's only guard, and moving the catch
     * in here would have left that one redundant rather than making this one
     * unreachable.
     */
    private fun parseJsonBody(body: String): JsonElement {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> Json.parseToJsonElement(trimmed)
            else -> JsonPrimitive(trimmed)
        }
    }

    private fun JsonObject.firstNonBlank(vararg keys: String): String? {
        for (key in keys) {
            val value = optStringOrEmpty(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun loadSession(): Session? = tokenStore.current()

    private suspend fun saveSession(session: Session?) {
        if (session == null) tokenStore.clear() else tokenStore.save(session)
    }

    private fun shouldRefresh(session: Session): Boolean {
        val expiresAt = session.expiresAtEpochSec ?: return false
        val nowEpochSec = nowMs() / 1000L
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
        private const val CALL_TIMEOUT_MS = 10_000L
        private const val SESSION_EXPIRY_SKEW_SEC = 30L
    }
}