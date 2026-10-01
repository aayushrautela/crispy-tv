package com.crispy.tv.backend

import com.crispy.tv.network.HttpMethod
import com.crispy.tv.network.HttpRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun CrispyBackendClient.getHomeApi(
    accessToken: String,
    profileId: String,
): ProfileHomeResponse? {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/home",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val parsedProfileId = json.optStringOrEmpty("profileId").trim()
    if (parsedProfileId.isBlank()) return null
    return ProfileHomeResponse(
        profileId = parsedProfileId,
        generatedAt = json.optNullableString("generatedAt"),
        expiresAt = json.optNullableString("expiresAt"),
        sections = parseProfileHomeSections(json.optJsonArray("sections")),
    )
}

internal suspend fun CrispyBackendClient.getCalendarApi(accessToken: String, profileId: String): CalendarResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/calendar",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return CalendarResponse(
        profileId = json.optStringOrEmpty("profileId").trim(),
        source = json.optStringOrEmpty("source").trim(),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseCalendarItems(json.optJsonArray("items")),
    )
}

internal suspend fun CrispyBackendClient.getCalendarThisWeekApi(accessToken: String, profileId: String): CalendarResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/calendar/this-week",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return CalendarResponse(
        profileId = json.optStringOrEmpty("profileId").trim(),
        source = json.optStringOrEmpty("source").trim(),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseCalendarItems(json.optJsonArray("items")),
    )
}

internal suspend fun CrispyBackendClient.getUpNextApi(
    accessToken: String,
    profileId: String,
    limit: Int,
): UpNextResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/episodic-follow",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return UpNextResponse(
        profileId = json.optStringOrEmpty("profileId").trim(),
        source = json.optNullableString("source"),
        kind = json.optNullableString("kind"),
        generatedAt = json.optNullableString("generatedAt"),
        items = parseUpNextItems(json.optJsonArray("items")),
    )
}

internal fun CrispyBackendClient.parseUpNextItems(array: JsonArray?): List<UpNextItem> {
    val safe = array ?: JsonArray(emptyList())
    return buildList {
        for (i in 0 until safe.size) {
            val item = safe.getOrNull(i) as? JsonObject ?: continue
            add(
                UpNextItem(
                    show = item.optJsonObject("show")?.let(::parseClientMediaCard),
                    nextEpisode = item.optJsonObject("nextEpisode")?.let(::parseClientMediaCard),
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
    val payload = buildJsonObject {
        put("clientEventId", input.clientEventId.trim())
        put("eventType", input.eventType.trim())
        put("itemId", input.itemId.trim())
        if (input.positionSeconds != null) put("positionSeconds", input.positionSeconds)
        if (input.durationSeconds != null) put("durationSeconds", input.durationSeconds)
        if (input.seasonNumber != null) put("seasonNumber", input.seasonNumber)
        if (input.episodeNumber != null) put("episodeNumber", input.episodeNumber)
        if (!input.occurredAt.isNullOrBlank()) put("occurredAt", input.occurredAt.trim())
        put("payload", input.payload.toJsonObject())
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/events",
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/continue-watching/${itemId.trim()}",
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

internal suspend fun CrispyBackendClient.getWatchGenerationsApi(
    accessToken: String,
    profileId: String,
): WatchGenerationsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/generations",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return WatchGenerationsResponse(
        continueWatchingMs = json.optLongOrNull("continue_watching"),
        historyMs = json.optLongOrNull("history"),
        watchlistMs = json.optLongOrNull("watchlist"),
        ratingsMs = json.optLongOrNull("ratings"),
        homeMs = json.optLongOrNull("home"),
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/state",
        query = listOf(
            "itemId" to normalizedItemId,
            "extended" to "true",
        ),
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
    val payload = buildJsonObject {
        put(
            "items",
            buildJsonArray {
                itemIds.forEach { itemId ->
                    add(buildJsonObject { put("itemId", itemId.trim()) })
                }
            },
        )
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/states",
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
    val requestBody = buildJsonObject {
        if (!occurredAt.isNullOrBlank()) put("occurredAt", occurredAt.trim())
        if (payload.isNotEmpty()) put("payload", payload.toJsonObject())
    }.toString()
    val response = httpClient.execute(
        request = HttpRequest(
            method = HttpMethod.PUT,
            url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${itemId.trim()}",
            headers = authHeaders(accessToken),
            body = requestBody,
        ),
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/watchlist/${itemId.trim()}",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}

internal suspend fun CrispyBackendClient.setLikedApi(
    accessToken: String,
    profileId: String,
    itemId: String,
    liked: Boolean,
    occurredAt: String? = null,
    payload: Map<String, Any?> = emptyMap(),
): WatchActionResponse {
    checkConfigured()
    val requestBody = buildJsonObject {
        put("liked", liked)
        if (!occurredAt.isNullOrBlank()) put("occurredAt", occurredAt.trim())
        if (payload.isNotEmpty()) put("payload", payload.toJsonObject())
    }.toString()
    val response = httpClient.execute(
        request = HttpRequest(
            method = HttpMethod.PUT,
            url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${itemId.trim()}",
            headers = authHeaders(accessToken),
            body = requestBody,
        ),
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/rating/${itemId.trim()}",
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
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/$path",
        query = buildList {
            add("limit" to limit.coerceAtLeast(1).toString())
            add("extended" to "true")
            val nextCursor = cursor?.trim()?.takeIf { it.isNotEmpty() }
            if (nextCursor != null) {
                add("cursor" to nextCursor)
            }
        },
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
    val payload = buildJsonObject {
        put("itemId", input.itemId.trim())
        if (!input.occurredAt.isNullOrBlank()) put("occurredAt", input.occurredAt.trim())
        if (input.rating != null) put("rating", input.rating)
        if (input.seasonNumber != null) put("seasonNumber", input.seasonNumber)
        if (input.episodeNumber != null) put("episodeNumber", input.episodeNumber)
        put("payload", input.payload.toJsonObject())
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/watch/$path",
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    return parseWatchActionResponse(requireSuccess(response))
}
