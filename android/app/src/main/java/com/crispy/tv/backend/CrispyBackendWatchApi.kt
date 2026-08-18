package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.CalendarResponse
import com.crispy.tv.backend.CrispyBackendClient.UpNextResponse
import com.crispy.tv.backend.CrispyBackendClient.UpNextItem
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCardQueryResult
import com.crispy.tv.backend.CrispyBackendClient.ProfileHomeResponse
import com.crispy.tv.backend.PlaybackEventInput
import com.crispy.tv.backend.CrispyBackendClient.WatchActionResponse
import com.crispy.tv.backend.WatchMutationInput
import com.crispy.tv.backend.CrispyBackendClient.WatchStateEnvelope
import com.crispy.tv.backend.CrispyBackendClient.WatchStatesEnvelope
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun CrispyBackendClient.getHomeApi(
    accessToken: String,
    profileId: String,
): ProfileHomeResponse? {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/home".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val parsedProfileId = json.optString("profileId").trim()
    if (parsedProfileId.isBlank()) return null
    return ProfileHomeResponse(
        profileId = parsedProfileId,
        generatedAt = json.optNullableString("generatedAt"),
        expiresAt = json.optNullableString("expiresAt"),
        sections = parseProfileHomeSections(json.optJSONArray("sections")),
    )
}

internal suspend fun CrispyBackendClient.getCalendarApi(accessToken: String, profileId: String): CalendarResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/calendar".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return CalendarResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseCalendarItems(json.optJSONArray("items")),
    )
}

internal suspend fun CrispyBackendClient.getCalendarThisWeekApi(accessToken: String, profileId: String): CalendarResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/calendar/this-week".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return CalendarResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optString("source").trim(),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseCalendarItems(json.optJSONArray("items")),
    )
}

internal suspend fun CrispyBackendClient.getUpNextApi(
    accessToken: String,
    profileId: String,
    limit: Int,
): UpNextResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/episodic-follow".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return UpNextResponse(
        profileId = json.optString("profileId").trim(),
        source = json.optNullableString("source"),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseUpNextItems(json.optJSONArray("items")),
    )
}

internal fun CrispyBackendClient.parseUpNextItems(array: JSONArray?): List<UpNextItem> {
    val safe = array ?: JSONArray()
    return buildList {
        for (i in 0 until safe.length()) {
            val item = safe.optJSONObject(i) ?: continue
            val show = item.optJSONObject("show")
            val images = show?.optJSONObject("images")
            val poster = images?.optJSONObject("poster")
            val backdrop = images?.optJSONObject("backdrop")
            add(
                UpNextItem(
                    showItemId = show?.optNullableString("itemId"),
                    showTitle = show?.optNullableString("title"),
                    showPosterUrl = poster?.optNullableString("medium")
                        ?: poster?.optNullableString("large")
                        ?: poster?.optNullableString("small"),
                    showBackdropUrl = backdrop?.optNullableString("medium")
                        ?: backdrop?.optNullableString("large"),
                    nextEpisodeItemId = item.optNullableString("nextEpisodeItemId"),
                    nextEpisodeSeasonNumber = item.optIntOrNull("nextEpisodeSeasonNumber"),
                    nextEpisodeEpisodeNumber = item.optIntOrNull("nextEpisodeEpisodeNumber"),
                    nextEpisodeTitle = item.optNullableString("nextEpisodeTitle"),
                    nextEpisodeAirDate = item.optNullableString("nextEpisodeAirDate"),
                    lastInteractedAt = item.optNullableString("lastInteractedAt"),
                    reason = item.optNullableString("reason"),
                ),
            )
        }
    }
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
        put("itemId", input.itemId.trim())
        if (input.positionSeconds != null) put("positionSeconds", input.positionSeconds)
        if (input.durationSeconds != null) put("durationSeconds", input.durationSeconds)
        if (!input.occurredAt.isNullOrBlank()) put("occurredAt", input.occurredAt.trim())
        put("payload", input.payload.toJsonObject())
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/events".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.listContinueWatchingApi(
    accessToken: String,
    profileId: String,
    limit: Int = 20,
    cursor: String? = null,
): ClientMediaCardQueryResult {
    return listClientMediaCardQueryResultApi(
        accessToken, profileId, path = "continue-watching", limit = limit, cursor = cursor,
    )
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
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.listWatchHistoryApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
    cursor: String? = null,
): ClientMediaCardQueryResult {
    return listClientMediaCardQueryResultApi(
        accessToken, profileId, path = "history", limit = limit, cursor = cursor,
    )
}

internal suspend fun CrispyBackendClient.listWatchlistApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
    cursor: String? = null,
): ClientMediaCardQueryResult {
    return listClientMediaCardQueryResultApi(
        accessToken, profileId, path = "watchlist", limit = limit, cursor = cursor,
    )
}

internal suspend fun CrispyBackendClient.listRatingsApi(
    accessToken: String,
    profileId: String,
    limit: Int = 50,
    cursor: String? = null,
): ClientMediaCardQueryResult {
    return listClientMediaCardQueryResultApi(
        accessToken, profileId, path = "ratings", limit = limit, cursor = cursor,
    )
}

internal suspend fun CrispyBackendClient.getWatchStateApi(
    accessToken: String,
    profileId: String,
    itemId: String,
): WatchStateEnvelope {
    checkConfigured()
    val normalizedItemId = itemId.trim()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/state".toHttpUrl().newBuilder()
            .addQueryParameter("itemId", normalizedItemId)
            .addQueryParameter("extended", "true")
            .build(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseWatchStateEnvelope(json, profileId)
}

internal suspend fun CrispyBackendClient.getWatchStatesApi(
    accessToken: String,
    profileId: String,
    itemIds: List<String>,
): WatchStatesEnvelope {
    checkConfigured()
    val payload = JSONObject().put(
        "items",
        JSONArray().apply {
            itemIds.forEach { itemId ->
                put(
                    JSONObject().apply {
                        put("itemId", itemId.trim())
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
    val json = requireSuccess(response)
    return parseWatchStatesEnvelope(json, profileId)
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

internal suspend fun CrispyBackendClient.putWatchlistApi(
    accessToken: String,
    profileId: String,
    itemId: String,
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
            .url("$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${itemId.trim()}".toHttpUrl())
            .headers(authHeaders(accessToken))
            .put(requestBody.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.deleteWatchlistApi(
    accessToken: String,
    profileId: String,
    itemId: String,
): WatchActionResponse {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${itemId.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.putRatingApi(
    accessToken: String,
    profileId: String,
    itemId: String,
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
            .url("$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${itemId.trim()}".toHttpUrl())
            .headers(authHeaders(accessToken))
            .put(requestBody.toRequestBody(jsonMediaType))
            .build(),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.deleteRatingApi(
    accessToken: String,
    profileId: String,
    itemId: String,
): WatchActionResponse {
    checkConfigured()
    val response = httpClient.delete(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${itemId.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

private suspend fun CrispyBackendClient.listClientMediaCardQueryResultApi(
    accessToken: String,
    profileId: String,
    path: String,
    limit: Int,
    cursor: String?,
): ClientMediaCardQueryResult {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/$path".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceAtLeast(1).toString())
            .addQueryParameter("extended", "true")
            .apply {
                val nextCursor = cursor?.trim()?.takeIf { it.isNotEmpty() }
                if (nextCursor != null) {
                    addQueryParameter("cursor", nextCursor)
                }
            }
            .build(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseClientMediaCardQueryResult(json)
}

private suspend fun CrispyBackendClient.postWatchMutationApi(
    accessToken: String,
    profileId: String,
    path: String,
    input: WatchMutationInput,
): WatchActionResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("itemId", input.itemId.trim())
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
    return parseWatchActionResponse(requireSuccess(response))
}
