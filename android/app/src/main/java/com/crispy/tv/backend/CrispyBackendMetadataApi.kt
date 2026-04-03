package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.AiInsightsResponse
import com.crispy.tv.backend.CrispyBackendClient.AiSearchResponse
import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import com.crispy.tv.backend.CrispyBackendClient.MetadataEpisodeListResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataNextEpisodeResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataPersonDetail
import com.crispy.tv.backend.CrispyBackendClient.MetadataResolveResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataSearchResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataSeasonDetailResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleContentResponse
import com.crispy.tv.backend.CrispyBackendClient.MetadataTitleDetailResponse
import com.crispy.tv.backend.CrispyBackendClient.PlaybackResolveResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal suspend fun CrispyBackendClient.searchTitlesApi(
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
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
}

internal suspend fun CrispyBackendClient.searchTitlesByGenreApi(
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
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
}

internal suspend fun CrispyBackendClient.searchAiTitlesApi(
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
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return AiSearchResponse(items = parseMetadataItems(json.optJSONArray("items")))
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
        url = metadataLookupUrl("$baseUrl/v1/metadata/resolve", input, includeMediaKey = true),
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
        videos = parseMetadataVideoViews(json.optJSONArray("videos")),
        cast = parseMetadataPersonRefViews(json.optJSONArray("cast")),
        directors = parseMetadataPersonRefViews(json.optJSONArray("directors")),
        creators = parseMetadataPersonRefViews(json.optJSONArray("creators")),
        reviews = parseMetadataReviewViews(json.optJSONArray("reviews")),
        production = parseMetadataProductionInfoView(json.optJSONObject("production")),
        collection = parseMetadataCollectionView(json.optJSONObject("collection")),
        similar = parseMetadataCardViews(json.optJSONArray("similar")),
    )
}

internal suspend fun CrispyBackendClient.getMetadataTitleContentApi(
    accessToken: String,
    mediaKey: String,
): MetadataTitleContentResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/titles/${mediaKey.trim()}/content".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataTitleContentResponse(
        item = parseMetadataView(json.optJSONObject("item") ?: throw IllegalStateException("Backend title content is missing item.")),
        content = parseMetadataContentView(json.optJSONObject("content") ?: throw IllegalStateException("Backend title content is missing content.")),
    )
}

internal suspend fun CrispyBackendClient.getMetadataSeasonDetailApi(
    accessToken: String,
    mediaKey: String,
    seasonNumber: Int,
): MetadataSeasonDetailResponse {
    checkConfigured()
    val response = httpClient.get(
        url = "$baseUrl/v1/metadata/titles/${mediaKey.trim()}/seasons/$seasonNumber".toHttpUrl(),
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataSeasonDetailResponse(
        show = parseMetadataView(json.optJSONObject("show") ?: throw IllegalStateException("Backend season detail is missing show.")),
        season = parseMetadataSeasonView(json.optJSONObject("season") ?: throw IllegalStateException("Backend season detail is missing season.")),
        episodes = parseMetadataEpisodeViews(json.optJSONArray("episodes")),
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

internal suspend fun CrispyBackendClient.listMetadataEpisodesApi(
    accessToken: String,
    mediaKey: String,
    seasonNumber: Int? = null,
): MetadataEpisodeListResponse {
    checkConfigured()
    val url = "$baseUrl/v1/metadata/titles/${mediaKey.trim()}/episodes".toHttpUrl().newBuilder()
        .apply {
            if (seasonNumber != null) {
                addQueryParameter("seasonNumber", seasonNumber.toString())
            }
        }
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataEpisodeListResponse(
        show = parseMetadataView(json.optJSONObject("show") ?: throw IllegalStateException("Backend episode list is missing show.")),
        requestedSeasonNumber = json.optIntOrNull("requestedSeasonNumber"),
        effectiveSeasonNumber = json.optIntOrNull("effectiveSeasonNumber")
            ?: throw IllegalStateException("Backend episode list is missing effective season number."),
        includedSeasonNumbers = json.optIntList("includedSeasonNumbers"),
        episodes = parseMetadataEpisodeViews(json.optJSONArray("episodes")),
    )
}

internal suspend fun CrispyBackendClient.getNextEpisodeApi(
    accessToken: String,
    mediaKey: String,
    currentSeasonNumber: Int,
    currentEpisodeNumber: Int,
    watchedKeys: List<String> = emptyList(),
    showMediaKey: String? = null,
    nowMs: Long? = null,
): MetadataNextEpisodeResponse {
    checkConfigured()
    val url = "$baseUrl/v1/metadata/titles/${mediaKey.trim()}/next-episode".toHttpUrl().newBuilder()
        .addQueryParameter("currentSeasonNumber", currentSeasonNumber.toString())
        .addQueryParameter("currentEpisodeNumber", currentEpisodeNumber.toString())
        .apply {
            if (watchedKeys.isNotEmpty()) {
                addQueryParameter("watchedKeys", watchedKeys.joinToString(","))
            }
            if (!showMediaKey.isNullOrBlank()) {
                addQueryParameter("showMediaKey", showMediaKey.trim())
            }
            if (nowMs != null) {
                addQueryParameter("nowMs", nowMs.toString())
            }
        }
        .build()
    val response = httpClient.get(
        url = url,
        headers = authHeaders(accessToken),
        callTimeoutMs = callTimeoutMs,
    )
    val json = JSONObject(requireSuccess(response))
    return MetadataNextEpisodeResponse(
        show = parseMetadataView(json.optJSONObject("show") ?: throw IllegalStateException("Backend next episode response is missing show.")),
        currentSeasonNumber = json.optIntOrNull("currentSeasonNumber") ?: currentSeasonNumber,
        currentEpisodeNumber = json.optIntOrNull("currentEpisodeNumber") ?: currentEpisodeNumber,
        item = json.optJSONObject("item")?.let(::parseMetadataEpisodeView),
    )
}

internal suspend fun CrispyBackendClient.resolvePlaybackApi(
    accessToken: String,
    input: MediaLookupInput,
): PlaybackResolveResponse {
    checkConfigured()
    val response = httpClient.get(
        url = metadataLookupUrl("$baseUrl/v1/playback/resolve", input, includeMediaKey = true),
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
