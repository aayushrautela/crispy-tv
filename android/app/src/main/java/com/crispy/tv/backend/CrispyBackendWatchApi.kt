package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.CalendarResponse
import com.crispy.tv.backend.CrispyBackendClient.CanonicalWatchCollectionResponse
import com.crispy.tv.backend.CrispyBackendClient.HomeResponse
import com.crispy.tv.backend.CrispyBackendClient.HydratedRatingItem
import com.crispy.tv.backend.CrispyBackendClient.HydratedWatchItem
import com.crispy.tv.backend.CrispyBackendClient.HydratedWatchlistItem
import com.crispy.tv.backend.CrispyBackendClient.LibraryMutationResponse
import com.crispy.tv.backend.CrispyBackendClient.LibraryMutationSource
import com.crispy.tv.backend.CrispyBackendClient.LibrarySource
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.PlaybackEventInput
import com.crispy.tv.backend.CrispyBackendClient.ProfileLibraryResponse
import com.crispy.tv.backend.CrispyBackendClient.ProviderAuthState
import com.crispy.tv.backend.CrispyBackendClient.WatchActionResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchMutationInput
import com.crispy.tv.backend.CrispyBackendClient.WatchStateEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchStateResponse
import com.crispy.tv.backend.CrispyBackendClient.WatchStatesEnvelope
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun CrispyBackendClient.getHomeApi(accessToken: String, profileId: String): HomeResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/home".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return HomeResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        sections = parseHomeSections(json.optJSONArray("sections")),
    )
}

internal suspend fun CrispyBackendClient.getCalendarApi(accessToken: String, profileId: String): CalendarResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/calendar".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return CalendarResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseCalendarItems(json.optJSONArray("items")),
    )
}

internal suspend fun CrispyBackendClient.getProviderAuthStateApi(
    accessToken: String,
    profileId: String,
): List<ProviderAuthState> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/provider-auth/state".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseProviderAuthStates(json.optJSONArray("providers"))
}

internal suspend fun CrispyBackendClient.getProfileLibraryApi(
    accessToken: String,
    profileId: String,
    source: LibrarySource? = null,
    limitPerFolder: Int? = null,
): ProfileLibraryResponse {
    checkConfigured()
    val url = "$baseUrl/v1/profiles/${profileId.trim()}/library".toHttpUrl().newBuilder()
        .apply {
            if (source != null) {
                addQueryParameter("source", source.apiValue)
            }
            if (limitPerFolder != null) {
                addQueryParameter("limitPerFolder", limitPerFolder.toString())
            }
        }
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return ProfileLibraryResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim().ifBlank { source?.apiValue ?: LibrarySource.ALL.apiValue },
        generatedAt = json.optNullableString("generatedAt"),
        auth = parseLibraryAuth(json.optJSONObject("auth")),
        canonical = parseCanonicalLibrary(json.optJSONObject("canonical") ?: JSONObject()),
        native = json.optJSONObject("native")?.let(::parseNativeLibrary),
        diagnostics = parseLibraryDiagnostics(json.optJSONObject("diagnostics")),
    )
}

internal suspend fun CrispyBackendClient.setProviderWatchlistApi(
    accessToken: String,
    profileId: String,
    input: MediaLookupInput,
    inWatchlist: Boolean,
    source: LibraryMutationSource? = null,
): LibraryMutationResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("inWatchlist", inWatchlist)
        if (source != null) {
            put("source", source.apiValue)
        }
        putLibraryResolveInput(input)
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/library/watchlist".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseLibraryMutationResponse(json)
}

internal suspend fun CrispyBackendClient.setProviderRatingApi(
    accessToken: String,
    profileId: String,
    input: MediaLookupInput,
    rating: Int?,
    source: LibraryMutationSource? = null,
): LibraryMutationResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        if (source != null) {
            put("source", source.apiValue)
        }
        if (rating != null) {
            put("rating", rating)
        } else {
            put("rating", JSONObject.NULL)
        }
        putLibraryResolveInput(input)
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/library/rating".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseLibraryMutationResponse(json)
}

internal suspend fun CrispyBackendClient.sendWatchEventApi(
    accessToken: String,
    profileId: String,
    input: PlaybackEventInput,
): WatchActionResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("clientEventId", input.clientEventId.trim())
        put("eventType", input.eventType.trim())
        put("mediaType", input.mediaType.trim())
        if (!input.mediaKey.isNullOrBlank()) put("mediaKey", input.mediaKey.trim())
        if (input.tmdbId != null) put("tmdbId", input.tmdbId)
        if (input.showTmdbId != null) put("showTmdbId", input.showTmdbId)
        if (!input.provider.isNullOrBlank()) put("provider", input.provider.trim())
        if (!input.providerId.isNullOrBlank()) put("providerId", input.providerId.trim())
        if (!input.parentProvider.isNullOrBlank()) put("parentProvider", input.parentProvider.trim())
        if (!input.parentProviderId.isNullOrBlank()) put("parentProviderId", input.parentProviderId.trim())
        if (input.absoluteEpisodeNumber != null) put("absoluteEpisodeNumber", input.absoluteEpisodeNumber)
        if (input.seasonNumber != null) put("seasonNumber", input.seasonNumber)
        if (input.episodeNumber != null) put("episodeNumber", input.episodeNumber)
        if (input.positionSeconds != null) put("positionSeconds", input.positionSeconds)
        if (input.durationSeconds != null) put("durationSeconds", input.durationSeconds)
        if (input.rating != null) put("rating", input.rating)
        if (!input.occurredAt.isNullOrBlank()) put("occurredAt", input.occurredAt.trim())
        put("payload", input.payload.toJsonObject())
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/events".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

internal suspend fun CrispyBackendClient.listContinueWatchingApi(
    accessToken: String,
    profileId: String,
    limit: Int = 20,
): CanonicalWatchCollectionResponse<HydratedWatchItem> {
    return listHydratedWatchItemsApi(accessToken, profileId, path = "continue-watching", limit = limit)
}

internal suspend fun CrispyBackendClient.dismissContinueWatchingApi(
    accessToken: String,
    profileId: String,
    itemId: String,
): WatchActionResponse {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/continue-watching/${itemId.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

internal suspend fun CrispyBackendClient.listWatchHistoryApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
): CanonicalWatchCollectionResponse<HydratedWatchItem> {
    return listHydratedWatchItemsApi(accessToken, profileId, path = "history", limit = limit)
}

internal suspend fun CrispyBackendClient.listWatchlistApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
): CanonicalWatchCollectionResponse<HydratedWatchlistItem> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseHydratedWatchlistCollectionResponse(json)
}

internal suspend fun CrispyBackendClient.listRatingsApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
): CanonicalWatchCollectionResponse<HydratedRatingItem> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/ratings".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseHydratedRatingCollectionResponse(json)
}

internal suspend fun CrispyBackendClient.getWatchStateApi(
    accessToken: String,
    profileId: String,
    input: MediaLookupInput,
): WatchStateEnvelope {
    checkConfigured()
    val response = httpClient.get(
        url = metadataLookupUrl(
            "$baseUrl/v1/profiles/${profileId.trim()}/watch/state",
            input,
            includeId = false,
            includeImdbId = false,
        ),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseWatchStateEnvelope(json)
}

internal suspend fun CrispyBackendClient.getWatchStatesApi(
    accessToken: String,
    profileId: String,
    items: List<MediaLookupInput>,
): WatchStatesEnvelope {
    checkConfigured()
    val payload = JSONObject().put(
        "items",
        JSONArray().apply {
            items.forEach { input ->
                put(
                    JSONObject().apply {
                        putWatchLookupInput(input)
                    }
                )
            }
        },
    ).toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/states".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseWatchStatesEnvelope(json)
}

internal suspend fun CrispyBackendClient.markWatchedApi(
    accessToken: String,
    profileId: String,
    input: WatchMutationInput,
): WatchActionResponse {
    return postWatchMutationApi(accessToken, profileId, "mark-watched", input)
}

internal suspend fun CrispyBackendClient.unmarkWatchedApi(
    accessToken: String,
    profileId: String,
    input: WatchMutationInput,
): WatchActionResponse {
    return postWatchMutationApi(accessToken, profileId, "unmark-watched", input)
}

internal suspend fun CrispyBackendClient.putNativeWatchlistApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
    occurredAt: String? = null,
    payload: Map<String, Any?> = emptyMap(),
): WatchActionResponse {
    checkConfigured()
    val requestBody = JSONObject().apply {
        if (!occurredAt.isNullOrBlank()) put("occurredAt", occurredAt.trim())
        if (payload.isNotEmpty()) put("payload", payload.toJsonObject())
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${mediaKey.trim()}".toHttpUrl())
            .headers(authHeaders(accessToken))
            .put(requestBody.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

internal suspend fun CrispyBackendClient.deleteNativeWatchlistApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
): WatchActionResponse {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${mediaKey.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

internal suspend fun CrispyBackendClient.putNativeRatingApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
    rating: Int,
    occurredAt: String? = null,
    payload: Map<String, Any?> = emptyMap(),
): WatchActionResponse {
    checkConfigured()
    val requestBody = JSONObject().apply {
        put("rating", rating)
        if (!occurredAt.isNullOrBlank()) put("occurredAt", occurredAt.trim())
        if (payload.isNotEmpty()) put("payload", payload.toJsonObject())
    }.toString()
    val response = httpClient.execute(
        request = Request.Builder()
            .url("$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${mediaKey.trim()}".toHttpUrl())
            .headers(authHeaders(accessToken))
            .put(requestBody.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

internal suspend fun CrispyBackendClient.deleteNativeRatingApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
): WatchActionResponse {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${mediaKey.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}

private suspend fun CrispyBackendClient.listHydratedWatchItemsApi(
    accessToken: String,
    profileId: String,
    path: String,
    limit: Int,
): CanonicalWatchCollectionResponse<HydratedWatchItem> {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/$path".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .build(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseHydratedWatchCollectionResponse(json)
}

private suspend fun CrispyBackendClient.postWatchMutationApi(
    accessToken: String,
    profileId: String,
    path: String,
    input: WatchMutationInput,
): WatchActionResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("mediaType", input.mediaType.trim())
        if (!input.mediaKey.isNullOrBlank()) put("mediaKey", input.mediaKey.trim())
        if (input.tmdbId != null) put("tmdbId", input.tmdbId)
        if (input.showTmdbId != null) put("showTmdbId", input.showTmdbId)
        if (!input.provider.isNullOrBlank()) put("provider", input.provider.trim())
        if (!input.providerId.isNullOrBlank()) put("providerId", input.providerId.trim())
        if (!input.parentProvider.isNullOrBlank()) put("parentProvider", input.parentProvider.trim())
        if (!input.parentProviderId.isNullOrBlank()) put("parentProviderId", input.parentProviderId.trim())
        if (input.absoluteEpisodeNumber != null) put("absoluteEpisodeNumber", input.absoluteEpisodeNumber)
        if (input.seasonNumber != null) put("seasonNumber", input.seasonNumber)
        if (input.episodeNumber != null) put("episodeNumber", input.episodeNumber)
        if (!input.occurredAt.isNullOrBlank()) put("occurredAt", input.occurredAt.trim())
        if (input.rating != null) put("rating", input.rating)
        put("payload", input.payload.toJsonObject())
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/$path".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(JSONObject(requireSuccess(response)))
}
