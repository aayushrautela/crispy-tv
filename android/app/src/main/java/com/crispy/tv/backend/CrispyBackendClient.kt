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
        val email: String?,
    )

    data class Profile(
        val id: String,
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
        val accountSettings: AccountSettings,
        val profiles: List<Profile>,
    )

    data class AccountSettings(
        val settings: Map<String, String>,
        val hasOpenRouterKey: Boolean,
        val hasOmdbApiKey: Boolean,
    )

    data class AccountSecret(
        val key: String,
        val value: String,
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

    data class BackendMetadataItem(
        val id: String,
        val title: String,
        val summary: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val mediaType: String,
        val rating: String?,
        val year: String?,
        val genre: String?,
    )

    data class MetadataSearchResponse(
        val items: List<BackendMetadataItem>,
    )

    data class AiSearchResponse(
        val items: List<BackendMetadataItem>,
    )

    data class AiInsightsCard(
        val type: String,
        val title: String,
        val category: String,
        val content: String,
    )

    data class AiInsightsResponse(
        val insights: List<AiInsightsCard>,
        val trivia: String,
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
        return MeResponse(
            user = parseUser(userJson),
            accountSettings = parseAccountSettings(json.optJSONObject("accountSettings")),
            profiles = parseProfiles(json.optJSONArray("profiles")),
        )
    }

    suspend fun getAccountSettings(accessToken: String): AccountSettings {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/account/settings".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return parseAccountSettings(json.optJSONObject("settings"))
    }

    suspend fun patchAccountSettings(accessToken: String, settings: Map<String, String>): AccountSettings {
        checkConfigured()
        val payload = JSONObject().apply {
            settings.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = httpClient.execute(
            request = okhttp3.Request.Builder()
                .url("$baseUrl/v1/account/settings".toHttpUrl())
                .headers(authHeaders(accessToken))
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return parseAccountSettings(json.optJSONObject("settings"))
    }

    suspend fun getOpenRouterSecret(accessToken: String): AccountSecret? {
        return getAccountSecret(accessToken, "openrouter-key")
    }

    suspend fun putOpenRouterSecret(accessToken: String, value: String): AccountSecret {
        return putAccountSecret(accessToken, "openrouter-key", value)
    }

    suspend fun deleteOpenRouterSecret(accessToken: String): Boolean {
        return deleteAccountSecret(accessToken, "openrouter-key")
    }

    suspend fun getOmdbApiSecret(accessToken: String): AccountSecret? {
        return getAccountSecret(accessToken, "omdb-api-key")
    }

    suspend fun putOmdbApiSecret(accessToken: String, value: String): AccountSecret {
        return putAccountSecret(accessToken, "omdb-api-key", value)
    }

    suspend fun deleteOmdbApiSecret(accessToken: String): Boolean {
        return deleteAccountSecret(accessToken, "omdb-api-key")
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

    suspend fun searchTitles(
        accessToken: String,
        query: String,
        filter: String? = null,
        locale: String? = null,
        limit: Int = 20,
    ): MetadataSearchResponse {
        checkConfigured()
        val url = "$baseUrl/v1/search/titles".toHttpUrl().newBuilder()
            .addQueryParameter("query", query.trim())
            .apply {
                if (!filter.isNullOrBlank()) {
                    addQueryParameter("filter", filter)
                }
                if (!locale.isNullOrBlank()) {
                    addQueryParameter("locale", locale)
                }
            }
            .addQueryParameter("limit", limit.toString())
            .build()
        val response = httpClient.get(
            url = url,
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return MetadataSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
    }

    suspend fun searchTitlesByGenre(
        accessToken: String,
        genre: String,
        filter: String? = null,
        locale: String? = null,
        limit: Int = 20,
    ): MetadataSearchResponse {
        checkConfigured()
        val url = "$baseUrl/v1/search/titles".toHttpUrl().newBuilder()
            .addQueryParameter("genre", genre.trim())
            .apply {
                if (!filter.isNullOrBlank()) {
                    addQueryParameter("filter", filter)
                }
                if (!locale.isNullOrBlank()) {
                    addQueryParameter("locale", locale)
                }
            }
            .addQueryParameter("limit", limit.toString())
            .build()
        val response = httpClient.get(
            url = url,
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return MetadataSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
    }

    suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        filter: String? = null,
        locale: String? = null,
    ): AiSearchResponse {
        checkConfigured()
        val payload = JSONObject().apply {
            put("query", query.trim())
            if (!filter.isNullOrBlank()) put("filter", filter)
            if (!locale.isNullOrBlank()) put("locale", locale)
        }.toString()
        val response = httpClient.postJson(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/search".toHttpUrl(),
            jsonBody = payload,
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return AiSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
    }

    suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        tmdbId: Int,
        mediaType: String,
        locale: String? = null,
    ): AiInsightsResponse {
        checkConfigured()
        val payload = JSONObject().apply {
            put("tmdbId", tmdbId)
            put("mediaType", mediaType)
            if (!locale.isNullOrBlank()) put("locale", locale)
        }.toString()
        val response = httpClient.postJson(
            url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/insights".toHttpUrl(),
            jsonBody = payload,
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return AiInsightsResponse(
            insights = parseAiInsightsCards(json.optJSONArray("insights")),
            trivia = json.optString("trivia").trim(),
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
        val name = json.optString("name").trim()
        if (id.isBlank() || name.isBlank()) {
            throw IllegalStateException("Backend profile is missing required fields.")
        }
        return Profile(
            id = id,
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

    private fun parseAccountSettings(json: JSONObject?): AccountSettings {
        val settingsJson = json ?: JSONObject()
        val rawSettings = settingsJson.toStringMap()
        val hasOpenRouterKey = settingsJson.optJSONObject("ai")?.optBoolean("hasOpenRouterKey", false) == true
        val hasOmdbApiKey = settingsJson.optJSONObject("metadata")?.optBoolean("hasOmdbApiKey", false) == true
        return AccountSettings(
            settings = rawSettings,
            hasOpenRouterKey = hasOpenRouterKey,
            hasOmdbApiKey = hasOmdbApiKey,
        )
    }

    private fun parseMetadataItems(array: JSONArray?): List<BackendMetadataItem> {
        val safeArray = array ?: JSONArray()
        return buildList {
            for (index in 0 until safeArray.length()) {
                val item = safeArray.optJSONObject(index) ?: continue
                add(parseMetadataItem(item))
            }
        }
    }

    private fun parseMetadataItem(json: JSONObject): BackendMetadataItem {
        val id = json.optString("id").trim()
        val title = json.optString("title").trim()
        if (id.isBlank() || title.isBlank()) {
            throw IllegalStateException("Backend metadata item is missing required fields.")
        }
        val genre = json.optJSONArray("genres")?.optString(0)?.trim().takeUnless { it.isNullOrBlank() }
        return BackendMetadataItem(
            id = id,
            title = title,
            summary = json.optString("summary").trim().ifBlank { null },
            posterUrl = json.optJSONObject("images")?.optString("posterUrl")?.trim().ifBlank { null },
            backdropUrl = json.optJSONObject("images")?.optString("backdropUrl")?.trim().ifBlank { null },
            logoUrl = json.optJSONObject("images")?.optString("logoUrl")?.trim().ifBlank { null },
            mediaType = json.optString("mediaType").trim().ifBlank { "movie" },
            rating = json.opt("rating")?.toString()?.trim().ifBlank { null },
            year = json.opt("releaseYear")?.toString()?.trim().ifBlank { null },
            genre = genre,
        )
    }

    private fun parseAiInsightsCards(array: JSONArray?): List<AiInsightsCard> {
        val safeArray = array ?: JSONArray()
        return buildList {
            for (index in 0 until safeArray.length()) {
                val item = safeArray.optJSONObject(index) ?: continue
                add(
                    AiInsightsCard(
                        type = item.optString("type").trim(),
                        title = item.optString("title").trim(),
                        category = item.optString("category").trim(),
                        content = item.optString("content").trim(),
                    )
                )
            }
        }
    }

    private suspend fun getAccountSecret(accessToken: String, pathSuffix: String): AccountSecret? {
        checkConfigured()
        val response = httpClient.get(
            url = "$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl(),
            headers = authHeaders(accessToken),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        if (response.code == 404) {
            return null
        }
        val json = JSONObject(requireSuccess(response))
        return parseAccountSecret(json.optJSONObject("secret"))
    }

    private suspend fun putAccountSecret(accessToken: String, pathSuffix: String, value: String): AccountSecret {
        checkConfigured()
        val payload = JSONObject().put("value", value.trim()).toString()
        val response = httpClient.execute(
            request = okhttp3.Request.Builder()
                .url("$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl())
                .headers(authHeaders(accessToken))
                .put(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return parseAccountSecret(json.optJSONObject("secret"))
            ?: throw IllegalStateException("Backend did not return an account secret.")
    }

    private suspend fun deleteAccountSecret(accessToken: String, pathSuffix: String): Boolean {
        checkConfigured()
        val response = httpClient.execute(
            request = okhttp3.Request.Builder()
                .url("$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl())
                .headers(authHeaders(accessToken))
                .delete()
                .build(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val json = JSONObject(requireSuccess(response))
        return json.optBoolean("deleted", false)
    }

    private fun parseAccountSecret(json: JSONObject?): AccountSecret? {
        val secretJson = json ?: return null
        val key = secretJson.optString("key").trim()
        val value = secretJson.optString("value").trim()
        if (key.isBlank() || value.isBlank()) {
            return null
        }
        return AccountSecret(key = key, value = value)
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
