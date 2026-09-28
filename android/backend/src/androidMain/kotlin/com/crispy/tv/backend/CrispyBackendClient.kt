package com.crispy.tv.backend

import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

data class RemoteTrailerDto(
    val url: String,
    val thumbnailUrl: String?,
)

/**
 * Talks to the Crispy backend.
 *
 * This class is `androidMain` because it speaks OkHttp and `org.json`. The types it
 * exchanges are not: they are pure data and live in `commonMain`, in
 * `BackendTypes.kt`, so that `commonMain` code on every target can name them
 * without depending on this transport.
 */
class CrispyBackendClient(
    internal val httpClient: CrispyHttpClient,
    backendUrl: String,
    internal val aiHttpClient: CrispyHttpClient = httpClient,
) {
    val baseUrl: String = backendUrl.trim().trimEnd('/')

    fun isConfigured(): Boolean {
        return baseUrl.isNotBlank()
    }

    suspend fun getMe(accessToken: String): MeResponse {
        return getMeApi(accessToken)
    }

    suspend fun createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int? = null,
        isKids: Boolean = false,
        avatarKey: String? = null,
        interfaceLanguage: String? = null,
    ): Profile {
        return createProfileApi(
            accessToken = accessToken,
            name = name,
            sortOrder = sortOrder,
            isKids = isKids,
            avatarKey = avatarKey,
            interfaceLanguage = interfaceLanguage,
        )
    }

    suspend fun bootstrapAccount(
        accessToken: String,
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String? = null,
    ): Profile {
        return bootstrapAccountApi(
            accessToken = accessToken,
            name = name,
            interfaceLanguage = interfaceLanguage,
            avatarUrl = avatarUrl,
            region = region,
        )
    }

    suspend fun listImportConnections(accessToken: String, profileId: String): ProviderAccountsResponse {
        return listImportConnectionsApi(accessToken, profileId)
    }

    suspend fun listImportJobs(accessToken: String, profileId: String): ImportJobsResponse {
        return listImportJobsApi(accessToken, profileId)
    }

    suspend fun startImport(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
        action: String,
        clientId: String,
        returnTo: String,
    ): StartImportResult {
        return startImportApi(accessToken, profileId, provider, action, clientId, returnTo)
    }

    suspend fun getProfileSettings(accessToken: String, profileId: String): ProfileSettings {
        return getProfileSettingsApi(accessToken, profileId)
    }

    suspend fun patchProfileSettings(accessToken: String, profileId: String, settings: Map<String, String>): ProfileSettings {
        return patchProfileSettingsApi(accessToken, profileId, settings)
    }

    suspend fun searchTitles(
        accessToken: String,
        query: String,
        limit: Int = 20,
    ): SearchResultsResponse {
        return searchTitlesApi(
            accessToken = accessToken,
            query = query,
            limit = limit,
        )
    }

    suspend fun searchSuggestions(
        accessToken: String,
        query: String,
        limit: Int = 8,
    ): SearchSuggestionsResponse {
        return searchSuggestionsApi(
            accessToken = accessToken,
            query = query,
            limit = limit,
        )
    }

    suspend fun searchTitlesByGenre(
        accessToken: String,
        genre: String,
        limit: Int = 20,
    ): SearchResultsResponse {
        return searchTitlesByGenreApi(
            accessToken = accessToken,
            genre = genre,
            limit = limit,
        )
    }

    suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        locale: String? = null,
    ): SearchResultsResponse {
        return searchAiTitlesApi(
            accessToken = accessToken,
            profileId = profileId,
            query = query,
            locale = locale,
        )
    }

    suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        itemId: String,
        locale: String? = null,
    ): AiInsightsResult {
        return getAiInsightsApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            locale = locale,
        )
    }

    suspend fun disconnectImportConnection(accessToken: String, profileId: String, provider: ImportProvider): ProviderState {
        return disconnectImportConnectionApi(accessToken, profileId, provider)
    }

    suspend fun listProfiles(accessToken: String): List<Profile> {
        return listProfilesApi(accessToken)
    }

    suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        input: UpdateProfileInput,
    ): Profile {
        return updateProfileApi(accessToken, profileId, input)
    }

    suspend fun getAccountSettings(accessToken: String): AccountSettings {
        return getAccountSettingsApi(accessToken)
    }

    suspend fun patchAccountSettings(
        accessToken: String,
        settings: Map<String, String>,
    ): AccountSettings {
        return patchAccountSettingsApi(
            accessToken = accessToken,
            settings = settings,
        )
    }

    suspend fun deleteAccount(accessToken: String): Boolean {
        return deleteAccountApi(accessToken)
    }

    suspend fun listAddons(accessToken: String): List<AddonDto> {
        return listAddonsApi(accessToken)
    }

    suspend fun installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String = "stremio",
        payload: Map<String, String> = emptyMap(),
    ): AddonDto {
        return installAddonApi(accessToken, profileId, manifestUrl, type, payload)
    }

    suspend fun uninstallAddon(accessToken: String, profileId: String, addonId: String): Boolean {
        return uninstallAddonApi(accessToken, profileId, addonId)
    }

    suspend fun getAvatars(): List<Avatar> {
        return getAvatarsApi()
    }

    suspend fun getMetadataItemDetail(accessToken: String, itemId: String): MetadataTitleDetailResponse {
        return getMetadataItemDetailApi(accessToken, itemId)
    }

    suspend fun getMetadataItemExtras(accessToken: String, itemId: String): MetadataTitleExtrasResponse {
        return getMetadataItemExtrasApi(accessToken, itemId)
    }

    suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int? = null,
    ): MetadataSeriesEpisodesResponse {
        return getSeriesEpisodesApi(accessToken, seriesItemId, season)
    }

    suspend fun getMetadataItemRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): MetadataTitleRatingsResponse {
        return getMetadataItemRatingsApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
        )
    }

    suspend fun getMetadataPersonDetail(accessToken: String, personId: String, language: String? = null): MetadataPersonDetail {
        return getMetadataPersonDetailApi(accessToken, personId, language)
    }


    suspend fun resolvePlayback(accessToken: String, input: ItemLookupInput): PlaybackResolveResponse {
        return resolvePlaybackApi(accessToken, input)
    }

    suspend fun getHome(
        accessToken: String,
        profileId: String,
    ): ProfileHomeResponse? {
        return getHomeApi(accessToken, profileId)
    }

    suspend fun browseTitles(
        accessToken: String,
        type: String,
        genre: String? = null,
        sort: String = "popularity",
        page: Int = 0,
    ): BrowseTitlesResponse {
        return browseTitlesApi(
            accessToken = accessToken,
            type = type,
            genre = genre,
            sort = sort,
            page = page,
        )
    }

    suspend fun getCalendar(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarApi(accessToken, profileId)
    }

    suspend fun getCalendarThisWeek(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarThisWeekApi(accessToken, profileId)
    }

    suspend fun getUpNext(accessToken: String, profileId: String, limit: Int = 20): UpNextResponse {
        return getUpNextApi(accessToken, profileId, limit)
    }

    suspend fun sendWatchEvent(accessToken: String, profileId: String, input: PlaybackEventInput): WatchActionResponse {
        return sendWatchEventApi(accessToken, profileId, input)
    }

    suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int = 20,
        cursor: String? = null,
    ): ClientMediaCardQueryResult {
        return listContinueWatchingApi(accessToken, profileId, limit, cursor)
    }

    suspend fun dismissContinueWatching(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return dismissContinueWatchingApi(accessToken, profileId, itemId)
    }

    suspend fun listWatchHistory(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult {
        return listWatchHistoryApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult {
        return listWatchlistApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult {
        return listRatingsApi(accessToken, profileId, limit, cursor)
    }

    suspend fun getWatchGenerations(
        accessToken: String,
        profileId: String,
    ): WatchGenerationsResponse {
        return getWatchGenerationsApi(accessToken, profileId)
    }

    suspend fun getWatchState(accessToken: String, profileId: String, itemId: String): WatchStateEnvelope {
        return getWatchStateApi(accessToken, profileId, itemId)
    }

    suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): WatchStatesEnvelope {
        return getWatchStatesApi(accessToken, profileId, itemIds)
    }

    suspend fun getWatchStateMap(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): Map<String, WatchStateResponse> {
        if (itemIds.isEmpty()) return emptyMap()
        return getWatchStatesApi(accessToken, profileId, itemIds)
            .items
            .associateBy { it.itemId }
    }

    suspend fun markWatched(accessToken: String, profileId: String, input: WatchMutationInput): WatchActionResponse {
        return markWatchedApi(accessToken, profileId, input)
    }

    suspend fun unmarkWatched(accessToken: String, profileId: String, input: WatchMutationInput): WatchActionResponse {
        return unmarkWatchedApi(accessToken, profileId, input)
    }

    suspend fun putWatchlist(
        accessToken: String,
        profileId: String,
        itemId: String,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse {
        return putWatchlistApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            occurredAt = occurredAt,
            payload = payload,
        )
    }

    suspend fun deleteWatchlist(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return deleteWatchlistApi(accessToken, profileId, itemId)
    }

    suspend fun setLiked(
        accessToken: String,
        profileId: String,
        itemId: String,
        liked: Boolean,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse {
        return setLikedApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            liked = liked,
            occurredAt = occurredAt,
            payload = payload,
        )
    }

    suspend fun deleteRating(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return deleteRatingApi(accessToken, profileId, itemId)
    }

    internal fun checkConfigured() {
        if (!isConfigured()) {
            throw IllegalStateException("Backend API is not configured.")
        }
    }

    internal fun authHeaders(accessToken: String): Headers {
        return Headers.Builder()
            .add("Authorization", "Bearer ${accessToken.trim()}")
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    internal fun authHeaders(accessToken: String, profileId: String): Headers {
        val builder = authHeaders(accessToken).newBuilder()
        if (profileId.isNotBlank()) {
            builder.add("X-Profile-ID", profileId.trim())
        }
        return builder.build()
    }

    internal fun requireSuccess(response: CrispyHttpResponse): JSONObject {
        return if (response.code in 200..299) {
            extractDataEnvelope(response.body)
        } else {
            throw parseErrorEnvelope(response.code, response.body)
        }
    }

    private fun extractDataEnvelope(body: String): JSONObject {
        if (body.isBlank()) {
            throw IllegalStateException("Empty response body")
        }
        val json = JSONObject(body)
        return json.optJSONObject("data")
            ?: throw IllegalStateException("Response missing 'data' envelope")
    }

    private fun parseErrorEnvelope(code: Int, body: String): CrispyBackendException {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            return CrispyBackendException(
                httpCode = code, code = null, message = "HTTP $code",
                category = null, retryable = false, requestId = null, details = null,
            )
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        val error = json?.optJSONObject("error")
        return CrispyBackendException(
            httpCode = code,
            code = error?.optString("code")?.trim()?.ifBlank { null },
            message = error?.optString("message")?.trim()
                ?: json?.optString("message")?.trim()
                ?: "HTTP $code",
            category = error?.optString("category")?.trim()?.ifBlank { null },
            retryable = error?.optBoolean("retryable", false) ?: false,
            requestId = error?.optString("requestId")?.trim()?.ifBlank { null }
                ?: json?.optString("requestId")?.trim()?.ifBlank { null },
            details = error?.optJSONObject("details")?.toString()?.ifBlank { null },
        )
    }

    internal val callTimeoutMs: Long
        get() = CALL_TIMEOUT_MS

    internal val aiCallTimeoutMs: Long
        get() = AI_CALL_TIMEOUT_MS

    internal val jsonMediaType
        get() = JSON_MEDIA_TYPE

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
        private const val AI_CALL_TIMEOUT_MS = 120_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class ItemLookupInput(
    val itemId: String? = null,
)

data class WatchMutationInput(
    val itemId: String,
    val occurredAt: String? = null,
    val rating: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class PlaybackEventInput(
    val clientEventId: String,
    val eventType: String,
    val itemId: String,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val occurredAt: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class ImportJobsResponse(
    val jobs: List<ImportJob>,
)
