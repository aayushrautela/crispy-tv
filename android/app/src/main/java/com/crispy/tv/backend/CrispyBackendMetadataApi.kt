package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AiInsightsResponse
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonDetail
import com.crispy.tv.backend.CrispyBackendClient.MetadataResolveResponse
import com.crispy.tv.backend.CrispyBackendClient.SearchResultsResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleDetailResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleRatingsResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleReviewsResponse
import com.crispy.tv.backend.CrispyBackendClient.PlaybackResolveResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal suspend fun CrispyBackendClient.searchTitlesApi(
    accessToken: String,
    query: String,
    limit: Int = 20,
): SearchResultsResponse {
    checkConfigured()
    val url = "$baseUrl/v1/search/titles".toHttpUrl().newBuilder()
        .addQueryParameter("query", query.trim())
        .addQueryParameter("limit", limit.toString())
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseSearchResultsResponse(json)
}

internal suspend fun CrispyBackendClient.searchTitlesByGenreApi(
    accessToken: String,
    genre: String,
    limit: Int = 20,
): SearchResultsResponse {
    checkConfigured()
    val url = "$baseUrl/v1/search/titles".toHttpUrl().newBuilder()
        .addQueryParameter("genre", genre.trim())
        .addQueryParameter("limit", limit.toString())
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
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
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/search".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseSearchResultsResponse(json)
}

internal suspend fun CrispyBackendClient.getAiInsightsApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
    locale: String? = null,
): AiInsightsResponse {
    checkConfigured()
    val payload = JSONObject().apply {
        put("mediaKey", mediaKey.trim())
        if (!locale.isNullOrBlank()) put("locale", locale)
    }.toString()
    val response = httpClient.postJson(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/ai/insights".toHttpUrl(),
        jsonBody = payload,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return AiInsightsResponse(
        insights = parseAiInsightsCards(json.optJSONArray("insights")),
        trivia = json.optString("trivia").trim(),
    )
}

internal suspend fun CrispyBackendClient.resolveMetadataApi(
    accessToken: String,
    input: MediaLookupInput,
): MetadataResolveResponse {
    checkConfigured()
    val response = httpClient.get(
        url = metadataLookupUrl("$baseUrl/v1/metadata/resolve", input),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    val itemJson = json.optJSONObject("item") ?: throw IllegalStateException("Backend metadata resolve did not return an item.")
    return MetadataResolveResponse(item = parseMetadataView(itemJson))
}

internal suspend fun CrispyBackendClient.getMetadataTitleDetailApi(
    accessToken: String,
    mediaKey: String,
): MetadataTitleDetailResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/titles/${mediaKey.trim()}".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataTitleDetailResponse(
        item = parseMetadataView(json.optJSONObject("item") ?: throw IllegalStateException("Backend title detail is missing item.")),
        seasons = parseMetadataSeasonViews(json.optJSONArray("seasons")),
        episodes = parseMetadataEpisodeViews(json.optJSONArray("episodes")),
        nextEpisode = json.optJSONObject("nextEpisode")?.let(::parseMetadataEpisodeView),
        videos = parseMetadataVideoViews(json.optJSONArray("videos")),
        cast = parseMetadataPersonRefViews(json.optJSONArray("cast")),
        directors = parseMetadataPersonRefViews(json.optJSONArray("directors")),
        creators = parseMetadataPersonRefViews(json.optJSONArray("creators")),
        production = parseMetadataProductionInfoView(json.optJSONObject("production")),
        collection = parseMetadataCollectionView(json.optJSONObject("collection")),
        similar = parseMetadataCardViews(json.optJSONArray("similar")),
    )
}

internal suspend fun CrispyBackendClient.getMetadataTitleReviewsApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
): MetadataTitleReviewsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/metadata/titles/${mediaKey.trim()}/reviews".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataTitleReviewsResponse(
        reviews = parseMetadataReviewViews(json.optJSONArray("reviews")),
    )
}

internal suspend fun CrispyBackendClient.getMetadataTitleRatingsApi(
    accessToken: String,
    profileId: String,
    mediaKey: String,
): MetadataTitleRatingsResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/profiles/${profileId.trim()}/metadata/titles/${mediaKey.trim()}/ratings".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataTitleRatingsResponse(
        ratings = parseMetadataTitleRatings(json.optJSONObject("ratings")),
    )
}

internal suspend fun CrispyBackendClient.getMetadataPersonDetailApi(
    accessToken: String,
    id: String,
    language: String? = null,
): MetadataPersonDetail {
    checkConfigured()
    val url = "$baseUrl/v1/metadata/people/${id.trim()}".toHttpUrl().newBuilder()
        .apply {
            if (!language.isNullOrBlank()) {
                addQueryParameter("language", language)
            }
        }
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return parseMetadataPersonDetail(json)
}


internal suspend fun CrispyBackendClient.resolvePlaybackApi(
    accessToken: String,
    input: MediaLookupInput,
): PlaybackResolveResponse {
    checkConfigured()
    val response = httpClient.get(
        url = metadataLookupUrl("$baseUrl/v1/playback/resolve", input),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return PlaybackResolveResponse(
        item = parseMetadataView(json.optJSONObject("item") ?: throw IllegalStateException("Backend playback resolve is missing item.")),
        show = json.optJSONObject("show")?.let(::parseMetadataView),
        season = json.optJSONObject("season")?.let(::parseMetadataSeasonView),
    )
}
