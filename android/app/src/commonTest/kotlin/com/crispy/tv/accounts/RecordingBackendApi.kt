package com.crispy.tv.accounts

import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.BrowseTitlesResponse
import com.crispy.tv.backend.ImportJob
import com.crispy.tv.backend.ImportProvider
import com.crispy.tv.backend.ImportJobsResponse
import com.crispy.tv.backend.ItemLookupInput
import com.crispy.tv.backend.PlaybackEventInput
import com.crispy.tv.backend.WatchMutationInput
import com.crispy.tv.backend.ProviderAccountsResponse
import com.crispy.tv.backend.ProviderState
import com.crispy.tv.backend.StartImportResult
import com.crispy.tv.backend.UpdateProfileInput

/**
 * A [BackendApi] that records what it was asked and throws for everything else.
 *
 * Exhaustive on purpose, and the compiler is the point: adding a member to
 * [BackendApi] fails to compile here until a decision is made about what that
 * member means. A partial double would let a new member arrive unexamined.
 *
 * The members below are the ones `SyncProviderRepository` and
 * `BackendBrowseRepository` call, and they answer. The rest throw
 * `AssertionError` naming the member, which is louder than a silent default and
 * says immediately which call a test forgot to stub. Widening this file is the
 * intended cost of adding a consumer: the alternative is a narrow double in
 * the same module, which silently rots the moment a member changes.
 */
internal class RecordingBackendApi : BackendApi {
    val listImportConnectionsCalls = mutableListOf<Pair<String, String>>()
    val disconnectCalls = mutableListOf<Triple<String, String, ImportProvider>>()
    val startImportCalls = mutableListOf<StartImportCall>()

    var providerStates: List<ProviderState> = emptyList()
    var startImportResult: StartImportResult? = null

    fun withProviderStates(vararg states: ProviderState) = apply {
        providerStates = states.toList()
    }

    data class StartImportCall(
        val accessToken: String,
        val profileId: String,
        val provider: ImportProvider,
        val action: String,
        val clientId: String,
        val returnTo: String,
    )

    override fun isConfigured(): Boolean = true

    override val baseUrl: String get() = "https://api.test"

    override suspend fun listImportConnections(
        accessToken: String,
        profileId: String,
    ): ProviderAccountsResponse {
        listImportConnectionsCalls += accessToken to profileId
        return ProviderAccountsResponse(providerStates)
    }

    override suspend fun disconnectImportConnection(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
    ): ProviderState {
        disconnectCalls += Triple(accessToken, profileId, provider)
        return providerState(provider = provider.apiValue, connectionState = "disconnected")
    }

    override suspend fun startImport(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
        action: String,
        clientId: String,
        returnTo: String,
    ): StartImportResult {
        startImportCalls += StartImportCall(accessToken, profileId, provider, action, clientId, returnTo)
        return startImportResult
            ?: throw AssertionError("startImport was not stubbed with a result for this test")
    }


    // The rest of BackendApi, in the interface's own order. Throwing rather than
    // returning a default is deliberate: a test that reaches one of these has
    // called something it did not mean to, and a silent default would hide that
    // behind an empty result.
    override suspend fun getMe(accessToken: String): Nothing = unused("getMe")
    override suspend fun createProfile(
        accessToken: String,
        name: String,
        sortOrder: Int?,
        isKids: Boolean,
        avatarKey: String?,
        interfaceLanguage: String?
    ): Nothing = unused("createProfile")
    override suspend fun bootstrapAccount(
        accessToken: String,
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String?
    ): Nothing = unused("bootstrapAccount")
    override suspend fun listImportJobs(accessToken: String, profileId: String): Nothing = unused("listImportJobs")
    override suspend fun getProfileSettings(
        accessToken: String,
        profileId: String
    ): Nothing = unused("getProfileSettings")
    override suspend fun patchProfileSettings(
        accessToken: String,
        profileId: String,
        settings: Map<String, String>
    ): Nothing = unused("patchProfileSettings")
    override suspend fun listProfiles(accessToken: String): Nothing = unused("listProfiles")
    override suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        input: UpdateProfileInput
    ): Nothing = unused("updateProfile")
    override suspend fun getAccountSettings(accessToken: String): Nothing = unused("getAccountSettings")
    override suspend fun patchAccountSettings(
        accessToken: String,
        settings: Map<String, String>
    ): Nothing = unused("patchAccountSettings")
    override suspend fun deleteAccount(accessToken: String): Nothing = unused("deleteAccount")
    override suspend fun listAddons(accessToken: String): Nothing = unused("listAddons")
    override suspend fun installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String,
        payload: Map<String, String>
    ): Nothing = unused("installAddon")
    override suspend fun uninstallAddon(
        accessToken: String,
        profileId: String,
        addonId: String
    ): Nothing = unused("uninstallAddon")
    override suspend fun getAvatars(): Nothing = unused("getAvatars")
    override suspend fun searchTitles(accessToken: String, query: String, limit: Int): Nothing = unused("searchTitles")
    override suspend fun searchSuggestions(
        accessToken: String,
        query: String,
        limit: Int
    ): Nothing = unused("searchSuggestions")
    override suspend fun searchTitlesByGenre(
        accessToken: String,
        genre: String,
        limit: Int
    ): Nothing = unused("searchTitlesByGenre")
    override suspend fun searchAiTitles(
        accessToken: String,
        profileId: String,
        query: String,
        locale: String?
    ): Nothing = unused("searchAiTitles")
    override suspend fun getAiInsights(
        accessToken: String,
        profileId: String,
        itemId: String,
        locale: String?
    ): Nothing = unused("getAiInsights")
    override suspend fun getMetadataItemDetail(
        accessToken: String,
        itemId: String
    ): Nothing = unused("getMetadataItemDetail")
    override suspend fun getMetadataItemExtras(
        accessToken: String,
        itemId: String
    ): Nothing = unused("getMetadataItemExtras")
    override suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int?
    ): Nothing = unused("getSeriesEpisodes")
    override suspend fun getMetadataItemRatings(
        accessToken: String,
        profileId: String,
        itemId: String
    ): Nothing = unused("getMetadataItemRatings")
    override suspend fun getMetadataPersonDetail(
        accessToken: String,
        personId: String,
        language: String?
    ): Nothing = unused("getMetadataPersonDetail")
    var browseTitlesResponses: List<BrowseTitlesResponse> = emptyList()
    val browseTitlesCalls = mutableListOf<BrowseTitlesCall>()

    data class BrowseTitlesCall(
        val accessToken: String,
        val type: String,
        val genre: String?,
        val sort: String,
        val page: Int,
    )

    /** Answers [browseTitles] from a queue, one response per call, in order. */
    fun answerBrowseTitles(vararg responses: BrowseTitlesResponse) = apply {
        browseTitlesResponses = responses.toList()
    }

    override suspend fun browseTitles(
        accessToken: String,
        type: String,
        genre: String?,
        sort: String,
        page: Int
    ): BrowseTitlesResponse {
        browseTitlesCalls += BrowseTitlesCall(accessToken, type, genre, sort, page)
        return browseTitlesResponses.getOrNull(browseTitlesCalls.size - 1)
            ?: error("browseTitles was not stubbed for call ${browseTitlesCalls.size}")
    }
    override suspend fun getHome(accessToken: String, profileId: String): Nothing = unused("getHome")
    override suspend fun getCalendar(accessToken: String, profileId: String): Nothing = unused("getCalendar")
    override suspend fun getCalendarThisWeek(
        accessToken: String,
        profileId: String
    ): Nothing = unused("getCalendarThisWeek")
    override suspend fun getUpNext(accessToken: String, profileId: String, limit: Int): Nothing = unused("getUpNext")
    override suspend fun resolvePlayback(
        accessToken: String,
        input: ItemLookupInput
    ): Nothing = unused("resolvePlayback")
    override suspend fun sendWatchEvent(
        accessToken: String,
        profileId: String,
        input: PlaybackEventInput
    ): Nothing = unused("sendWatchEvent")
    override suspend fun listContinueWatching(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?
    ): Nothing = unused("listContinueWatching")
    override suspend fun dismissContinueWatching(
        accessToken: String,
        profileId: String,
        itemId: String
    ): Nothing = unused("dismissContinueWatching")
    override suspend fun listWatchHistory(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?
    ): Nothing = unused("listWatchHistory")
    override suspend fun listWatchlist(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?
    ): Nothing = unused("listWatchlist")
    override suspend fun listRatings(
        accessToken: String,
        profileId: String,
        limit: Int,
        cursor: String?
    ): Nothing = unused("listRatings")
    override suspend fun getWatchGenerations(
        accessToken: String,
        profileId: String
    ): Nothing = unused("getWatchGenerations")
    override suspend fun getWatchState(
        accessToken: String,
        profileId: String,
        itemId: String
    ): Nothing = unused("getWatchState")
    override suspend fun getWatchStates(
        accessToken: String,
        profileId: String,
        itemIds: List<String>
    ): Nothing = unused("getWatchStates")
    override suspend fun getWatchStateMap(
        accessToken: String,
        profileId: String,
        itemIds: List<String>
    ): Nothing = unused("getWatchStateMap")
    override suspend fun markWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput
    ): Nothing = unused("markWatched")
    override suspend fun unmarkWatched(
        accessToken: String,
        profileId: String,
        input: WatchMutationInput
    ): Nothing = unused("unmarkWatched")
    override suspend fun putWatchlist(
        accessToken: String,
        profileId: String,
        itemId: String,
        occurredAt: String?,
        payload: Map<String, Any?>
    ): Nothing = unused("putWatchlist")
    override suspend fun deleteWatchlist(
        accessToken: String,
        profileId: String,
        itemId: String
    ): Nothing = unused("deleteWatchlist")
    override suspend fun setLiked(
        accessToken: String,
        profileId: String,
        itemId: String,
        liked: Boolean,
        occurredAt: String?,
        payload: Map<String, Any?>
    ): Nothing = unused("setLiked")
    override suspend fun deleteRating(
        accessToken: String,
        profileId: String,
        itemId: String
    ): Nothing = unused("deleteRating")
    private fun unused(name: String): Nothing = throw AssertionError("$name is not stubbed")
}

/** A [ProviderState] with only the two fields `SyncProviderRepository` reads. */
internal fun providerState(
    provider: String,
    connectionState: String,
): ProviderState = ProviderState(
    provider = provider,
    connectionState = connectionState,
    accountStatus = null,
    primaryAction = "",
    canImport = false,
    canReconnect = false,
    canDisconnect = false,
    externalUsername = null,
    statusLabel = "",
    statusMessage = null,
    lastImportCompletedAt = null,
)

/** An [ImportJob] a test can look at; the remaining fields are server-owned. */
internal fun importJob(
    id: String = "job-1",
    profileId: String = "profile-7",
): ImportJob = ImportJob(
    id = id,
    profileId = profileId,
    provider = "trakt",
    mode = "full",
    status = "queued",
    requestedByUserId = "user-1",
    errorMessage = null,
    createdAt = null,
    startedAt = null,
    finishedAt = null,
    updatedAt = null,
)

