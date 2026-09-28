package com.crispy.tv.backend

/**
 * The backend's response and request types.
 *
 * ## Why these are top-level in `commonMain` rather than nested in the client
 *
 * They are pure data: no `Context`, no OkHttp, no `org.json`, no I/O. The only
 * non-kotlin reference in the block is `IllegalStateException`. They were nested
 * inside `CrispyBackendClient` only because that is where they were first written,
 * and nesting them there made the client -- which is `androidMain`, because it
 * speaks OkHttp and `org.json` -- the sole owner of the vocabulary every other
 * module speaks.
 *
 * 38 files across 8 modules reference these types. Before the move, every one of
 * them was pinned to `androidMain` because of where a data class happened to be
 * declared. That is what kept `:android:home`'s `ResponsiveImageSet`,
 * `:android:addons`' `MediaDetailMappings`, `:android:backend`'s
 * `AiInsightsModels` and `:app`'s 1,423-line `ui/components` library out of
 * `commonMain`.
 *
 * The client and its parsers stay in `androidMain`. Only the vocabulary moves.
 */
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

data class AccountSettings(
    val pricingTier: String?,
    val hasMdbListAccess: Boolean,
)

data class AddonDto(
    val id: String,
    val manifestUrl: String,
    val createdAt: String,
    val type: String = "stremio",
    val payload: Map<String, String> = emptyMap(),
)

data class Avatar(
    val id: String,
    val url: String,
)

data class UpdateProfileInput(
    val name: String? = null,
    val isKids: Boolean? = null,
    val avatarKey: String? = null,
    val sortOrder: Int? = null,
)

data class MediaExternalIds(
    val tmdb: Int?,
    val imdb: String?,
    val tvdb: Int?,
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
    val personId: String,
    val name: String,
    val knownForDepartment: String?,
    val profileUrl: String?,
    val knownForTitles: List<String>,
)

data class SearchResultsResponse(
    val query: String,
    val movies: List<ClientMediaCard>,
    val series: List<ClientMediaCard>,
    val people: List<PersonSearchResultItem>,
)

/**
 * A suggestion is a keyword name, not a resolvable item. The server serves
 * these from a curated table, so typing never spends search quota, and the
 * client turns a suggestion into media only by running a real search.
 */
data class SearchSuggestionsResponse(
    val suggestions: List<String>,
)

// --- Home ---

data class ClientProgress(
    val played: Boolean,
    val playCount: Int,
    val positionSeconds: Int?,
    val durationSeconds: Int?,
    val percent: Double?,
    val lastPlayedAt: String?,
    val watchlisted: Boolean,
    val liked: Boolean?,
)

data class ClientParentImages(
    val artwork: ResponsiveImageSet,
)

data class ClientParentRef(
    val seriesItemId: String?,
    val seriesTitle: String?,
    val seasonItemId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val images: ClientParentImages? = null,
)

data class ClientImages(
    val artwork: ResponsiveImageSet,
    val logo: ResponsiveImageSet,
    val still: ResponsiveImageSet,
)

data class ClientMediaCard(
    val itemId: String,
    val mediaType: String,
    val title: String,
    val overview: String?,
    val tagline: String? = null,
    val year: Int?,
    val releaseDate: String?,
    val rating: Double?,
    val maturityRating: String?,
    val genres: List<String>,
    val runtimeSeconds: Int?,
    val images: ClientImages,
    val trailerUrl: String? = null,
    val progress: ClientProgress?,
    val parent: ClientParentRef?,
    val providerIds: MediaExternalIds? = null,
)

data class ProfileHomeSection(
    val listKey: String,
    val title: String,
    val subtitle: String?,
    val layout: String,
    val items: List<ClientMediaCard>,
    val meta: Map<String, String>,
)

data class ProfileHomeResponse(
    val profileId: String,
    val generatedAt: String?,
    val expiresAt: String?,
    val sections: List<ProfileHomeSection>,
)

// --- Calendar ---

data class CalendarItem(
    val card: ClientMediaCard,
    val airDate: String?,
    val bucket: String?,
)

data class CalendarResponse(
    val profileId: String,
    val source: String,
    val kind: String?,
    val generatedAt: String?,
    val items: List<CalendarItem>,
)

data class UpNextItem(
    val show: ClientMediaCard?,
    val nextEpisode: ClientMediaCard?,
    val nextEpisodeAirDate: String?,
    val lastInteractedAt: String?,
    val reason: String?,
)

data class UpNextResponse(
    val profileId: String,
    val source: String?,
    val kind: String?,
    val generatedAt: String?,
    val items: List<UpNextItem>,
)

// --- Browse ---

data class BrowseTitlesResponse(
    val items: List<ClientMediaCard>,
    val total: Int?,
    val hasMore: Boolean,
)

// --- Watch State ---

data class WatchedStateView(
    val watchedAt: String,
)

data class WatchStateResponse(
    val itemId: String,
    val played: Boolean,
    val watched: WatchedStateView?,
    val playCount: Int,
    val liked: Boolean? = null,
    val resumePositionSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val progressPercent: Double? = null,
    val lastPlayedAt: String? = null,
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

data class WatchGenerationsResponse(
    val continueWatchingMs: Long?,
    val historyMs: Long?,
    val watchlistMs: Long?,
    val ratingsMs: Long?,
    val homeMs: Long?,
)

// --- Paged watch collections (ClientMediaCardQueryResult) ---

data class ClientMediaCardQueryResult(
    val items: List<ClientMediaCard>,
    val startIndex: Int,
    val totalRecordCount: Int,
    val nextCursor: String?,
    val hasMore: Boolean,
)

// --- Metadata / Playback (Jellyfin Item-based, unchanged) ---

data class MetadataTitleDetailResponse(
    val item: ClientMediaCard,
    val nextEpisode: ClientMediaCard?,
    val videos: List<MetadataVideoView>,
    val cast: List<MetadataPersonRefView>,
    val directors: List<MetadataPersonRefView>,
    val creators: List<MetadataPersonRefView>,
    val production: MetadataProductionInfoView,
)

data class MetadataExtrasList(
    val key: String,
    val title: String,
    val items: List<ClientMediaCard>,
)

data class MetadataTitleExtrasResponse(
    val seasons: List<ClientMediaCard>,
    val reviews: List<MetadataReviewView>,
    val lists: List<MetadataExtrasList>,
)

data class MetadataSeriesEpisodesResponse(
    val items: List<ClientMediaCard>,
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
    val personId: String,
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
    val item: ClientMediaCard,
    val show: ClientMediaCard?,
    val season: ClientMediaCard?,
)

data class MetadataPersonDetail(
    val personId: String,
    val name: String,
    val knownForDepartment: String?,
    val biography: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val profileUrl: String?,
    val socials: PersonSocials,
    val knownFor: List<ClientMediaCard>,
)

data class PersonSocials(
    val imdbId: String?,
    val instagram: String?,
    val twitter: String?,
    val facebook: String?,
    val tiktok: String?,
    val youtube: String?,
)

// --- Watch Actions ---

data class WatchActionResponse(
    val accepted: Boolean,
    val mode: String,
    val reason: String? = null,
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
