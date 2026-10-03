package com.crispy.tv.backend

import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RemoteTrailerDto(
    val url: String,
    val thumbnailUrl: String?,
)

/**
 * Talks to the Crispy backend.
 *
 * **Both reasons this class was once `androidMain` are gone, and the class is
 * now in `commonMain`.** OkHttp went when the transport became the
 * `CrispyHttpClient` port, and `org.json` went when [requireSuccess] started
 * answering `JsonObject`. The 39 `internal fun CrispyBackendClient.parseX(...)`
 * extensions in `CrispyBackendParsers.kt` moved with it after their receiver
 * became node-neutral -- **a file whose receiver is pinned cannot be freed by
 * changing its arguments**, so the receiver's declaration had to move first.
 * The Android-only transport adapters are separate implementations behind the
 * common `BackendApi` surface.
 *
 * The types it exchanges are not pinned at all: they are pure data and live in
 * `commonMain`, in `BackendTypes.kt`, so that `commonMain` code on every target
 * can name them without depending on this transport.
 */
class CrispyBackendClient(
    internal val httpClient: CrispyHttpClient,
    backendUrl: String,
    internal val aiHttpClient: CrispyHttpClient = httpClient,
) : BackendApi {
    override val baseUrl: String = backendUrl.trim().trimEnd('/')

    override fun isConfigured(): Boolean {
        return baseUrl.isNotBlank()
    }

    override suspend fun getMe(accessToken: String): MeResponse {
        return getMeApi(accessToken)
    }

    override suspend fun createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int?,
        isKids: Boolean,
        avatarKey: String?,
        interfaceLanguage: String?,
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

    override suspend fun bootstrapAccount(
        accessToken: String,
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String?,
    ): Profile {
        return bootstrapAccountApi(
            accessToken = accessToken,
            name = name,
            interfaceLanguage = interfaceLanguage,
            avatarUrl = avatarUrl,
            region = region,
        )
    }

    override suspend fun listImportConnections(accessToken: String, profileId: String): ProviderAccountsResponse {
        return listImportConnectionsApi(accessToken, profileId)
    }

    override suspend fun listImportJobs(accessToken: String, profileId: String): ImportJobsResponse {
        return listImportJobsApi(accessToken, profileId)
    }

    override suspend fun startImport(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
        action: String,
        clientId: String,
        returnTo: String,
    ): StartImportResult {
        return startImportApi(accessToken, profileId, provider, action, clientId, returnTo)
    }

    override suspend fun getProfileSettings(accessToken: String, profileId: String): ProfileSettings {
        return getProfileSettingsApi(accessToken, profileId)
    }

    override suspend fun patchProfileSettings(
        accessToken: String,
        profileId: String,
        settings: Map<String,
        String>,
    ): ProfileSettings {
        return patchProfileSettingsApi(accessToken, profileId, settings)
    }

    override suspend fun searchTitles(accessToken: String, query: String, limit: Int): SearchResultsResponse {
        return searchTitlesApi(
            accessToken = accessToken,
            query = query,
            limit = limit,
        )
    }

    override suspend fun searchSuggestions(accessToken: String, query: String, limit: Int): SearchSuggestionsResponse {
        return searchSuggestionsApi(
            accessToken = accessToken,
            query = query,
            limit = limit,
        )
    }

    override suspend fun searchTitlesByGenre(accessToken: String, genre: String, limit: Int): SearchResultsResponse {
        return searchTitlesByGenreApi(
            accessToken = accessToken,
            genre = genre,
            limit = limit,
        )
    }

    override suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        locale: String?,
    ): SearchResultsResponse {
        return searchAiTitlesApi(
            accessToken = accessToken,
            profileId = profileId,
            query = query,
            locale = locale,
        )
    }

    override suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        itemId: String,
        locale: String?,
    ): AiInsightsResult {
        return getAiInsightsApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            locale = locale,
        )
    }

    override suspend fun disconnectImportConnection(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
    ): ProviderState {
        return disconnectImportConnectionApi(accessToken, profileId, provider)
    }

    override suspend fun listProfiles(accessToken: String): List<Profile> {
        return listProfilesApi(accessToken)
    }

    override suspend fun updateProfile(accessToken: String, profileId: String, input: UpdateProfileInput): Profile {
        return updateProfileApi(accessToken, profileId, input)
    }

    override suspend fun getAccountSettings(accessToken: String): AccountSettings {
        return getAccountSettingsApi(accessToken)
    }

    override suspend fun patchAccountSettings(accessToken: String, settings: Map<String, String>): AccountSettings {
        return patchAccountSettingsApi(
            accessToken = accessToken,
            settings = settings,
        )
    }

    override suspend fun deleteAccount(accessToken: String): Boolean {
        return deleteAccountApi(accessToken)
    }

    override suspend fun listAddons(accessToken: String): List<AddonDto> {
        return listAddonsApi(accessToken)
    }

    override suspend fun installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String,
        payload: Map<String, String>,
    ): AddonDto {
        return installAddonApi(accessToken, profileId, manifestUrl, type, payload)
    }

    override suspend fun uninstallAddon(accessToken: String, profileId: String, addonId: String): Boolean {
        return uninstallAddonApi(accessToken, profileId, addonId)
    }

    override suspend fun getAvatars(): List<Avatar> {
        return getAvatarsApi()
    }

    override suspend fun getMetadataItemDetail(accessToken: String, itemId: String): MetadataTitleDetailResponse {
        return getMetadataItemDetailApi(accessToken, itemId)
    }

    override suspend fun getMetadataItemExtras(accessToken: String, itemId: String): MetadataTitleExtrasResponse {
        return getMetadataItemExtrasApi(accessToken, itemId)
    }

    override suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int?,
    ): MetadataSeriesEpisodesResponse {
        return getSeriesEpisodesApi(accessToken, seriesItemId, season)
    }

    override suspend fun getMetadataItemRatings(
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

    override suspend fun getMetadataPersonDetail(
        accessToken: String,
        personId: String,
        language: String?,
    ): MetadataPersonDetail {
        return getMetadataPersonDetailApi(accessToken, personId, language)
    }


    override suspend fun resolvePlayback(accessToken: String, input: ItemLookupInput): PlaybackResolveResponse {
        return resolvePlaybackApi(accessToken, input)
    }

    override suspend fun getHome(accessToken: String, profileId: String): ProfileHomeResponse? {
        return getHomeApi(accessToken, profileId)
    }

    override suspend fun browseTitles(
        accessToken: String,
        type: String,
        genre: String?,
        sort: String,
        page: Int,
    ): BrowseTitlesResponse {
        return browseTitlesApi(
            accessToken = accessToken,
            type = type,
            genre = genre,
            sort = sort,
            page = page,
        )
    }

    override suspend fun getCalendar(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarApi(accessToken, profileId)
    }

    override suspend fun getCalendarThisWeek(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarThisWeekApi(accessToken, profileId)
    }

    override suspend fun getUpNext(accessToken: String, profileId: String, limit: Int): UpNextResponse {
        return getUpNextApi(accessToken, profileId, limit)
    }

    override suspend fun sendWatchEvent(
        accessToken: String,
        profileId: String,
        input: PlaybackEventInput,
    ): WatchActionResponse {
        return sendWatchEventApi(accessToken, profileId, input)
    }

    override suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?,
    ): ClientMediaCardQueryResult {
        return listContinueWatchingApi(accessToken, profileId, limit, cursor)
    }

    override suspend fun dismissContinueWatching(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): WatchActionResponse {
        return dismissContinueWatchingApi(accessToken, profileId, itemId)
    }

    override suspend fun listWatchHistory(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?,
    ): ClientMediaCardQueryResult {
        return listWatchHistoryApi(accessToken, profileId, limit, cursor)
    }

    override suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?,
    ): ClientMediaCardQueryResult {
        return listWatchlistApi(accessToken, profileId, limit, cursor)
    }

    override suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?,
    ): ClientMediaCardQueryResult {
        return listRatingsApi(accessToken, profileId, limit, cursor)
    }

    override suspend fun getWatchGenerations(accessToken: String, profileId: String): WatchGenerationsResponse {
        return getWatchGenerationsApi(accessToken, profileId)
    }

    override suspend fun getWatchState(accessToken: String, profileId: String, itemId: String): WatchStateEnvelope {
        return getWatchStateApi(accessToken, profileId, itemId)
    }

    override suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): WatchStatesEnvelope {
        return getWatchStatesApi(accessToken, profileId, itemIds)
    }

    override suspend fun getWatchStateMap(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): Map<String, WatchStateResponse> {
        if (itemIds.isEmpty()) return emptyMap()
        return getWatchStatesApi(accessToken, profileId, itemIds)
            .items
            .associateBy { it.itemId }
    }

    override suspend fun markWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput,
    ): WatchActionResponse {
        return markWatchedApi(accessToken, profileId, input)
    }

    override suspend fun unmarkWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput,
    ): WatchActionResponse {
        return unmarkWatchedApi(accessToken, profileId, input)
    }

    override suspend fun putWatchlist(
        accessToken: String,
        profileId: String,
        itemId: String,
        occurredAt: String?,
        payload: Map<String, Any?>,
    ): WatchActionResponse {
        return putWatchlistApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            occurredAt = occurredAt,
            payload = payload,
        )
    }

    override suspend fun deleteWatchlist(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return deleteWatchlistApi(accessToken, profileId, itemId)
    }

    override suspend fun setLiked(
        accessToken: String,
        profileId: String,
        itemId: String,
        liked: Boolean,
        occurredAt: String?,
        payload: Map<String, Any?>,
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

    override suspend fun deleteRating(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return deleteRatingApi(accessToken, profileId, itemId)
    }

    internal fun checkConfigured() {
        if (!isConfigured()) {
            throw IllegalStateException("Backend API is not configured.")
        }
    }

    /**
     * A `Map<String, String>` rather than OkHttp's `Headers`, which is the whole
     * reason this file can drop its last three `okhttp3` imports. **It is one
     * function on purpose:** all 48 call sites pass `authHeaders(accessToken)`,
     * so changing the return type here is what fixes every one of them, and
     * changing it anywhere else would leave 48 sites still building a `Headers`
     * that nothing accepts.
     *
     * `+` is the equivalent of the old `Headers.newBuilder().add(...)`: the
     * one-argument overload never sets `X-Profile-ID`, so there is no key for
     * `+` to overwrite, and a `Map` cannot hold the duplicate an
     * `Headers.Builder` would have allowed.
     */
    internal fun authHeaders(accessToken: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer ${accessToken.trim()}",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )

    internal fun authHeaders(accessToken: String, profileId: String): Map<String, String> =
        if (profileId.isBlank()) {
            authHeaders(accessToken)
        } else {
            authHeaders(accessToken) + ("X-Profile-ID" to profileId.trim())
        }

    internal fun requireSuccess(response: CrispyHttpResponse): JsonObject {
        return if (response.code in 200..299) {
            extractDataEnvelope(response.body)
        } else {
            throw parseErrorEnvelope(response.code, response.body)
        }
    }

    private fun extractDataEnvelope(body: String): JsonObject {
        if (body.isBlank()) {
            throw IllegalStateException("Empty response body")
        }
        val json = parseJsonOrNull(body)
            ?: throw IllegalArgumentException("Malformed response body")
        return json.optJsonObject("data")
            ?: throw IllegalStateException("Response missing 'data' envelope")
    }

    /**
     * `Json.parseToJsonElement` throws `SerializationException`, not
     * `org.json`'s `JSONException`, so the one place that deliberately tolerated a
     * malformed body now catches the other type. **The exception a caller sees
     * for a malformed body changed name**, which is why [extractDataEnvelope]
     * above converts it to an `IllegalArgumentException` rather than letting a
     * serialization-library type escape a transport seam.
     */
    private fun parseJsonOrNull(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun parseErrorEnvelope(code: Int, body: String): CrispyBackendException {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            return CrispyBackendException(
                httpCode = code, code = null, message = "HTTP $code",
                category = null, retryable = false, requestId = null, details = null,
            )
        }
        val json = parseJsonOrNull(trimmed)
        val error = json?.optJsonObject("error")
        return CrispyBackendException(
            httpCode = code,
            code = error?.optStringOrEmpty("code")?.trim()?.ifBlank { null },
            message = error?.optStringOrEmpty("message")?.trim()
                ?: json?.optStringOrEmpty("message")?.trim()
                ?: "HTTP $code",
            category = error?.optStringOrEmpty("category")?.trim()?.ifBlank { null },
            retryable = error?.jsonPrimitiveOrNull("retryable")?.booleanOrNull ?: false,
            requestId = error?.optStringOrEmpty("requestId")?.trim()?.ifBlank { null }
                ?: json?.optStringOrEmpty("requestId")?.trim()?.ifBlank { null },
            details = error?.optJsonObject("details")?.toString()?.ifBlank { null },
        )
    }

    internal val callTimeoutMs: Long
        get() = CALL_TIMEOUT_MS

    internal val aiCallTimeoutMs: Long
        get() = AI_CALL_TIMEOUT_MS

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
        private const val AI_CALL_TIMEOUT_MS = 120_000L
    }
}
