package com.crispy.tv.home

import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.CalendarItem
import com.crispy.tv.backend.CalendarResponse
import com.crispy.tv.backend.ImportJobsResponse
import com.crispy.tv.backend.ImportProvider
import com.crispy.tv.backend.ItemLookupInput
import com.crispy.tv.backend.PlaybackEventInput
import com.crispy.tv.backend.UpNextItem
import com.crispy.tv.backend.UpNextResponse
import com.crispy.tv.backend.UpdateProfileInput
import com.crispy.tv.backend.WatchMutationInput

/**
 * A [BackendApi] that records what it was asked and throws for everything else.
 *
 * Exhaustive on purpose, and the compiler is the point: adding a member to
 * [BackendApi] fails to compile here until a decision is made about what that
 * member means. `:app` keeps its own copy of this same double for the same
 * reason -- `internal` is module-wide, so one module's test double is invisible
 * to the next, and a partial one would let a new member arrive unexamined.
 *
 * The three reads below are the ones `CalendarService` and `UpNextService` make.
 * The other 49 throw `AssertionError` naming the member, which is louder than a
 * silent default and says immediately which call a test forgot to stub.
 */
internal class RecordingBackendApi : BackendApi {
    val calendarCalls = mutableListOf<Pair<String, String>>()
    val thisWeekCalls = mutableListOf<Pair<String, String>>()
    val upNextCalls = mutableListOf<Triple<String, String, Int>>()

    var calendarResponse: CalendarResponse? = null
    var thisWeekResponse: CalendarResponse? = null
    var upNextResponse: UpNextResponse? = null

    /** When set, the named read throws it instead of answering. */
    var calendarFailure: Throwable? = null
    var thisWeekFailure: Throwable? = null
    var upNextFailure: Throwable? = null

    fun withCalendar(vararg items: CalendarItem) = apply {
        calendarResponse = calendarResponse(
            items = items.toList(),
        )
    }

    fun withThisWeek(vararg items: CalendarItem) = apply {
        thisWeekResponse = calendarResponse(
            items = items.toList(),
        )
    }

    fun withUpNext(vararg items: UpNextItem) = apply {
        upNextResponse = UpNextResponse(
            profileId = PROFILE_ID,
            source = "test",
            kind = null,
            generatedAt = null,
            items = items.toList(),
        )
    }

    override val baseUrl: String get() = "https://api.test"

    override fun isConfigured(): Boolean = true

    override suspend fun getCalendar(
        accessToken: String,
        profileId: String,
    ): CalendarResponse {
        calendarCalls += accessToken to profileId
        calendarFailure?.let { throw it }
        return calendarResponse
            ?: throw AssertionError("getCalendar was not stubbed with a response for this test")
    }

    override suspend fun getCalendarThisWeek(
        accessToken: String,
        profileId: String,
    ): CalendarResponse {
        thisWeekCalls += accessToken to profileId
        thisWeekFailure?.let { throw it }
        return thisWeekResponse
            ?: throw AssertionError("getCalendarThisWeek was not stubbed with a response for this test")
    }

    override suspend fun getUpNext(
        accessToken: String,
        profileId: String,
        limit: Int,
    ): UpNextResponse {
        upNextCalls += Triple(accessToken, profileId, limit)
        upNextFailure?.let { throw it }
        return upNextResponse
            ?: throw AssertionError("getUpNext was not stubbed with a response for this test")
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
    override suspend fun listImportConnections(
        accessToken: String,
        profileId: String
): Nothing = unused("listImportConnections")
    override suspend fun listImportJobs(accessToken: String, profileId: String): Nothing = unused("listImportJobs")
    override suspend fun disconnectImportConnection(
        accessToken: String,
        profileId: String,
        provider: ImportProvider
): Nothing = unused("disconnectImportConnection")
    override suspend fun startImport(
        accessToken: String,
        profileId: String,
        provider: ImportProvider,
        action: String,
        clientId: String,
        returnTo: String
): Nothing = unused("startImport")
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
    override suspend fun browseTitles(
        accessToken: String,
        type: String,
        genre: String?,
        sort: String,
        page: Int
): Nothing = unused("browseTitles")
    override suspend fun getHome(accessToken: String, profileId: String): Nothing = unused("getHome")
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

internal const val PROFILE_ID = "profile-1"
internal const val ACCESS_TOKEN = "token-1"

private fun calendarResponse(items: List<CalendarItem>) = CalendarResponse(
    profileId = PROFILE_ID,
    source = "test",
    kind = null,
    generatedAt = "2026-01-01T00:00:00Z",
    items = items,
)

