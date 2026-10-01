package com.crispy.tv.backend

import com.crispy.tv.ai.AiInsightsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun CrispyBackendClient.searchTitlesApi(
    accessToken: String,
    query: String,
    limit: Int = 20,
): SearchResultsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/search/titles",
        query = buildList {
            add("query" to query.trim())
            add("limit" to limit.toString())
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseSearchResultsResponse(json)
}

internal suspend fun CrispyBackendClient.searchSuggestionsApi(
    accessToken: String,
    query: String,
    limit: Int = 8,
): SearchSuggestionsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/search/suggestions",
        query = buildList {
            add("query" to query.trim())
            add("limit" to limit.toString())
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseSearchSuggestionsResponse(json)
}

internal suspend fun CrispyBackendClient.searchTitlesByGenreApi(
    accessToken: String,
    genre: String,
    limit: Int = 20,
): SearchResultsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/search/titles",
        query = buildList {
            add("genre" to genre.trim())
            add("limit" to limit.toString())
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseSearchResultsResponse(json)
}

internal suspend fun CrispyBackendClient.searchAiTitlesApi(
    accessToken: String,
    profileId: String,
    query: String,
    locale: String? = null,
): SearchResultsResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("query", query.trim())
        if (!locale.isNullOrBlank()) put("locale", locale)
    }.toString()
    val response = aiHttpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/search",
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = aiCallTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseSearchResultsResponse(json)
}

internal suspend fun CrispyBackendClient.browseTitlesApi(
    accessToken: String,
    type: String,
    genre: String? = null,
    sort: String = "popularity",
    page: Int = 0,
): BrowseTitlesResponse {
    checkConfigured()
    // The route schema rejects unknown query params, so the page size is a
    // server-side decision and must not be sent from here.
    val response = httpClient.get(
        url = "$baseUrl/v1/browse/titles",
        query = buildList {
            add("type" to type.trim())
            genre?.trim()?.takeIf { it.isNotBlank() }?.let { add("genre" to it) }
            add("sort" to sort.trim())
            add("page" to page.toString())
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseBrowseTitlesResponse(json)
}

internal suspend fun CrispyBackendClient.getMetadataPersonDetailApi(
    accessToken: String,
    personId: String,
    language: String? = null,
): MetadataPersonDetail {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/people/${personId.trim()}",
        query = buildList {
            if (!language.isNullOrBlank()) {
                add("language" to language)
            }
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return parseMetadataPersonDetail(json)
}

internal suspend fun CrispyBackendClient.getAiInsightsApi(
    accessToken: String,
    profileId: String,
    itemId: String,
    locale: String? = null,
): AiInsightsResult {
    checkConfigured()
    val payload = JSONObject().apply {
        put("itemId", itemId.trim())
        if (!locale.isNullOrBlank()) put("locale", locale)
    }.toString()
    val response = aiHttpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/insights",
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = aiCallTimeoutMs,
    )
    val json = requireSuccess(response)
    return AiInsightsResult(slides = parseAiInsightsSlides(json.optJSONArray("slides")))
}

internal suspend fun CrispyBackendClient.getMetadataItemDetailApi(
    accessToken: String,
    itemId: String,
): MetadataTitleDetailResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/items/${itemId.trim()}",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return withContext(Dispatchers.Default) {
        MetadataTitleDetailResponse(
            item = parseClientMediaCard(json.optJSONObject("Item") ?: throw IllegalStateException("Backend item detail is missing Item.")),
            nextEpisode = json.optJSONObject("NextEpisode")?.let(::parseClientMediaCard),
            videos = parseMetadataVideoViews(json.optJSONArray("Videos")),
            cast = parseMetadataPersonRefViews(json.optJSONArray("Cast")),
            directors = parseMetadataPersonRefViews(json.optJSONArray("Directors")),
            creators = parseMetadataPersonRefViews(json.optJSONArray("Creators")),
            production = parseMetadataProductionInfoView(json.optJSONObject("Production")),
        )
    }
}

internal suspend fun CrispyBackendClient.getMetadataItemExtrasApi(
    accessToken: String,
    itemId: String,
): MetadataTitleExtrasResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/items/${itemId.trim()}/extras",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return withContext(Dispatchers.Default) {
        MetadataTitleExtrasResponse(
            seasons = parseClientMediaCards(json.optJSONArray("Seasons")),
            reviews = parseMetadataReviewViews(json.optJSONArray("Reviews")),
            lists = parseMetadataExtrasLists(json.optJSONArray("Lists")),
        )
    }
}

internal fun CrispyBackendClient.parseMetadataExtrasLists(array: JSONArray?): List<MetadataExtrasList> {
    val safeArray = array ?: JSONArray()
    return buildList {
        for (index in 0 until safeArray.length()) {
            val list = safeArray.optJSONObject(index) ?: continue
            val key = list.optString("key").trim()
            val title = list.optString("title").trim()
            if (key.isBlank() || title.isBlank()) continue
            add(
                MetadataExtrasList(
                    key = key,
                    title = title,
                    items = parseClientMediaCards(list.optJSONArray("items")),
                )
            )
        }
    }
}

internal suspend fun CrispyBackendClient.getSeriesEpisodesApi(
    accessToken: String,
    seriesItemId: String,
    season: Int?,
): MetadataSeriesEpisodesResponse {
    checkConfigured()
    val url = buildString {
        append("$baseUrl/v1/metadata/shows/${seriesItemId.trim()}/episodes")
        if (season != null) append("?season=$season")
    }
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    val itemsArray = json.optJSONArray("Items")
    val items = mutableListOf<ClientMediaCard>()
    if (itemsArray != null) {
        for (i in 0 until itemsArray.length()) {
            val itemJson = itemsArray.optJSONObject(i) ?: continue
            items += parseClientMediaCard(itemJson)
        }
    }
    return MetadataSeriesEpisodesResponse(items = items)
}

internal suspend fun CrispyBackendClient.getMetadataItemRatingsApi(
    accessToken: String,
    profileId: String,
    itemId: String,
): MetadataTitleRatingsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/metadata/items/${itemId.trim()}/ratings",
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return withContext(Dispatchers.Default) {
        MetadataTitleRatingsResponse(
            ratings = parseMetadataTitleRatings(json.optJSONObject("Ratings")),
        )
    }
}

internal suspend fun CrispyBackendClient.resolvePlaybackApi(
    accessToken: String,
    input: ItemLookupInput,
): PlaybackResolveResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/playback/resolve",
        query = buildList {
            if (!input.itemId.isNullOrBlank()) add("itemId" to input.itemId.trim())
        },
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = requireSuccess(response)
    return PlaybackResolveResponse(
        item = parseClientMediaCard(json.optJSONObject("Item") ?: throw IllegalStateException("Backend playback resolve is missing Item.")),
        show = json.optJSONObject("Show")?.let(::parseClientMediaCard),
        season = json.optJSONObject("Season")?.let(::parseClientMediaCard),
    )
}
