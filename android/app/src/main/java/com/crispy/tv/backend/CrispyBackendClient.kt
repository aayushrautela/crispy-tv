package com.crispy.tv.backend

import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

class CrispyBackendClient(
    internal val httpClient: CrispyHttpClient,
    backendUrl: String,
) {
    internal val baseUrl: String = backendUrl.trim().trimEnd('/')

    data class User(
        val id: String,
        val email: String?,
    )

    data class Profile(
        val id: String,
        val name: String,
        val avatarKey: String?,
        val isKids: Boolean,
        val sortOrder: Int,
        val createdByUserId: String?,
        val createdAt: String?,
        val updatedAt: String?,
    )

    data class MeResponse(
        val user: User,
        val profiles: List<Profile>,
    )

    data class ProfileSettings(
        val settings: Map<String, String>,
    )

    enum class ImportProvider(val apiValue: String) {
        TRAKT("trakt"),
        SIMKL("simkl"),
    }

    data class ProviderState(
        val provider: String,
        val connectionState: String,
        val accountStatus: String?,
        val primaryAction: String,
        val canImport: Boolean,
        val canReconnect: Boolean,
        val canDisconnect: Boolean,
        val externalUsername: String?,
        val statusLabel: String,
        val statusMessage: String?,
        val lastImportCompletedAt: String?,
    )

    data class ImportJob(
        val id: String,
        val profileId: String,
        val provider: String,
        val mode: String,
        val status: String,
        val requestedByUserId: String,
        val errorMessage: String?,
        val createdAt: String?,
        val startedAt: String?,
        val finishedAt: String?,
        val updatedAt: String?,
    )

    data class ProviderAccountsResponse(
        val providerStates: List<ProviderState>,
    )

    data class StartImportResult(
        val job: ImportJob,
        val providerState: ProviderState,
        val authUrl: String?,
        val nextAction: String,
    )

    data class MediaExternalIds(
        val tmdb: Int?,
        val imdb: String?,
        val tvdb: Int?,
    )

    data class MediaItem(
        val itemId: String,
        val itemType: String,
        val title: String,
        val originalTitle: String?,
        val overview: String?,
        val poster: ResponsiveImageSet,
        val backdrop: ResponsiveImageSet,
        val logo: ResponsiveImageSet,
        val still: ResponsiveImageSet,
        val releaseDate: String?,
        val releaseYear: Int?,
        val rating: Double?,
        val genres: List<String>,
        val runtimeMinutes: Int?,
        val status: String?,
        val maturityRating: String?,
        val certification: String?,
        val externalIds: MediaExternalIds,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val absoluteEpisodeNumber: Int?,
        val episodeTitle: String?,
        val airDate: String?,
        val tagline: String?,
        val seriesId: String?,
        val seriesName: String?,
        val seasonId: String?,
        val seasonName: String?,
        val userData: UserItemData?,
    ) {
        val posterUrl: String?
            get() = poster.medium

        val backdropUrl: String?
            get() = backdrop.medium

        val logoUrl: String?
            get() = logo.medium

        val stillUrl: String?
            get() = still.medium
    }

    data class UserItemData(
        val itemId: String?,
        val isFavorite: Boolean?,
        val played: Boolean?,
        val playCount: Int?,
        val playbackPositionSeconds: Double?,
        val runtimeSeconds: Double?,
        val playedPercentage: Double?,
        val lastPlayedDate: String?,
        val rating: Double?,
        val dismissedFromContinueWatching: Boolean?,
    )

    data class ResponsiveImageSet(
        val small: String?,
        val medium: String?,
        val large: String?,
    ) {
        val isEmpty: Boolean
            get() = small.isNullOrBlank() && medium.isNullOrBlank() && large.isNullOrBlank()
    }

    // --- Search ---

    data class PersonSearchResultItem(
        val kind: String,
        val tmdbPersonId: Int,
        val name: String,
        val knownForDepartment: String?,
        val profileUrl: String?,
        val knownForTitles: List<String>,
    )

    data class SearchResultsResponse(
        val query: String,
        val all: List<MediaItem>,
        val movies: List<MediaItem>,
        val series: List<MediaItem>,
        val people: List<PersonSearchResultItem>,
    )

    data class SearchSuggestionItem(
        val itemId: String,
        val itemType: String,
        val title: String,
        val year: Int?,
        val posterUrl: String?,
        val providerIds: MediaExternalIds,
    )

    data class SearchSuggestionsResponse(
        val suggestions: List<SearchSuggestionItem>,
    )

    data class AiInsightsCard(
        val type: String,
        val title: String,
        val category: String,
        val content: String,
    )

    data class AiInsightsResponse(
        val insights: List<AiInsightsCard>,
        val trivia: String,
    )

    // --- Recommendations ---

    data class RecommendationSection(
        val id: String,
        val title: String,
        val layout: String,
        val sourceKey: String,
        val items: List<MediaItem>,
    )

    data class RecommendationsResponse(
        val profileId: String,
        val sourceKey: String,
        val algorithmVersion: String,
        val source: String,
        val generatedAt: String?,
        val expiresAt: String?,
        val updatedAt: String?,
        val sections: List<RecommendationSection>,
    )

    // --- Calendar ---

    data class CalendarResponse(
        val profileId: String,
        val source: String,
        val kind: String?,
        val generatedAt: String?,
        val items: List<MediaItem>,
    )

    // --- Watch State ---

    data class WatchedStateView(
        val watchedAt: String,
    )

    data class WatchStateResponse(
        val watched: WatchedStateView?,
        val playCount: Int,
        val watchedEpisodeKeys: List<String>,
    )

    data class WatchStateEnvelope(
        val profileId: String,
        val source: String,
        val generatedAt: String?,
        val item: WatchStateResponse,
    )

    data class WatchStatesEnvelope(
        val profileId: String,
        val source: String,
        val generatedAt: String?,
        val items: List<WatchStateResponse>,
    )

    // --- Paged media lists (BaseItemDtoQueryResult) ---

    data class BaseItemDtoQueryResult(
        val items: List<MediaItem>,
        val startIndex: Int,
        val totalRecordCount: Int,
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    // --- Metadata / Playback (Jellyfin Item-based, unchanged) ---

    data class MetadataImages(
        val poster: ResponsiveImageSet,
        val backdrop: ResponsiveImageSet,
        val still: ResponsiveImageSet,
        val logo: ResponsiveImageSet,
    ) {
        val posterUrl: String?
            get() = poster.medium

        val backdropUrl: String?
            get() = backdrop.medium

        val stillUrl: String?
            get() = still.medium

        val logoUrl: String?
            get() = logo.medium
    }

    data class MetadataExternalIds(
        val tmdb: Int?,
        val imdb: String?,
        val tvdb: Int?,
    )

    data class MetadataEpisodePreview(
        val itemId: String,
        val itemType: String,
        val absoluteEpisodeNumber: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val images: MetadataImages,
    ) {
        val id: String
            get() = itemId
    }

    data class MetadataView(
        val itemId: String,
        val itemType: String,
        val kind: String,
        val absoluteEpisodeNumber: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val subtitle: String?,
        val summary: String?,
        val overview: String?,
        val images: MetadataImages,
        val releaseDate: String?,
        val releaseYear: Int?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val certification: String?,
        val status: String?,
        val genres: List<String>,
        val externalIds: MetadataExternalIds,
        val seasonCount: Int?,
        val episodeCount: Int?,
        val nextEpisode: MetadataEpisodePreview?,
    ) {
        val id: String
            get() = itemId
    }

    data class MetadataSeasonView(
        val itemId: String,
        val seasonNumber: Int,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val episodeCount: Int?,
        val posterUrl: String?,
    ) {
        val id: String
            get() = itemId
    }

    data class MetadataEpisodeView(
        val itemId: String,
        val itemType: String,
        val absoluteEpisodeNumber: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val images: MetadataImages,
        val showItemId: String?,
        val showTitle: String?,
        val showExternalIds: MetadataExternalIds,
    ) {
        val id: String
            get() = itemId

        val showId: String?
            get() = showItemId
    }

    data class MetadataResolveResponse(
        val item: MetadataView,
    )

    data class MetadataTitleDetailResponse(
        val item: MetadataView,
        val nextEpisode: MetadataEpisodeView?,
        val videos: List<MetadataVideoView>,
        val cast: List<MetadataPersonRefView>,
        val directors: List<MetadataPersonRefView>,
        val creators: List<MetadataPersonRefView>,
        val production: MetadataProductionInfoView,
    )

    data class MetadataTitleExtrasResponse(
        val seasons: List<MetadataSeasonView>,
        val episodes: List<MetadataEpisodeView>,
        val reviews: List<MetadataReviewView>,
        val similar: List<MetadataCardView>,
        val collection: MetadataCollectionView?,
    )

    data class MetadataCardView(
        val id: String?,
        val itemId: String?,
        val itemType: String,
        val kind: String,
        val absoluteEpisodeNumber: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val subtitle: String?,
        val summary: String?,
        val overview: String?,
        val images: MetadataImages,
        val releaseDate: String?,
        val releaseYear: Int?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val status: String?,
    )

    data class MetadataVideoView(
        val id: String,
        val key: String,
        val name: String?,
        val site: String?,
        val type: String?,
        val official: Boolean,
        val publishedAt: String?,
        val url: String?,
        val thumbnailUrl: String?,
    )

    data class MetadataPersonRefView(
        val id: String,
        val tmdbPersonId: Int?,
        val name: String,
        val role: String?,
        val department: String?,
        val profileUrl: String?,
    )

    data class MetadataReviewView(
        val id: String,
        val provider: String,
        val author: String?,
        val username: String?,
        val content: String,
        val createdAt: String?,
        val updatedAt: String?,
        val url: String?,
        val rating: Double?,
        val avatarUrl: String?,
    )

    data class MetadataCompanyView(
        val id: String,
        val name: String,
        val logo: ResponsiveImageSet,
        val originCountry: String?,
    ) {
        val logoUrl: String?
            get() = logo.medium
    }

    data class MetadataCollectionView(
        val id: String,
        val name: String,
        val poster: ResponsiveImageSet,
        val backdrop: ResponsiveImageSet,
        val parts: List<MetadataCardView>,
    ) {
        val posterUrl: String?
            get() = poster.medium

        val backdropUrl: String?
            get() = backdrop.medium
    }

    data class MetadataProductionInfoView(
        val originalLanguage: String?,
        val originCountries: List<String>,
        val spokenLanguages: List<String>,
        val productionCountries: List<String>,
        val companies: List<MetadataCompanyView>,
        val networks: List<MetadataCompanyView>,
    )

    data class MetadataTitleRatings(
        val imdb: Double?,
        val tmdb: Double?,
        val trakt: Double?,
        val metacritic: Double?,
        val rottenTomatoes: Double?,
        val audience: Double?,
        val letterboxd: Double?,
        val rogerEbert: Double?,
        val myAnimeList: Double?,
    )

    data class MetadataTitleRatingsResponse(
        val ratings: MetadataTitleRatings,
    )

    data class PlaybackResolveResponse(
        val item: MetadataView,
        val show: MetadataView?,
        val season: MetadataSeasonView?,
    )

    data class MetadataPersonKnownForItem(
        val itemId: String,
        val itemType: String,
        val title: String,
        val posterUrl: String?,
        val rating: Double?,
        val releaseYear: Int?,
    ) {
        val id: String
            get() = itemId
    }

    data class MetadataPersonDetail(
        val id: String,
        val tmdbPersonId: Int,
        val name: String,
        val knownForDepartment: String?,
        val biography: String?,
        val birthday: String?,
        val placeOfBirth: String?,
        val profileUrl: String?,
        val imdbId: String?,
        val instagramId: String?,
        val twitterId: String?,
        val knownFor: List<MetadataPersonKnownForItem>,
    )

    // --- Watch Actions ---

    data class WatchActionResponse(
        val accepted: Boolean,
        val mode: String,
    )

    class CrispyBackendException(
        val httpCode: Int,
        val code: String?,
        override val message: String?,
        val category: String?,
        val retryable: Boolean,
        val requestId: String?,
        val details: String?,
    ) : IllegalStateException(message)

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
    ): Profile {
        return createProfileApi(
            accessToken = accessToken,
            name = name,
            sortOrder = sortOrder,
            isKids = isKids,
            avatarKey = avatarKey,
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
    ): StartImportResult {
        return startImportApi(accessToken, profileId, provider, action)
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

    suspend fun searchSuggestions(
        accessToken: String,
        query: String,
        filter: String = "all",
        limit: Int = 8,
        locale: String? = null,
    ): SearchSuggestionsResponse {
        return searchSuggestionsApi(
            accessToken = accessToken,
            query = query,
            filter = filter,
            limit = limit,
            locale = locale,
        )
    }

    suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        itemId: String,
        locale: String? = null,
    ): AiInsightsResponse {
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

    suspend fun resolveMetadata(accessToken: String, input: ItemLookupInput): MetadataResolveResponse {
        return resolveMetadataApi(accessToken, input)
    }

    suspend fun getMetadataItemDetail(accessToken: String, itemId: String): MetadataTitleDetailResponse {
        return getMetadataItemDetailApi(accessToken, itemId)
    }

    suspend fun getMetadataItemExtras(accessToken: String, itemId: String): MetadataTitleExtrasResponse {
        return getMetadataItemExtrasApi(accessToken, itemId)
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

    suspend fun getMetadataPersonDetail(accessToken: String, id: String, language: String? = null): MetadataPersonDetail {
        return getMetadataPersonDetailApi(accessToken, id, language)
    }


    suspend fun resolvePlayback(accessToken: String, input: ItemLookupInput): PlaybackResolveResponse {
        return resolvePlaybackApi(accessToken, input)
    }

    suspend fun getHome(
        accessToken: String,
        profileId: String,
        sourceKey: String? = null,
        algorithmVersion: String? = null,
    ): RecommendationsResponse? {
        return getHomeApi(accessToken, profileId, sourceKey, algorithmVersion)
    }

    suspend fun getCalendar(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarApi(accessToken, profileId)
    }

    suspend fun getCalendarThisWeek(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarThisWeekApi(accessToken, profileId)
    }

    suspend fun sendWatchEvent(accessToken: String, profileId: String, input: PlaybackEventInput): WatchActionResponse {
        return sendWatchEventApi(accessToken, profileId, input)
    }

    suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int = 20,
        cursor: String? = null,
    ): BaseItemDtoQueryResult {
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
    ): BaseItemDtoQueryResult {
        return listWatchHistoryApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): BaseItemDtoQueryResult {
        return listWatchlistApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): BaseItemDtoQueryResult {
        return listRatingsApi(accessToken, profileId, limit, cursor)
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

    suspend fun putRating(
        accessToken: String,
        profileId: String,
        itemId: String,
        rating: Int,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse {
        return putRatingApi(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
            rating = rating,
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

    internal val jsonMediaType
        get() = JSON_MEDIA_TYPE

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
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
    val payload: Map<String, Any?> = emptyMap(),
)

data class PlaybackEventInput(
    val clientEventId: String,
    val eventType: String,
    val itemId: String,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val occurredAt: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class ImportJobsResponse(
    val jobs: List<CrispyBackendClient.ImportJob>,
)
