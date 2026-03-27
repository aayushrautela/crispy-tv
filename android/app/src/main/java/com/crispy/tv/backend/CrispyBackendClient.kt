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
        val accountSettings: AccountSettings,
        val profiles: List<Profile>,
    )

    data class AccountSettings(
        val settings: Map<String, String>,
        val hasOpenRouterKey: Boolean,
        val hasOmdbApiKey: Boolean,
    )

    data class AccountSecret(
        val key: String,
        val value: String,
    )

    data class ProfileSettings(
        val settings: Map<String, String>,
    )

    enum class ImportProvider(val apiValue: String) {
        TRAKT("trakt"),
        SIMKL("simkl"),
    }

    data class ImportConnection(
        val id: String,
        val provider: String,
        val status: String,
        val providerUserId: String?,
        val externalUsername: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val lastUsedAt: String?,
        val lastImportJobId: String?,
        val lastImportCompletedAt: String?,
    )

    data class ImportJob(
        val id: String,
        val profileId: String,
        val provider: String,
        val mode: String,
        val status: String,
        val requestedByUserId: String,
        val connectionId: String?,
        val errorMessage: String?,
        val createdAt: String?,
        val startedAt: String?,
        val finishedAt: String?,
        val updatedAt: String?,
    )

    data class ImportConnectionsResponse(
        val connections: List<ImportConnection>,
        val watchDataOrigin: String?,
    )

    data class BackendMetadataItem(
        val id: String,
        val title: String,
        val summary: String?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val mediaType: String,
        val rating: String?,
        val year: String?,
        val genre: String?,
    )

    data class MetadataSearchResponse(
        val items: List<BackendMetadataItem>,
    )

    data class AiSearchResponse(
        val items: List<BackendMetadataItem>,
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
        val watchDataOrigin: String?,
    )

    data class StartImportResult(
        val job: ImportJob,
        val connection: ImportConnection?,
        val authUrl: String?,
        val nextAction: String,
        val watchDataOrigin: String?,
    )

    enum class LibrarySource(val apiValue: String) {
        LOCAL("local"),
        TRAKT("trakt"),
        SIMKL("simkl"),
        ALL("all"),
    }

    enum class LibraryMutationSource(val apiValue: String) {
        TRAKT("trakt"),
        SIMKL("simkl"),
        ALL("all"),
    }

    data class MediaLookupInput(
        val id: String? = null,
        val mediaKey: String? = null,
        val mediaType: String? = null,
        val tmdbId: Int? = null,
        val showTmdbId: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    )

    data class WatchMutationInput(
        val mediaKey: String? = null,
        val mediaType: String,
        val tmdbId: Int? = null,
        val showTmdbId: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val occurredAt: String? = null,
        val rating: Int? = null,
        val payload: Map<String, Any?> = emptyMap(),
    )

    data class PlaybackEventInput(
        val clientEventId: String,
        val eventType: String,
        val mediaKey: String? = null,
        val mediaType: String,
        val tmdbId: Int? = null,
        val showTmdbId: Int? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val positionSeconds: Double? = null,
        val durationSeconds: Double? = null,
        val rating: Int? = null,
        val occurredAt: String? = null,
        val payload: Map<String, Any?> = emptyMap(),
    )

    data class MetadataImages(
        val posterUrl: String?,
        val backdropUrl: String?,
        val stillUrl: String?,
        val logoUrl: String?,
    )

    data class MetadataExternalIds(
        val tmdb: Int?,
        val imdb: String?,
        val tvdb: Int?,
    )

    data class MetadataEpisodePreview(
        val id: String,
        val mediaType: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val images: MetadataImages,
    )

    data class MetadataView(
        val id: String,
        val mediaKey: String,
        val mediaType: String,
        val kind: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
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
    )

    data class MetadataSeasonView(
        val id: String,
        val showId: String,
        val showTmdbId: Int?,
        val seasonNumber: Int,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val episodeCount: Int?,
        val posterUrl: String?,
    )

    data class MetadataEpisodeView(
        val id: String,
        val mediaType: String,
        val tmdbId: Int?,
        val showTmdbId: Int?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val title: String?,
        val summary: String?,
        val airDate: String?,
        val runtimeMinutes: Int?,
        val rating: Double?,
        val images: MetadataImages,
        val showId: String?,
        val showTitle: String?,
        val showExternalIds: MetadataExternalIds,
    )

    data class MetadataResolveResponse(
        val item: MetadataView,
    )

    data class MetadataTitleDetailResponse(
        val item: MetadataView,
        val seasons: List<MetadataSeasonView>,
        val videos: List<MetadataVideoView>,
        val cast: List<MetadataPersonRefView>,
        val directors: List<MetadataPersonRefView>,
        val creators: List<MetadataPersonRefView>,
        val reviews: List<MetadataReviewView>,
        val production: MetadataProductionInfoView,
        val collection: MetadataCollectionView?,
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
        val tmdbPersonId: Int,
        val name: String,
        val role: String?,
        val department: String?,
        val profileUrl: String?,
    )

    data class MetadataReviewView(
        val id: String,
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
        val id: Int,
        val name: String,
        val logoUrl: String?,
        val originCountry: String?,
    )

    data class MetadataCollectionView(
        val id: Int,
        val name: String,
        val posterUrl: String?,
        val backdropUrl: String?,
    )

    data class MetadataProductionInfoView(
        val originalLanguage: String?,
        val originCountries: List<String>,
        val spokenLanguages: List<String>,
        val productionCountries: List<String>,
        val companies: List<MetadataCompanyView>,
        val networks: List<MetadataCompanyView>,
    )

    data class MetadataSeasonDetailResponse(
        val show: MetadataView,
        val season: MetadataSeasonView,
        val episodes: List<MetadataEpisodeView>,
    )

    data class MetadataEpisodeListResponse(
        val show: MetadataView,
        val requestedSeasonNumber: Int?,
        val effectiveSeasonNumber: Int,
        val includedSeasonNumbers: List<Int>,
        val episodes: List<MetadataEpisodeView>,
    )

    data class MetadataNextEpisodeResponse(
        val show: MetadataView,
        val currentSeasonNumber: Int,
        val currentEpisodeNumber: Int,
        val item: MetadataEpisodeView?,
    )

    data class PlaybackResolveResponse(
        val item: MetadataView,
        val show: MetadataView?,
        val season: MetadataSeasonView?,
    )

    data class MetadataPersonKnownForItem(
        val id: String,
        val mediaType: String,
        val tmdbId: Int?,
        val title: String,
        val posterUrl: String?,
        val rating: Double?,
        val releaseYear: Int?,
    )

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

    data class ProviderAuthState(
        val provider: String,
        val connected: Boolean,
        val status: String,
        val tokenState: String?,
        val externalUsername: String?,
        val lastImportCompletedAt: String?,
        val lastUsedAt: String?,
        val message: String?,
    )

    data class WatchProgressView(
        val positionSeconds: Double?,
        val durationSeconds: Double?,
        val progressPercent: Double,
        val status: String?,
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

    data class HydratedWatchItem(
        val id: String?,
        val media: MetadataView,
        val progress: WatchProgressView?,
        val watchedAt: String?,
        val lastActivityAt: String?,
        val payload: Map<String, Any?>,
    )

    data class HydratedWatchlistItem(
        val media: MetadataView,
        val addedAt: String,
        val payload: Map<String, Any?>,
    )

    data class HydratedRatingItem(
        val media: MetadataView,
        val rating: RatingStateView,
        val payload: Map<String, Any?>,
    )

    data class CanonicalWatchCollectionResponse<T>(
        val profileId: String,
        val kind: String,
        val source: String,
        val generatedAt: String?,
        val items: List<T>,
    )

    data class WatchStateResponse(
        val media: MetadataView,
        val progress: WatchProgressView?,
        val continueWatching: ContinueWatchingStateView?,
        val watched: WatchedStateView?,
        val watchlist: WatchlistStateView?,
        val rating: RatingStateView?,
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

    data class CalendarItem(
        val bucket: String,
        val media: MetadataView,
        val relatedShow: MetadataView,
        val airDate: String?,
        val watched: Boolean,
    )

    data class CalendarResponse(
        val profileId: String,
        val source: String,
        val generatedAt: String?,
        val items: List<CalendarItem>,
    )

    data class HomeRecommendationItem(
        val media: MetadataView,
        val reason: String?,
        val score: Double?,
        val rank: Int?,
        val payload: Map<String, Any?>,
    )

    sealed interface HomeSection {
        val id: String
        val title: String
        val kind: String
        val source: String
    }

    data class HomeWatchSection(
        override val id: String,
        override val title: String,
        override val kind: String = "watch",
        override val source: String = "canonical_watch",
        val items: List<HydratedWatchItem> = emptyList(),
    ) : HomeSection

    data class HomeCalendarSection(
        override val id: String,
        override val title: String,
        override val kind: String = "calendar",
        override val source: String = "canonical_calendar",
        val items: List<CalendarItem> = emptyList(),
    ) : HomeSection

    data class HomeRecommendationSection(
        override val id: String,
        override val title: String,
        override val kind: String = "recommendation",
        override val source: String = "recommendation",
        val items: List<HomeRecommendationItem> = emptyList(),
        val meta: Map<String, Any?> = emptyMap(),
    ) : HomeSection

    data class HomeResponse(
        val profileId: String,
        val source: String,
        val generatedAt: String?,
        val sections: List<HomeSection>,
    )

    data class NativeLibrary(
        val continueWatching: List<HydratedWatchItem>,
        val history: List<HydratedWatchItem>,
        val watchlist: List<HydratedWatchlistItem>,
        val ratings: List<HydratedRatingItem>,
    )

    data class ProviderLibraryFolder(
        val id: String,
        val label: String,
        val provider: String,
        val itemCount: Int,
    )

    data class ProviderLibraryItem(
        val provider: String,
        val folderId: String,
        val contentId: String,
        val contentType: String,
        val title: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val externalIds: MetadataExternalIds?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val addedAt: String?,
        val media: MetadataView?,
    )

    data class ProviderLibrarySnapshot(
        val provider: String,
        val status: String,
        val statusMessage: String,
        val folders: List<ProviderLibraryFolder>,
        val items: List<ProviderLibraryItem>,
    )

    data class LibraryAuth(
        val providers: List<ProviderAuthState>,
    )

    data class CanonicalLibraryItem(
        val key: String,
        val mediaKey: String?,
        val contentId: String,
        val contentType: String,
        val externalIds: MetadataExternalIds?,
        val title: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val addedAt: String,
        val providers: List<String>,
        val folderIds: List<String>,
        val media: MetadataView?,
    )

    data class CanonicalLibrary(
        val source: String,
        val generatedAt: String?,
        val continueWatching: List<HydratedWatchItem>,
        val history: List<HydratedWatchItem>,
        val watchlist: List<HydratedWatchlistItem>,
        val ratings: List<HydratedRatingItem>,
        val items: List<CanonicalLibraryItem>,
    )

    data class LibraryDiagnostics(
        val source: String,
        val generatedAt: String?,
        val providers: List<ProviderLibrarySnapshot>,
    )

    data class ProfileLibraryResponse(
        val profileId: String,
        val source: String,
        val generatedAt: String?,
        val auth: LibraryAuth,
        val canonical: CanonicalLibrary,
        val native: NativeLibrary?,
        val diagnostics: LibraryDiagnostics,
    )

    data class ProviderMutationResult(
        val provider: String,
        val status: String,
        val message: String?,
    )

    data class LibraryMutationResponse(
        val source: String,
        val action: String,
        val media: MetadataView,
        val watchlist: Boolean?,
        val rating: Int?,
        val results: List<ProviderMutationResult>,
        val statusMessage: String,
    )

    data class WatchActionResponse(
        val accepted: Boolean,
        val mode: String,
    )

    fun isConfigured(): Boolean {
        return baseUrl.isNotBlank()
    }

    suspend fun getMe(accessToken: String): MeResponse {
        return getMeApi(accessToken)
    }

    suspend fun getAccountSettings(accessToken: String): AccountSettings {
        return getAccountSettingsApi(accessToken)
    }

    suspend fun patchAccountSettings(accessToken: String, settings: Map<String, String>): AccountSettings {
        return patchAccountSettingsApi(accessToken, settings)
    }

    suspend fun getOpenRouterSecret(accessToken: String): AccountSecret? {
        return getOpenRouterSecretApi(accessToken)
    }

    suspend fun putOpenRouterSecret(accessToken: String, value: String): AccountSecret {
        return putOpenRouterSecretApi(accessToken, value)
    }

    suspend fun deleteOpenRouterSecret(accessToken: String): Boolean {
        return deleteOpenRouterSecretApi(accessToken)
    }

    suspend fun getOmdbApiSecret(accessToken: String): AccountSecret? {
        return getOmdbApiSecretApi(accessToken)
    }

    suspend fun putOmdbApiSecret(accessToken: String, value: String): AccountSecret {
        return putOmdbApiSecretApi(accessToken, value)
    }

    suspend fun deleteOmdbApiSecret(accessToken: String): Boolean {
        return deleteOmdbApiSecretApi(accessToken)
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

    suspend fun listImportConnections(accessToken: String, profileId: String): ImportConnectionsResponse {
        return listImportConnectionsApi(accessToken, profileId)
    }

    suspend fun listImportJobs(accessToken: String, profileId: String): ImportJobsResponse {
        return listImportJobsApi(accessToken, profileId)
    }

    suspend fun startImport(accessToken: String, profileId: String, provider: ImportProvider): StartImportResult {
        return startImportApi(accessToken, profileId, provider)
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
        filter: String? = null,
        locale: String? = null,
        limit: Int = 20,
    ): MetadataSearchResponse {
        return searchTitlesApi(
            accessToken = accessToken,
            query = query,
            filter = filter,
            locale = locale,
            limit = limit,
        )
    }

    suspend fun searchTitlesByGenre(
        accessToken: String,
        genre: String,
        filter: String? = null,
        locale: String? = null,
        limit: Int = 20,
    ): MetadataSearchResponse {
        return searchTitlesByGenreApi(
            accessToken = accessToken,
            genre = genre,
            filter = filter,
            locale = locale,
            limit = limit,
        )
    }

    suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        filter: String? = null,
        locale: String? = null,
    ): AiSearchResponse {
        return searchAiTitlesApi(
            accessToken = accessToken,
            profileId = profileId,
            query = query,
            filter = filter,
            locale = locale,
        )
    }

    suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        tmdbId: Int,
        mediaType: String,
        locale: String? = null,
    ): AiInsightsResponse {
        return getAiInsightsApi(
            accessToken = accessToken,
            profileId = profileId,
            tmdbId = tmdbId,
            mediaType = mediaType,
            locale = locale,
        )
    }

    suspend fun disconnectImportConnection(accessToken: String, profileId: String, provider: ImportProvider): ImportConnection {
        return disconnectImportConnectionApi(accessToken, profileId, provider)
    }

    suspend fun resolveMetadata(accessToken: String, input: MediaLookupInput): MetadataResolveResponse {
        return resolveMetadataApi(accessToken, input)
    }

    suspend fun getMetadataTitleDetail(accessToken: String, id: String): MetadataTitleDetailResponse {
        return getMetadataTitleDetailApi(accessToken, id)
    }

    suspend fun getMetadataSeasonDetail(accessToken: String, id: String, seasonNumber: Int): MetadataSeasonDetailResponse {
        return getMetadataSeasonDetailApi(accessToken, id, seasonNumber)
    }

    suspend fun getMetadataPersonDetail(accessToken: String, id: String, language: String? = null): MetadataPersonDetail {
        return getMetadataPersonDetailApi(accessToken, id, language)
    }

    suspend fun listMetadataEpisodes(accessToken: String, id: String, seasonNumber: Int? = null): MetadataEpisodeListResponse {
        return listMetadataEpisodesApi(accessToken, id, seasonNumber)
    }

    suspend fun getNextEpisode(
        accessToken: String,
        id: String,
        currentSeasonNumber: Int,
        currentEpisodeNumber: Int,
        watchedKeys: List<String> = emptyList(),
        showId: String? = null,
        nowMs: Long? = null,
    ): MetadataNextEpisodeResponse {
        return getNextEpisodeApi(
            accessToken = accessToken,
            id = id,
            currentSeasonNumber = currentSeasonNumber,
            currentEpisodeNumber = currentEpisodeNumber,
            watchedKeys = watchedKeys,
            showId = showId,
            nowMs = nowMs,
        )
    }

    suspend fun resolvePlayback(accessToken: String, input: MediaLookupInput): PlaybackResolveResponse {
        return resolvePlaybackApi(accessToken, input)
    }

    suspend fun getHome(accessToken: String, profileId: String): HomeResponse {
        return getHomeApi(accessToken, profileId)
    }

    suspend fun getCalendar(accessToken: String, profileId: String): CalendarResponse {
        return getCalendarApi(accessToken, profileId)
    }

    suspend fun getProviderAuthState(accessToken: String, profileId: String): List<ProviderAuthState> {
        return getProviderAuthStateApi(accessToken, profileId)
    }

    suspend fun getProfileLibrary(
        accessToken: String,
        profileId: String,
        source: LibrarySource? = null,
        limitPerFolder: Int? = null,
    ): ProfileLibraryResponse {
        return getProfileLibraryApi(
            accessToken = accessToken,
            profileId = profileId,
            source = source,
            limitPerFolder = limitPerFolder,
        )
    }

    suspend fun setProviderWatchlist(
        accessToken: String,
        profileId: String,
        input: MediaLookupInput,
        inWatchlist: Boolean,
        source: LibraryMutationSource? = null,
    ): LibraryMutationResponse {
        return setProviderWatchlistApi(
            accessToken = accessToken,
            profileId = profileId,
            input = input,
            inWatchlist = inWatchlist,
            source = source,
        )
    }

    suspend fun setProviderRating(
        accessToken: String,
        profileId: String,
        input: MediaLookupInput,
        rating: Int?,
        source: LibraryMutationSource? = null,
    ): LibraryMutationResponse {
        return setProviderRatingApi(
            accessToken = accessToken,
            profileId = profileId,
            input = input,
            rating = rating,
            source = source,
        )
    }

    suspend fun sendWatchEvent(accessToken: String, profileId: String, input: PlaybackEventInput): WatchActionResponse {
        return sendWatchEventApi(accessToken, profileId, input)
    }

    suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int = 20,
    ): CanonicalWatchCollectionResponse<HydratedWatchItem> {
        return listContinueWatchingApi(accessToken, profileId, limit)
    }

    suspend fun dismissContinueWatching(accessToken: String, profileId: String, itemId: String): WatchActionResponse {
        return dismissContinueWatchingApi(accessToken, profileId, itemId)
    }

    suspend fun listWatchHistory(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
    ): CanonicalWatchCollectionResponse<HydratedWatchItem> {
        return listWatchHistoryApi(accessToken, profileId, limit)
    }

    suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
    ): CanonicalWatchCollectionResponse<HydratedWatchlistItem> {
        return listWatchlistApi(accessToken, profileId, limit)
    }

    suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
    ): CanonicalWatchCollectionResponse<HydratedRatingItem> {
        return listRatingsApi(accessToken, profileId, limit)
    }

    suspend fun getWatchState(accessToken: String, profileId: String, input: MediaLookupInput): WatchStateEnvelope {
        return getWatchStateApi(accessToken, profileId, input)
    }

    suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        items: List<MediaLookupInput>,
    ): WatchStatesEnvelope {
        return getWatchStatesApi(accessToken, profileId, items)
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

    internal fun requireSuccess(response: CrispyHttpResponse): String {
        if (response.code in 200..299) {
            return response.body
        }
        throw IllegalStateException(extractErrorMessage(response.body) ?: "HTTP ${response.code}")
    }

    internal fun extractErrorMessage(rawBody: String): String? {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        return json?.let {
            listOf("message", "error", "error_description")
                .firstNotNullOfOrNull { key -> it.optString(key).trim().takeIf(String::isNotBlank) }
        } ?: trimmed
    }

    internal val callTimeoutMs: Long
        get() = CALL_TIMEOUT_MS

    internal val jsonMediaType
        get() = JSON_MEDIA_TYPE

    internal fun metadataLookupUrl(
        path: String,
        input: MediaLookupInput,
        includeId: Boolean = true,
        includeMediaKey: Boolean = true,
        includeShowTmdbId: Boolean = true,
        includeImdbId: Boolean = true,
        includeTvdbId: Boolean = true,
    ) = path.toHttpUrl().newBuilder()
        .apply {
            if (includeId && !input.id.isNullOrBlank()) addQueryParameter("id", input.id.trim())
            if (includeMediaKey && !input.mediaKey.isNullOrBlank()) addQueryParameter("mediaKey", input.mediaKey.trim())
            if (!input.mediaType.isNullOrBlank()) addQueryParameter("mediaType", input.mediaType.trim())
            if (input.tmdbId != null) addQueryParameter("tmdbId", input.tmdbId.toString())
            if (includeShowTmdbId && input.showTmdbId != null) addQueryParameter("showTmdbId", input.showTmdbId.toString())
            if (includeImdbId && !input.imdbId.isNullOrBlank()) addQueryParameter("imdbId", input.imdbId.trim())
            if (includeTvdbId && input.tvdbId != null) addQueryParameter("tvdbId", input.tvdbId.toString())
            if (input.seasonNumber != null) addQueryParameter("seasonNumber", input.seasonNumber.toString())
            if (input.episodeNumber != null) addQueryParameter("episodeNumber", input.episodeNumber.toString())
        }
        .build()

    private companion object {
        private const val CALL_TIMEOUT_MS = 45_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
