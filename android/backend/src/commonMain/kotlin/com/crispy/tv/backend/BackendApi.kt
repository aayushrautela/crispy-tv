package com.crispy.tv.backend

import com.crispy.tv.ai.AiInsightsResult

/**
 * The Crispy backend, as `commonMain` code sees it.
 *
 * ## Why this exists
 *
 * Every type this interface exchanges is pure data and already lives in
 * `commonMain` ([BackendTypes], [BackendPayloads], `AiInsightsResult`). This
 * interface was introduced because the one thing that was *not* portable was
 * the implementation: `CrispyBackendClient` spoke OkHttp and `org.json` and was
 * therefore `androidMain`.
 *
 * **Both of those reasons are gone, so this KDoc's original justification no
 * longer describes anything.** OkHttp went when the transport became the
 * [com.crispy.tv.network.CrispyHttpClient] port and `org.json` went when the
 * client started answering `JsonObject`; `CrispyBackendClient`, its 39 receiver
 * extensions and every JSON accessor are in `commonMain` now. The interface
 * outlived its reason and earns its place differently: **it is the seam a
 * `commonTest` can stand in for.** A test can construct an interface and cannot
 * construct a class that needs an HTTP transport, so `ProfileDataCloudSync`
 * names this type rather than the client, which is what made that file's own
 * suite writable at all. A `commonMain` file that names the concrete client is a
 * file nothing can test.
 *
 * `CrispyBackendClient` implements this with its body unchanged, so introducing
 * it was a type-level port, not a transport port, and no request or response
 * handling moved.
 *
 * ## What this is not
 *
 * The transport is [com.crispy.tv.network.CrispyHttpClient], and it is the real
 * port: `HttpRequest` describes a request and the implementation owns building
 * it. **This KDoc previously justified *not* abstracting the transport here by
 * claiming `CrispyHttpClient` "leaks `okhttp3.HttpUrl`, `Headers` and `Request`
 * in its own signature", and that was false** -- it leaked nothing, and
 * `git grep -l 'import okhttp3' | grep '/src/commonMain/'` returns zero hits
 * repo-wide. The same invented reason was load-bearing in
 * [com.crispy.tv.accounts.AccountApi] and in
 * `com.crispy.tv.addons.streams.StreamResolver`, so one premise needed
 * correcting in three files. What remains true and worth keeping: no `okhttp3`
 * or `org.json` type reaches this interface, because `CrispyBackendClient` names
 * neither.
 *
 * ## Errors
 *
 * Implementations are expected to throw [CrispyBackendException] on a non-2xx
 * response. It is declared here in `commonMain`, so `commonMain` callers can
 * catch it without naming a transport.
 */
interface BackendApi {

    /** The configured backend base URL, normalised (trimmed, no trailing slash). */
    val baseUrl: String

    /** False when no base URL was configured; every call would fail. */
    fun isConfigured(): Boolean

    // --- account -----------------------------------------------------------

    suspend fun getMe(accessToken: String): MeResponse

    suspend fun createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int? = null,
        isKids: Boolean = false,
        avatarKey: String? = null,
        interfaceLanguage: String? = null,
    ): Profile

    suspend fun bootstrapAccount(
        accessToken: String,
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String? = null,
    ): Profile

    suspend fun listImportConnections(accessToken: String, profileId: String): ProviderAccountsResponse

    suspend fun listImportJobs(accessToken: String, profileId: String): ImportJobsResponse

    suspend fun disconnectImportConnection(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
    ): ProviderState

    suspend fun startImport(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
        action: String,
        clientId: String,
        returnTo: String,
    ): StartImportResult

    suspend fun getProfileSettings(accessToken: String, profileId: String): ProfileSettings

    suspend fun patchProfileSettings(
        accessToken: String,
        profileId: String,
        settings: Map<String, String>,
    ): ProfileSettings

    suspend fun listProfiles(accessToken: String): List<Profile>

    suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        input: UpdateProfileInput,
    ): Profile

    suspend fun getAccountSettings(accessToken: String): AccountSettings

    suspend fun patchAccountSettings(
        accessToken: String,
        settings: Map<String, String>,
    ): AccountSettings

    suspend fun deleteAccount(accessToken: String): Boolean

    // --- addons ------------------------------------------------------------

    suspend fun listAddons(accessToken: String): List<AddonDto>

    suspend fun installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String = "stremio",
        payload: Map<String, String> = emptyMap(),
    ): AddonDto

    suspend fun uninstallAddon(accessToken: String, profileId: String, addonId: String): Boolean

    suspend fun getAvatars(): List<Avatar>

    // --- search ------------------------------------------------------------

    suspend fun searchTitles(
        accessToken: String,
        query: String,
        limit: Int = 20,
    ): SearchResultsResponse

    suspend fun searchSuggestions(
        accessToken: String,
        query: String,
        limit: Int = 8,
    ): SearchSuggestionsResponse

    suspend fun searchTitlesByGenre(
        accessToken: String,
        genre: String,
        limit: Int = 20,
    ): SearchResultsResponse

    suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        locale: String? = null,
    ): SearchResultsResponse

    suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        itemId: String,
        locale: String? = null,
    ): AiInsightsResult

    // --- metadata ----------------------------------------------------------

    suspend fun getMetadataItemDetail(accessToken: String, itemId: String): MetadataTitleDetailResponse

    suspend fun getMetadataItemExtras(accessToken: String, itemId: String): MetadataTitleExtrasResponse

    suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int? = null,
    ): MetadataSeriesEpisodesResponse

    suspend fun getMetadataItemRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): MetadataTitleRatingsResponse

    suspend fun getMetadataPersonDetail(
        accessToken: String,
        personId: String,
        language: String? = null,
    ): MetadataPersonDetail

    suspend fun browseTitles(
        accessToken: String,
        type: String,
        genre: String? = null,
        sort: String = "popularity",
        page: Int = 0,
    ): BrowseTitlesResponse

    // --- home / calendar ---------------------------------------------------

    suspend fun getHome(
        accessToken: String,
        profileId: String,
    ): ProfileHomeResponse?

    suspend fun getCalendar(accessToken: String, profileId: String): CalendarResponse

    suspend fun getCalendarThisWeek(accessToken: String, profileId: String): CalendarResponse

    suspend fun getUpNext(accessToken: String, profileId: String, limit: Int = 20): UpNextResponse

    // --- playback ----------------------------------------------------------

    suspend fun resolvePlayback(accessToken: String, input: ItemLookupInput): PlaybackResolveResponse

    suspend fun sendWatchEvent(
        accessToken: String,
        profileId: String,
        input: PlaybackEventInput,
    ): WatchActionResponse

    // --- watch state -------------------------------------------------------

    suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int = 20,
        cursor: String? = null,
    ): ClientMediaCardQueryResult

    suspend fun dismissContinueWatching(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): WatchActionResponse

    suspend fun listWatchHistory(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult

    suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult

    suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): ClientMediaCardQueryResult

    suspend fun getWatchGenerations(
        accessToken: String,
        profileId: String,
    ): WatchGenerationsResponse

    suspend fun getWatchState(accessToken: String, profileId: String, itemId: String): WatchStateEnvelope

    suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): WatchStatesEnvelope

    /**
     * [getWatchStates] keyed by item id, and empty for an empty request.
     *
     * The short-circuit is part of the contract rather than an optimisation:
     * a caller resolving a list of episodes should not spend a request to learn
     * that there were none.
     */
    suspend fun getWatchStateMap(
        accessToken: String,
        profileId: String,
        itemIds: List<String>,
    ): Map<String, WatchStateResponse>

    suspend fun markWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput,
    ): WatchActionResponse

    suspend fun unmarkWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput,
    ): WatchActionResponse

    // --- watchlist / ratings -----------------------------------------------

    suspend fun putWatchlist(
        accessToken: String,
        profileId: String,
        itemId: String,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse

    suspend fun deleteWatchlist(accessToken: String, profileId: String, itemId: String): WatchActionResponse

    suspend fun setLiked(
        accessToken: String,
        profileId: String,
        itemId: String,
        liked: Boolean,
        occurredAt: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): WatchActionResponse

    suspend fun deleteRating(accessToken: String, profileId: String, itemId: String): WatchActionResponse
}
