package com.crispy.tv.backend

import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CrispyBackendClient(
    private val httpClient: CrispyHttpClient,
    backendUrl: String,
) {
    private val baseUrl: String = backendUrl.trim().trimEnd('/')

    data class User(
        val id: String,
        val supabaseAuthUserId: String?,
        val email: String?,
    )

    data class Household(
        val id: String,
    )

    data class Profile(
        val id: String,
        val householdId: String,
        val name: String,
        val avatarKey: String?,
        val isKids: Boolean,
        val sortOrder: Int,
        val createdByUserId: String?,
        val createdAt: String?,
        val updatedAt: String?,
    )

    data class MeResponse(
        val user: User,
        val household: Household,
        val profiles: List<Profile>,
    )

    data class ProfileSettings(
        val settings: Map<String, String>,
    )

    enum class ImportProvider(val apiValue: String) {
        TRAKT("trakt"),
        SIMKL("simkl"),
    }

    data class ImportConnection(
        val id: String,
        val provider: String,
        val status: String,
        val providerUserId: String?,
        val externalUsername: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val lastUsedAt: String?,
        val lastImportJobId: String?,
        val lastImportCompletedAt: String?,
    )

    data class ImportJob(
        val id: String,
        val profileId: String,
        val householdId: String,
        val provider: String,
        val mode: String,
        val status: String,
        val requestedByUserId: String,
        val connectionId: String?,
        val errorMessage: String?,
        val createdAt: String?,
        val startedAt: String?,
        val finishedAt: String?,
        val updatedAt: String?,
    )

    data class ImportConnectionsResponse(
        val connections: List<ImportConnection>,
        val watchDataOrigin: String?,
    )

    data class ImportJobsResponse(
        val jobs: List<ImportJob>,
        val watchDataOrigin: String?,
    )

    data class StartImportResult(
        val job: ImportJob,
        val connection: ImportConnection?,
        val authUrl: String?,
        val nextAction: String,
        val watchDataOrigin: String?,
    )

    fun isConfigured(): Boolean {
        return baseUrl.isNotBlank()
    }

    suspend fun getMe(accessToken: String): MeResponse {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/me".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        val userJson = json.optJSONObject("user") ?: throw IllegalStateException("Backend /v1/me did not return a user.")
        val householdJson =
            json.optJSONObject("household") ?: throw IllegalStateException("Backend /v1/me did not return a household.")
        return MeResponse(
            user = parseUser(userJson),
            household = Household(id = householdJson.optString("id").trim().ifEmpty { throw IllegalStateException("Backend /v1/me returned a household without an id.") }),
            profiles = parseProfiles(json.optJSONArray("profiles")),
        )
    }

    suspend fun createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int? = null,
        isKids: Boolean = false,
        avatarKey: String? = null,
    ): Profile {
        checkConfigured()
        val payload = JSONObject().put("name", name.trim()).put("isKids", isKids).apply {
            if (sortOrder != null) {
                put("sortOrder", sortOrder)
            }
            if (!avatarKey.isNullOrBlank()) {
                put("avatarKey", avatarKey.trim())
            }
        }.toString()
        val response = httpClient.postJson(
            url = "$baseUrl/v1/profiles".toHttpUrl(),
            jsonBody = payload,
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return a created profile.")
        return parseProfile(profileJson)
    }

    suspend fun listImportConnections(accessToken: String, profileId: String): ImportConnectionsResponse {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return ImportConnectionsResponse(
            connections = parseImportConnections(json.optJSONArray("connections")),
            watchDataOrigin = json.optJSONObject("watchDataState")?.optString("origin")?.trim().orEmpty().ifBlank { null },
        )
    }

    suspend fun listImportJobs(accessToken: String, profileId: String): ImportJobsResponse {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/imports".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return ImportJobsResponse(
            jobs = parseImportJobs(json.optJSONArray("jobs")),
            watchDataOrigin = json.optJSONObject("watchDataState")?.optString("origin")?.trim().orEmpty().ifBlank { null },
        )
    }

    suspend fun startImport(accessToken: String, profileId: String, provider: ImportProvider): StartImportResult {
        checkConfigured()
        val response = httpClient.postJson(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/imports/start".toHttpUrl(),
            jsonBody = JSONObject().put("provider", provider.apiValue).toString(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        val jobJson = json.optJSONObject("job") ?: throw IllegalStateException("Backend did not return an import job.")
        return StartImportResult(
            job = parseImportJob(jobJson),
            connection = json.optJSONObject("connection")?.let(::parseImportConnection),
            authUrl = json.optString("authUrl").trim().ifBlank { null },
            nextAction = json.optString("nextAction").trim().ifBlank { "queued" },
            watchDataOrigin = json.optJSONObject("watchDataState")?.optString("origin")?.trim().orEmpty().ifBlank { null },
        )
    }

    suspend fun getProfileSettings(accessToken: String, profileId: String): ProfileSettings {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/settings".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return ProfileSettings(
            settings = json.optJSONObject("settings").toStringMap(),
        )
    }

    suspend fun patchProfileSettings(accessToken: String, profileId: String, settings: Map<String, String>): ProfileSettings {
        checkConfigured()
        val payload = JSONObject().apply {
            settings.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = httpClient.execute(
            request = okhttp3.Request.Builder()
                .url("$baseUrl/v1/profiles/${profileId.trim()}/settings".toHttpUrl())
                .headers(authHeaders(accessToken))
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return ProfileSettings(
            settings = json.optJSONObject("settings").toStringMap(),
        )
    }

    private fun checkConfigured() {
        if (!isConfigured()) {
            throw IllegalStateException("Backend API is not configured.")
        }
    }

    private fun authHeaders(accessToken: String): Headers {
        return Headers.Builder()
            .add("Authorization", "Bearer ${accessToken.trim()}")
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    private fun requireSuccess(response: CrispyHttpResponse): String {
        if (response.code in 200..299) {
            return response.body
        }
        throw IllegalStateException(extractErrorMessage(response.body) ?: "HTTP ${response.code}")
    }

    private fun extractErrorMessage(rawBody: String): String? {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        return json?.let {
            listOf("message", "error", "error_description")
                .firstNotNullOfOrNull { key -> it.optString(key).trim().takeIf(String::isNotBlank) }
        } ?: trimmed
    }

    private fun parseUser(json: JSONObject): User {
        val id = json.optString("id").trim()
        if (id.isBlank()) {
            throw IllegalStateException("Backend user is missing an id.")
        }
        return User(
            id = id,
            supabaseAuthUserId = json.optString("supabaseAuthUserId").trim().ifBlank { null },
            email = json.optString("email").trim().ifBlank { null },
        )
    }

    private fun parseProfiles(array: JSONArray?): List<Profile> {
        val safeArray = array ?: JSONArray()
        return buildList {
            for (index in 0 until safeArray.length()) {
                val profile = safeArray.optJSONObject(index) ?: continue
                add(parseProfile(profile))
            }
        }
    }

    private fun parseProfile(json: JSONObject): Profile {
        val id = json.optString("id").trim()
        val householdId = json.optString("householdId").trim()
        val name = json.optString("name").trim()
        if (id.isBlank() || householdId.isBlank() || name.isBlank()) {
            throw IllegalStateException("Backend profile is missing required fields.")
        }
        return Profile(
            id = id,
            householdId = householdId,
            name = name,
            avatarKey = json.optString("avatarKey").trim().ifBlank { null },
            isKids = json.optBoolean("isKids", false),
            sortOrder = json.optInt("sortOrder", 0),
            createdByUserId = json.optString("createdByUserId").trim().ifBlank { null },
            createdAt = json.optString("createdAt").trim().ifBlank { null },
            updatedAt = json.optString("updatedAt").trim().ifBlank { null },
        )
    }

    private fun parseImportConnections(array: JSONArray?): List<ImportConnection> {
        val safeArray = array ?: JSONArray()
        return buildList {
            for (index in 0 until safeArray.length()) {
                val connection = safeArray.optJSONObject(index) ?: continue
                add(parseImportConnection(connection))
            }
        }
    }

    private fun parseImportConnection(json: JSONObject): ImportConnection {
        return ImportConnection(
            id = json.optString("id").trim(),
            provider = json.optString("provider").trim(),
            status = json.optString("status").trim(),
            providerUserId = json.optString("providerUserId").trim().ifBlank { null },
            externalUsername = json.optString("externalUsername").trim().ifBlank { null },
            createdAt = json.optString("createdAt").trim().ifBlank { null },
            updatedAt = json.optString("updatedAt").trim().ifBlank { null },
            lastUsedAt = json.optString("lastUsedAt").trim().ifBlank { null },
            lastImportJobId = json.optString("lastImportJobId").trim().ifBlank { null },
            lastImportCompletedAt = json.optString("lastImportCompletedAt").trim().ifBlank { null },
        )
    }

    private fun parseImportJobs(array: JSONArray?): List<ImportJob> {
        val safeArray = array ?: JSONArray()
        return buildList {
            for (index in 0 until safeArray.length()) {
                val job = safeArray.optJSONObject(index) ?: continue
                add(parseImportJob(job))
            }
        }
    }

    private fun parseImportJob(json: JSONObject): ImportJob {
        return ImportJob(
            id = json.optString("id").trim(),
            profileId = json.optString("profileId").trim(),
            householdId = json.optString("householdId").trim(),
            provider = json.optString("provider").trim(),
            mode = json.optString("mode").trim(),
            status = json.optString("status").trim(),
            requestedByUserId = json.optString("requestedByUserId").trim(),
            connectionId = json.optString("connectionId").trim().ifBlank { null },
            errorMessage = json.optJSONObject("errorJson")?.optString("message")?.trim().orEmpty().ifBlank { null },
            createdAt = json.optString("createdAt").trim().ifBlank { null },
            startedAt = json.optString("startedAt").trim().ifBlank { null },
            finishedAt = json.optString("finishedAt").trim().ifBlank { null },
            updatedAt = json.optString("updatedAt").trim().ifBlank { null },
        )
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        val obj = this ?: return emptyMap()
        val keys = obj.keys()
        val result = linkedMapOf<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key) ?: continue
            val normalized =
                when (value) {
                    JSONObject.NULL -> null
                    is String -> value
                    is Number, is Boolean -> value.toString()
                    else -> value.toString()
                }?.trim()
            if (!normalized.isNullOrBlank()) {
                result[key] = normalized
            }
        }
        return result
    }

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
