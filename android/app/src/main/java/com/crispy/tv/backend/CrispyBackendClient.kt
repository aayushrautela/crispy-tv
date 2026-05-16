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

    data class MediaExternalIds(
        val tmdb: Int?,
        val imdb: String?,
        val tvdb: Int?,
    )

    data class MediaItem(
        val mediaKey: String,
        val mediaType: String,
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

    data class SurfaceContext(
        val values: Map<String, Any?>,
    )

    data class MediaPresentationHint(
        val preferredSize: String?,
        val sectionId: String?,
        val sectionTitle: String?,
    )

    data class SearchResultItem(
        val kind: String,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
    )

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
        val all: List<SearchResultItem>,
        val movies: List<SearchResultItem>,
        val series: List<SearchResultItem>,
        val people: List<PersonSearchResultItem>,
    )

    data class SearchSuggestionItem(
        val tmdbId: Int,
        val mediaType: String,
        val title: String,
        val year: Int?,
        val posterPath: String?,
        val popularity: Double,
        val overview: String?,
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

    data class ImportJobsResponse(
        val jobs: List<ImportJob>,
    )

    data class StartImportResult(
        val job: ImportJob,
        val providerState: ProviderState,
        val authUrl: String?,
        val nextAction: String,
    )

data class MediaLookupInput(
    val mediaKey: String? = null,
    val mediaType: String? = null,
    val tmdbId: Int? = null,
    val showTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

data class WatchMutationInput(
    val mediaKey: String? = null,
    val mediaType: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val occurredAt: String? = null,
    val rating: Int? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

data class PlaybackEventInput(
    val clientEventId: String,
    val eventType: String,
    val mediaKey: String? = null,
    val mediaType: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val positionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val occurredAt: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

    data class ResponsiveImageSet(
        val small: String?,
        val medium: String?,
        val large: String?,
    ) {
        val isEmpty: Boolean
            get() = small.isNullOrBlank() && medium.isNullOrBlank() && large.isNullOrBlank()
    }

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
        val mediaKey: String,
        val mediaType: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
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
            get() = mediaKey
    }

    data class MetadataView(
        val mediaKey: String,
        val mediaType: String,
        val kind: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
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
            get() = mediaKey
    }

    data class MetadataSeasonView(
        val mediaKey: String,
        val showTmdbId: Int?,
        val seasonNumber: Int,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val episodeCount: Int?,
        val posterUrl: String?,
    ) {
        val id: String
            get() = mediaKey
    }

    data class MetadataEpisodeView(
        val mediaKey: String,
        val mediaType: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
        val absoluteEpisodeNumber: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val images: MetadataImages,
        val showMediaKey: String?,
        val showTitle: String?,
        val showExternalIds: MetadataExternalIds,
    ) {
        val id: String
            get() = mediaKey

        val showId: String?
            get() = showMediaKey
    }

    data class MetadataResolveResponse(
        val item: MetadataView,
    )

    data class MetadataTitleDetailResponse(
        val item: MetadataView,
        val seasons: List<MetadataSeasonView>,
        val episodes: List<MetadataEpisodeView>,
        val nextEpisode: MetadataEpisodeView?,
        val videos: List<MetadataVideoView>,
        val cast: List<MetadataPersonRefView>,
        val directors: List<MetadataPersonRefView>,
        val creators: List<MetadataPersonRefView>,
        val production: MetadataProductionInfoView,
        val collection: MetadataCollectionView?,
        val similar: List<MetadataCardView>,
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
        val mediaKey: String?,
        val mediaType: String,
        val kind: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
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
        val mediaKey: String,
        val mediaType: String,
        val tmdbId: Int?,
        val title: String,
        val posterUrl: String?,
        val rating: Double?,
        val releaseYear: Int?,
    ) {
        val id: String
            get() = mediaKey
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

    data class WatchProgressView(
        val positionSeconds: Double?,
        val durationSeconds: Double?,
        val progressPercent: Double,
        val lastPlayedAt: String?,
    )

    data class ContinueWatchingStateView(
        val id: String,
        val positionSeconds: Double?,
        val durationSeconds: Double?,
        val progressPercent: Double,
        val lastActivityAt: String,
    )

    data class WatchedStateView(
        val watchedAt: String,
    )

    data class WatchlistStateView(
        val addedAt: String,
    )

    data class RatingStateView(
        val value: Int,
        val ratedAt: String,
    )

    data class ContinueWatchingItem(
        val id: String,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
        val progress: WatchProgressView?,
        val lastActivityAt: String,
        val origins: List<String>,
        val dismissible: Boolean,
    )

    data class HistoryItem(
        val id: String,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
        val eventType: String,
        val occurredAt: String?,
        val watchedAt: String?,
        val origins: List<String>,
    )

    data class WatchlistItem(
        val id: String?,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
        val addedAt: String?,
        val origins: List<String>,
    )

    data class RatingItem(
        val id: String?,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
        val rating: RatingStateView,
        val origins: List<String>,
    )

    data class PageInfo(
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    data class CanonicalWatchCollectionResponse<T>(
        val profileId: String,
        val kind: String,
        val source: String,
        val generatedAt: String?,
        val items: List<T>,
        val pageInfo: PageInfo,
    )

    data class WatchStateResponse(
        val kind: String,
        val mediaItem: MediaItem,
        val context: SurfaceContext,
        val presentation: MediaPresentationHint?,
        val progress: WatchProgressView?,
        val continueWatching: ContinueWatchingStateView?,
        val watched: WatchedStateView?,
        val watchlist: WatchlistStateView?,
        val rating: RatingStateView?,
        val watchedEpisodeKeys: List<String>,
        val playCount: Int = 0,
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

    data class CalendarContext(
        val bucket: String,
        val airDate: String?,
        val watched: Boolean,
        val relatedShow: MediaItem,
    )

    data class CalendarItem(
        val bucket: String,
        val kind: String,
        val mediaItem: MediaItem,
        val context: CalendarContext,
        val presentation: MediaPresentationHint?,
        val airDate: String?,
        val watched: Boolean,
    )

    data class CalendarResponse(
        val profileId: String,
        val source: String,
        val kind: String?,
        val generatedAt: String?,
        val items: List<CalendarItem>,
    )

    data class RecommendationItemContext(
        val reason: String?,
        val reasonCodes: List<String>,
        val score: Double?,
        val rank: Double?,
        val payload: Map<String, Any?>,
    )

    data class RecommendationItem(
        val kind: String,
        val mediaItem: MediaItem,
        val context: RecommendationItemContext,
        val presentation: MediaPresentationHint?,
        val reason: String?,
        val score: Double?,
        val rank: Double,
        val payload: Map<String, Any?>,
    )

    data class RecommendationHeroItem(
        val mediaKey: String,
        val mediaType: String,
        val title: String,
        val description: String,
        val backdrop: ResponsiveImageSet,
        val poster: ResponsiveImageSet,
        val logo: ResponsiveImageSet,
        val releaseYear: Int?,
        val rating: Double?,
        val genre: String?,
    )

    data class RecommendationCollectionItem(
        val mediaType: String,
        val title: String,
        val poster: ResponsiveImageSet,
        val releaseYear: Int?,
        val rating: Double?,
    )

    data class RecommendationCollectionCard(
        val title: String,
        val logo: ResponsiveImageSet,
        val items: List<RecommendationCollectionItem>,
    )

    data class RecommendationSection(
        val id: String,
        val title: String,
        val layout: String,
        val sourceKey: String,
        val recommendationItems: List<RecommendationItem> = emptyList(),
        val heroItems: List<RecommendationHeroItem> = emptyList(),
        val collectionItems: List<RecommendationCollectionCard> = emptyList(),
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
        mediaKey: String,
        locale: String? = null,
    ): AiInsightsResponse {
        return getAiInsightsApi(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
            locale = locale,
        )
    }

    suspend fun disconnectImportConnection(accessToken: String, profileId: String, provider: ImportProvider): ProviderState {
        return disconnectImportConnectionApi(accessToken, profileId, provider)
    }

    suspend fun resolveMetadata(accessToken: String, input: MediaLookupInput): MetadataResolveResponse {
        return resolveMetadataApi(accessToken, input)
    }

    suspend fun getMetadataTitleDetail(accessToken: String, mediaKey: String): MetadataTitleDetailResponse {
        return getMetadataTitleDetailApi(accessToken, mediaKey)
    }

    suspend fun getMetadataTitleExtras(accessToken: String, mediaKey: String): MetadataTitleExtrasResponse {
        return getMetadataTitleExtrasApi(accessToken, mediaKey)
    }

    suspend fun getMetadataTitleRatings(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): MetadataTitleRatingsResponse {
        return getMetadataTitleRatingsApi(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
        )
    }

    suspend fun getMetadataPersonDetail(accessToken: String, id: String, language: String? = null): MetadataPersonDetail {
        return getMetadataPersonDetailApi(accessToken, id, language)
    }


    suspend fun resolvePlayback(accessToken: String, input: MediaLookupInput): PlaybackResolveResponse {
        return resolvePlaybackApi(accessToken, input)
    }

    suspend fun getRecommendations(accessToken: String, profileId: String): RecommendationsResponse? {
        return getRecommendationsApi(accessToken, profileId)
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
    ): CanonicalWatchCollectionResponse<ContinueWatchingItem> {
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
    ): CanonicalWatchCollectionResponse<HistoryItem> {
        return listWatchHistoryApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): CanonicalWatchCollectionResponse<WatchlistItem> {
        return listWatchlistApi(accessToken, profileId, limit, cursor)
    }

    suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): CanonicalWatchCollectionResponse<RatingItem> {
        return listRatingsApi(accessToken, profileId, limit, cursor)
    }

    suspend fun getWatchState(accessToken: String, profileId: String, mediaKey: String): WatchStateEnvelope {
        return getWatchStateApi(accessToken, profileId, mediaKey)
    }

    suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        mediaKeys: List<String>,
    ): WatchStatesEnvelope {
        return getWatchStatesApi(accessToken, profileId, mediaKeys)
    }

    suspend fun markWatched(accessToken: String, profileId: String, input: WatchMutationInput): WatchActionResponse {
        return markWatchedApi(accessToken, profileId, input)
    }

    suspend fun unmarkWatched(accessToken: String, profileId: String, input: WatchMutationInput): WatchActionResponse {
        return unmarkWatchedApi(accessToken, profileId, input)
    }

    suspend fun putNativeWatchlist(
        accessToken: String,
        profileId: String,
        mediaKey: String,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse {
        return putNativeWatchlistApi(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
            occurredAt = occurredAt,
            payload = payload,
        )
    }

    suspend fun deleteNativeWatchlist(accessToken: String, profileId: String, mediaKey: String): WatchActionResponse {
        return deleteNativeWatchlistApi(accessToken, profileId, mediaKey)
    }

    suspend fun putNativeRating(
        accessToken: String,
        profileId: String,
        mediaKey: String,
        rating: Int,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse {
        return putNativeRatingApi(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
            rating = rating,
            occurredAt = occurredAt,
            payload = payload,
        )
    }

    suspend fun deleteNativeRating(accessToken: String, profileId: String, mediaKey: String): WatchActionResponse {
        return deleteNativeRatingApi(accessToken, profileId, mediaKey)
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

internal fun metadataLookupUrl(
    path: String,
    input: MediaLookupInput,
) = path.toHttpUrl().newBuilder()
    .apply {
        if (!input.mediaKey.isNullOrBlank()) addQueryParameter("mediaKey", input.mediaKey.trim())
        if (!input.mediaType.isNullOrBlank()) addQueryParameter("mediaType", input.mediaType.trim())
        if (input.tmdbId != null) addQueryParameter("tmdbId", input.tmdbId.toString())
        if (input.seasonNumber != null) addQueryParameter("seasonNumber", input.seasonNumber.toString())
        if (input.episodeNumber != null) addQueryParameter("episodeNumber", input.episodeNumber.toString())
    }
    .build()

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
