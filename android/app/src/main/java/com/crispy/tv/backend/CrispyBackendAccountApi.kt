package com.crispy.tv.backend

import com.crispy.tv.backend.ImportJobsResponse
import com.crispy.tv.backend.CrispyBackendClient.ImportProvider
import com.crispy.tv.backend.CrispyBackendClient.MeResponse
import com.crispy.tv.backend.CrispyBackendClient.Profile
import com.crispy.tv.backend.CrispyBackendClient.ProfileSettings
import com.crispy.tv.backend.CrispyBackendClient.ProviderAccountsResponse
import com.crispy.tv.backend.CrispyBackendClient.StartImportResult
import com.crispy.tv.backend.CrispyBackendClient.UpdateProfileInput
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
    val json = requireSuccess(response)
    val userJson = json.optJSONObject("user") ?: throw IllegalStateException("Backend /v1/me did not return a user.")
    return MeResponse(
        user = parseUser(userJson),
        profiles = parseProfiles(json.optJSONArray("profiles")),
    )
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
    val json = requireSuccess(response)
    val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return a created profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.listImportConnectionsApi(
    accessToken: String,
    profileId: String,
): ProviderAccountsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return ProviderAccountsResponse(
        providerStates = parseProviderStates(json.optJSONArray("providerStates")),
    )
}

internal suspend fun CrispyBackendClient.listImportJobsApi(accessToken: String, profileId: String): ImportJobsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return ImportJobsResponse(
        jobs = parseImportJobs(json.optJSONArray("jobs")),
    )
}

internal suspend fun CrispyBackendClient.startImportApi(
    accessToken: String,
    profileId: String,
    provider: ImportProvider,
    action: String,
    clientId: String,
    returnTo: String,
): StartImportResult {
    checkConfigured()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports/start".toHttpUrl(),
        jsonBody = JSONObject()
            .put("provider", provider.apiValue)
            .put("action", action)
            .put("clientId", clientId)
            .put("returnTo", returnTo)
            .toString(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val jobJson = json.optJSONObject("job") ?: throw IllegalStateException("Backend did not return an import job.")
    val providerStateJson = json.optJSONObject("providerState") ?: throw IllegalStateException("Backend did not return a provider state.")
    return StartImportResult(
        job = parseImportJob(jobJson),
        providerState = parseProviderState(providerStateJson),
        authUrl = json.optString("authUrl").trim().ifBlank { null },
        nextAction = json.optString("nextAction").trim().ifBlank { "queued" },
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
    val json = requireSuccess(response)
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
    val json = requireSuccess(response)
    return ProfileSettings(
        settings = json.optJSONObject("settings").toStringMap(),
    )
}

internal suspend fun CrispyBackendClient.disconnectImportConnectionApi(
    accessToken: String,
    profileId: String,
    provider: ImportProvider,
): CrispyBackendClient.ProviderState {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections/${provider.apiValue}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val providerStateJson = json.optJSONObject("providerState") ?: throw IllegalStateException("Backend did not return a provider state.")
    return parseProviderState(providerStateJson)
}

internal suspend fun CrispyBackendClient.listProfilesApi(accessToken: String): List<Profile> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseProfiles(json.optJSONArray("profiles"))
}

internal suspend fun CrispyBackendClient.updateProfileApi(
    accessToken: String,
    profileId: String,
    input: UpdateProfileInput,
): Profile {
    checkConfigured()
    val payload = JSONObject().apply {
        if (input.name != null) put("name", input.name.trim())
        if (input.isKids != null) put("isKids", input.isKids)
        if (input.avatarKey != null) put("avatarKey", input.avatarKey.trim())
        if (input.sortOrder != null) put("sortOrder", input.sortOrder)
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/profiles/${profileId.trim()}".toHttpUrl())
            .headers(authHeaders(accessToken))
            .patch(payload.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return an updated profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.getAccountSettingsApi(accessToken: String): AccountSettings {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/account".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAccountSettings(json)
}

internal suspend fun CrispyBackendClient.patchAccountSettingsApi(
    accessToken: String,
    email: String? = null,
    currentPassword: String? = null,
    newPassword: String? = null,
): AccountSettings {
    checkConfigured()
    val payload = JSONObject().apply {
        if (email != null) put("email", email.trim())
        if (currentPassword != null) put("currentPassword", currentPassword)
        if (newPassword != null) put("newPassword", newPassword)
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/account".toHttpUrl())
            .headers(authHeaders(accessToken))
            .patch(payload.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAccountSettings(json)
}

internal suspend fun CrispyBackendClient.deleteAccountApi(accessToken: String): Boolean {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/account".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return response.code in 200..299
}

internal suspend fun CrispyBackendClient.listProfileAddonsApi(
    accessToken: String,
    profileId: String,
): List<AddonSetting> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/addons".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAddonSettings(json.optJSONArray("addons"))
}
