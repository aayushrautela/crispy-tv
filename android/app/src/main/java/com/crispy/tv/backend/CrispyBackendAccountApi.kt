package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AccountSecret
import com.crispy.tv.backend.CrispyBackendClient.AccountSettings
import com.crispy.tv.backend.CrispyBackendClient.ImportConnectionsResponse
import com.crispy.tv.backend.CrispyBackendClient.ImportJobsResponse
import com.crispy.tv.backend.CrispyBackendClient.ImportProvider
import com.crispy.tv.backend.CrispyBackendClient.MeResponse
import com.crispy.tv.backend.CrispyBackendClient.Profile
import com.crispy.tv.backend.CrispyBackendClient.ProfileSettings
import com.crispy.tv.backend.CrispyBackendClient.StartImportResult
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal suspend fun CrispyBackendClient.getMeApi(accessToken: String): MeResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/me".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    val userJson = json.optJSONObject("user") ?: throw IllegalStateException("Backend /v1/me did not return a user.")
    return MeResponse(
        user = parseUser(userJson),
        accountSettings = parseAccountSettings(json.optJSONObject("accountSettings")),
        profiles = parseProfiles(json.optJSONArray("profiles")),
    )
}

internal suspend fun CrispyBackendClient.getAccountSettingsApi(accessToken: String): AccountSettings {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/account/settings".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseAccountSettings(json.optJSONObject("settings"))
}

internal suspend fun CrispyBackendClient.patchAccountSettingsApi(
    accessToken: String,
    settings: Map<String, String>,
): AccountSettings {
    checkConfigured()
    val payload = JSONObject().apply {
        settings.forEach { (key, value) -> put(key, value) }
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/account/settings".toHttpUrl())
            .headers(authHeaders(accessToken))
            .patch(payload.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseAccountSettings(json.optJSONObject("settings"))
}

internal suspend fun CrispyBackendClient.getOpenRouterSecretApi(accessToken: String): AccountSecret? {
    return getAccountSecretApi(accessToken, "openrouter-key")
}

internal suspend fun CrispyBackendClient.putOpenRouterSecretApi(accessToken: String, value: String): AccountSecret {
    return putAccountSecretApi(accessToken, "openrouter-key", value)
}

internal suspend fun CrispyBackendClient.deleteOpenRouterSecretApi(accessToken: String): Boolean {
    return deleteAccountSecretApi(accessToken, "openrouter-key")
}

internal suspend fun CrispyBackendClient.getOmdbApiSecretApi(accessToken: String): AccountSecret? {
    return getAccountSecretApi(accessToken, "omdb-api-key")
}

internal suspend fun CrispyBackendClient.putOmdbApiSecretApi(accessToken: String, value: String): AccountSecret {
    return putAccountSecretApi(accessToken, "omdb-api-key", value)
}

internal suspend fun CrispyBackendClient.deleteOmdbApiSecretApi(accessToken: String): Boolean {
    return deleteAccountSecretApi(accessToken, "omdb-api-key")
}

internal suspend fun CrispyBackendClient.createProfileApi(
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
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return a created profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.listImportConnectionsApi(
    accessToken: String,
    profileId: String,
): ImportConnectionsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return ImportConnectionsResponse(
        connections = parseImportConnections(json.optJSONArray("connections")),
        watchDataOrigin = json.optJSONObject("watchDataState")?.optString("origin")?.trim().orEmpty().ifBlank { null },
    )
}

internal suspend fun CrispyBackendClient.listImportJobsApi(accessToken: String, profileId: String): ImportJobsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return ImportJobsResponse(
        jobs = parseImportJobs(json.optJSONArray("jobs")),
        watchDataOrigin = json.optJSONObject("watchDataState")?.optString("origin")?.trim().orEmpty().ifBlank { null },
    )
}

internal suspend fun CrispyBackendClient.startImportApi(
    accessToken: String,
    profileId: String,
    provider: ImportProvider,
): StartImportResult {
    checkConfigured()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports/start".toHttpUrl(),
        jsonBody = JSONObject().put("provider", provider.apiValue).toString(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
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

internal suspend fun CrispyBackendClient.getProfileSettingsApi(
    accessToken: String,
    profileId: String,
): ProfileSettings {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/settings".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return ProfileSettings(
        settings = json.optJSONObject("settings").toStringMap(),
    )
}

internal suspend fun CrispyBackendClient.patchProfileSettingsApi(
    accessToken: String,
    profileId: String,
    settings: Map<String, String>,
): ProfileSettings {
    checkConfigured()
    val payload = JSONObject().apply {
        settings.forEach { (key, value) -> put(key, value) }
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/profiles/${profileId.trim()}/settings".toHttpUrl())
            .headers(authHeaders(accessToken))
            .patch(payload.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return ProfileSettings(
        settings = json.optJSONObject("settings").toStringMap(),
    )
}

internal suspend fun CrispyBackendClient.disconnectImportConnectionApi(
    accessToken: String,
    profileId: String,
    provider: ImportProvider,
): CrispyBackendClient.ImportConnection {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections/${provider.apiValue}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    val connectionJson = json.optJSONObject("connection") ?: throw IllegalStateException("Backend did not return a provider connection.")
    return parseImportConnection(connectionJson)
}

private suspend fun CrispyBackendClient.getAccountSecretApi(accessToken: String, pathSuffix: String): AccountSecret? {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    if (response.code == 404) {
        return null
    }
    val json = JSONObject(requireSuccess(response))
    return parseAccountSecret(json.optJSONObject("secret"))
}

private suspend fun CrispyBackendClient.putAccountSecretApi(
    accessToken: String,
    pathSuffix: String,
    value: String,
): AccountSecret {
    checkConfigured()
    val payload = JSONObject().put("value", value.trim()).toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl())
            .headers(authHeaders(accessToken))
            .put(payload.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseAccountSecret(json.optJSONObject("secret"))
        ?: throw IllegalStateException("Backend did not return an account secret.")
}

private suspend fun CrispyBackendClient.deleteAccountSecretApi(accessToken: String, pathSuffix: String): Boolean {
    checkConfigured()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/account/secrets/$pathSuffix".toHttpUrl())
            .headers(authHeaders(accessToken))
            .delete()
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return json.optBoolean("deleted", false)
}
