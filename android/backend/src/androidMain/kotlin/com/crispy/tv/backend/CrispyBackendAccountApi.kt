package com.crispy.tv.backend

import com.crispy.tv.network.HttpMethod
import com.crispy.tv.network.HttpRequest
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun CrispyBackendClient.getMeApi(accessToken: String): MeResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/me",
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
    interfaceLanguage: String? = null,
): Profile {
    checkConfigured()
    val payload = JSONObject().put("name", name.trim()).put("isKids", isKids).apply {
        if (sortOrder != null) {
            put("sortOrder", sortOrder)
        }
        if (!avatarKey.isNullOrBlank()) {
            put("avatarUrl", avatarKey.trim())
        }
        if (!interfaceLanguage.isNullOrBlank()) {
            put("interfaceLanguage", interfaceLanguage.trim())
        }
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles",
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return a created profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.bootstrapAccountApi(
    accessToken: String,
    name: String,
    interfaceLanguage: String,
    avatarUrl: String,
    region: String? = null,
): Profile {
    checkConfigured()
    val payload = JSONObject()
        .put("name", name.trim())
        .put("interfaceLanguage", interfaceLanguage.trim())
        .put("avatarUrl", avatarUrl.trim())
        .apply { if (!region.isNullOrBlank()) put("region", region.trim()) }
        .toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/account/bootstrap",
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val profileJson = json.optJSONObject("profile")
        ?: throw IllegalStateException("Backend did not return a created profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.listImportConnectionsApi(
    accessToken: String,
    profileId: String,
): ProviderAccountsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections",
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports",
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/imports/start",
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/settings",
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
        request = HttpRequest(
            method = HttpMethod.PATCH,
            url = "$baseUrl/v1/profiles/${profileId.trim()}/settings",
            headers = authHeaders(accessToken),
            body = payload,
        ),
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
): ProviderState {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/import-connections/${provider.apiValue}",
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
        url = "$baseUrl/v1/profiles",
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
        request = HttpRequest(
            method = HttpMethod.PATCH,
            url = "$baseUrl/v1/profiles/${profileId.trim()}",
            headers = authHeaders(accessToken),
            body = payload,
        ),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val profileJson = json.optJSONObject("profile") ?: throw IllegalStateException("Backend did not return an updated profile.")
    return parseProfile(profileJson)
}

internal suspend fun CrispyBackendClient.getAccountSettingsApi(accessToken: String): AccountSettings {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/account/settings",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAccountSettings(json)
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
        request = HttpRequest(
            method = HttpMethod.PATCH,
            url = "$baseUrl/v1/account/settings",
            headers = authHeaders(accessToken),
            body = payload,
        ),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAccountSettings(json)
}

internal suspend fun CrispyBackendClient.deleteAccountApi(accessToken: String): Boolean {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/account",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return response.code in 200..299
}

internal suspend fun CrispyBackendClient.listAddonsApi(accessToken: String): List<AddonDto> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/account/addons",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAddons(json.opt("addons"))
}

internal suspend fun CrispyBackendClient.installAddonApi(
    accessToken: String,
    profileId: String,
    manifestUrl: String,
    type: String = "stremio",
    payload: Map<String, String> = emptyMap(),
): AddonDto {
    checkConfigured()
    val jsonBody = JSONObject()
        .put("manifestUrl", manifestUrl.trim())
        .put("type", type)
    if (payload.isNotEmpty()) {
        val payloadJson = JSONObject()
        payload.forEach { (key, value) -> payloadJson.put(key, value) }
        jsonBody.put("payload", payloadJson)
    }
    val response = httpClient.postJson(
        url = "$baseUrl/v1/account/addons",
        jsonBody = jsonBody.toString(),
        headers = authHeaders(accessToken, profileId),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAddon(json.optJSONObject("addon"))
        ?: throw IllegalStateException("Backend did not return an installed addon.")
}

internal suspend fun CrispyBackendClient.uninstallAddonApi(
    accessToken: String,
    profileId: String,
    addonId: String,
): Boolean {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/account/addons/${addonId.trim()}",
        headers = authHeaders(accessToken, profileId),
        callTimeoutMs = callTimeoutMs,
    )
    return response.code in 200..299
}

internal suspend fun CrispyBackendClient.getAvatarsApi(): List<Avatar> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/avatars",
        headers = mapOf("Accept" to "application/json"),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseAvatars(json.optJSONArray("avatars"))
}
