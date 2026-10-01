package com.crispy.tv.playerui

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.addons.mapping.toMediaVideo
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ClientParentRef
import com.crispy.tv.backend.MetadataSeriesEpisodesResponse
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.accounts.RecordingBackendApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The rules behind the season's episode list, and the reason they are worth a suite at all.
 *
 * ## What this class changed about the tests
 *
 * The view model this loader came out of wrote the same `if (current.selectedSeason != season)`
 * guard **five** times in one method -- four of them carrying a status message. That is why the
 * stale-response rule had no coverage at all: there was no single function a test could call.
 * The whole value of the extraction is that the guard now lives in one place,
 * `SeasonEpisodesLoader.publish`, and a test can therefore ask it a question.
 *
 * ## What is deliberately not tested here
 *
 * The "this is a movie, there are no seasons" early return stays with the caller, so it is not
 * reachable from here; and the loader publishes through a slot, so *how* the view model turns an
 * outcome into a `_uiState` copy is the view model's business, not this suite's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonEpisodesLoaderTest {

    @Test
    fun theFirstRequestPublishesLoadingBeforeTheCoroutineRuns() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        // Before advanceUntilIdle(): the spinner must already be showing, because the rail
        // has to answer the same frame the user asked for the season.
        assertEquals(outcomes(SeasonEpisodesOutcome.Loading), h.outcomes)
        advanceUntilIdle()
        assertEquals(2, h.outcomes.size)
    }

    @Test
    fun aSuccessfulLoadPublishesTheEpisodes() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(
            outcomes(SeasonEpisodesOutcome.Loaded(videos = h.expectedVideos)),
            h.outcomes.drop(1),
        )
    }

    @Test
    fun aCardIsMappedToItsEpisodeFields() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        val loaded = h.outcomes.last() as SeasonEpisodesOutcome.Loaded
        assertEquals("tt-episode-1", loaded.videos.single().id)
        assertEquals(SEASON, loaded.videos.single().season)
        assertEquals(1, loaded.videos.single().episode)
    }

    @Test
    fun anEmptySeasonPublishesTheEmptyMessageRatherThanNothing() = runTest {
        val h = Harness(this, items = emptyList())
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(
            SeasonEpisodesOutcome.Loaded(videos = emptyList(), statusMessage = EPISODES_EMPTY_MESSAGE),
            h.outcomes.last(),
        )
    }

    @Test
    fun aCardWithABlankIdentifierIsDroppedRatherThanPublishedBlank() = runTest {
        val h = Harness(this, items = listOf(card(itemId = "   ", title = "Nameless")))
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertTrue(
            (h.outcomes.last() as SeasonEpisodesOutcome.Loaded).videos.isEmpty(),
            "a card with no identifier cannot be mapped, so it must not reach the rail",
        )
    }

    // ---- The rule the five-fold repetition made untestable ----

    @Test
    fun aResponseArrivingAfterTheUserChangedSeasonIsDropped() = runTest {
        val h = Harness(this)
        h.selectedSeason = SEASON
        h.reporter.load(SEASON)
        // The request is in flight; the user picks another season before it answers.
        h.selectedSeason = SEASON + 1
        advanceUntilIdle()
        assertEquals(
            outcomes(SeasonEpisodesOutcome.Loading),
            h.outcomes,
            "a response for season $SEASON must not write into the state now showing season ${SEASON + 1}",
        )
    }

    @Test
    fun aFailureArrivingAfterTheUserChangedSeasonIsDropped() = runTest {
        val h = Harness(this, throws = true)
        h.selectedSeason = SEASON
        h.reporter.load(SEASON)
        h.selectedSeason = SEASON + 1
        advanceUntilIdle()
        assertEquals(outcomes(SeasonEpisodesOutcome.Loading), h.outcomes)
    }

    // ---- The cache, which is shared with the caller ----

    @Test
    fun aCachedSeasonPublishesWithoutARequestAndWithoutLoading() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        h.outcomes.clear()
        // Baseline: the first load asked once, so "did not ask again" is a comparison,
        // not `isEmpty()` -- which would only be true if the first load had not happened.
        val requestsSoFar = h.backend.requestedItemIds.size

        h.reporter.load(SEASON)
        advanceUntilIdle()

        assertEquals(
            outcomes(SeasonEpisodesOutcome.Loaded(h.expectedVideos)),
            h.outcomes,
            "a cached season is known, so a spinner would be a lie",
        )
        assertEquals(requestsSoFar, h.backend.requestedItemIds.size, "a cache hit must not reach the network")
    }

    @Test
    fun forceRefetchesACachedSeason() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        val requestsAfterFirstLoad = h.backend.requestedItemIds.size

        h.reporter.load(SEASON, force = true)
        advanceUntilIdle()

        assertEquals(requestsAfterFirstLoad + 1, h.backend.requestedItemIds.size)
    }

    @Test
    fun theLoadWritesIntoTheCacheTheCallerOwns() = runTest {
        val h = Harness(this)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(h.expectedVideos, h.cache[SEASON])
    }

    // ---- The three ways a load can fail, which are three different messages ----

    @Test
    fun aMissingSessionAsksTheUserToSignIn() = runTest {
        val h = Harness(this, session = null)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(
            SeasonEpisodesOutcome.Failed(EPISODES_SIGN_IN_MESSAGE),
            h.outcomes.last(),
        )
    }

    @Test
    fun aThrownSessionLookupReportsAFailureRatherThanASignInPrompt() = runTest {
        val h = Harness(this, account = ThrowingSessionAccountApi())
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(
            SeasonEpisodesOutcome.Failed(EPISODES_FAILED_MESSAGE),
            h.outcomes.last(),
            "a backend that threw is not the same answer as a user who is not signed in",
        )
    }

    @Test
    fun aFailedRequestReportsAFailure() = runTest {
        val h = Harness(this, throws = true)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(SeasonEpisodesOutcome.Failed(EPISODES_FAILED_MESSAGE), h.outcomes.last())
    }

    @Test
    fun aBlankSeriesIdentifierReportsAFailureWithoutAskingTheBackend() = runTest {
        val h = Harness(this, details = details(itemId = null, id = ""))
        h.identity = null
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(SeasonEpisodesOutcome.Failed(EPISODES_FAILED_MESSAGE), h.outcomes.last())
        assertTrue(h.backend.requestedItemIds.isEmpty(), "there is nothing to ask about")
    }

    // ---- Which identifier the request uses ----

    @Test
    fun theIdentitysSeriesIdIsPreferredOverTheOnScreenDetails() = runTest {
        val h = Harness(this, details = details(itemId = "on-screen-item", id = "tt0903747"))
        h.identity = identity(seriesItemId = "from-playback-identity")
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(listOf("from-playback-identity"), h.backend.requestedItemIds)
    }

    @Test
    fun theOnScreenItemIdIsUsedWhenTheIdentityCarriesNoSeries() = runTest {
        val h = Harness(this, details = details(itemId = "on-screen-item", id = "tt0903747"))
        h.identity = identity(seriesItemId = null)
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(listOf("on-screen-item"), h.backend.requestedItemIds)
    }

    @Test
    fun aBlankSeriesIdentifierInTheIdentityFallsBackToTheOnScreenDetails() = runTest {
        val h = Harness(this, details = details(itemId = "on-screen-item", id = "tt0903747"))
        h.identity = identity(seriesItemId = "   ")
        h.reporter.load(SEASON)
        advanceUntilIdle()
        assertEquals(listOf("on-screen-item"), h.backend.requestedItemIds)
    }

    // ---- Fixtures ----

    private class Harness(
        scope: TestScope,
        session: Session? = SESSION,
        throws: Boolean = false,
        items: List<ClientMediaCard> = listOf(card()),
        details: MediaDetails? = details(),
        account: AccountApi = FakeAccountApi(session),
    ) {
        var selectedSeason: Int? = SEASON
        var details: MediaDetails? = details
            private set
        var identity: PlaybackIdentity? = null
        val outcomes = mutableListOf<SeasonEpisodesOutcome>()
        val cache = mutableMapOf<Int, List<MediaVideo>>()
        val backend = StubBackendApi(items, throws)
        val account = account
        val expectedVideos: List<MediaVideo> by lazy {
            items.mapNotNull { it.toMediaVideo() }
        }
        val reporter = SeasonEpisodesLoader(
            accountApi = account,
            backendApi = backend,
            scope = scope,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            cache = cache,
            details = { this.details },
            identity = { identity },
            selectedSeason = { selectedSeason },
            onOutcome = { outcomes += it },
        )
    }

    /**
     * A session lookup that *throws*, which [FakeAccountApi] cannot express: it answers
     * `ensureValidSession` with whatever session it was given, and a null session is the
     * "not signed in" answer rather than the "backend is down" one. Overriding the one
     * member is why [FakeAccountApi] is `open`.
     */
    private class ThrowingSessionAccountApi : FakeAccountApi(null) {
        override suspend fun ensureValidSession(): Session? =
            throw IllegalStateException("backend unavailable")
    }

    /**
     * A one-member override of the module's exhaustive `BackendApi` double, which is `open`
     * precisely so this can exist instead of a second 52-member file.
     */
    private class StubBackendApi(
        private val items: List<ClientMediaCard>,
        private val throws: Boolean,
    ) : RecordingBackendApi() {
        val requestedItemIds = mutableListOf<String>()

        override suspend fun getSeriesEpisodes(
            accessToken: String,
            seriesItemId: String,
            season: Int?,
        ): MetadataSeriesEpisodesResponse {
            requestedItemIds += seriesItemId
            if (throws) throw IllegalStateException("offline")
            return MetadataSeriesEpisodesResponse(items = items)
        }
    }

    /**
     * A typed list of outcomes, because `assertEquals` cannot infer its type parameter when one
     * side is a `List` of a subtype and the other a `MutableList` of the sealed interface --
     * and the failure is `Type inference failed`, which says nothing about the real cause.
     */
    private fun outcomes(vararg values: SeasonEpisodesOutcome): List<SeasonEpisodesOutcome> = values.toList()

    private companion object {
        const val SEASON = 3
        val SESSION = Session(
            accessToken = "token",
            refreshToken = "refresh",
            expiresAtEpochSec = null,
            userId = "user",
            email = null,
            anonymous = false,
        )

        fun card(
            itemId: String = "tt-episode-1",
            title: String = "Pilot",
            season: Int? = SEASON,
            episode: Int? = 1,
        ) = ClientMediaCard(
            itemId = itemId,
            mediaType = "episode",
            title = title,
            overview = null,
            year = 2026,
            releaseDate = "2026-01-05",
            rating = 7.5,
            maturityRating = null,
            genres = listOf("Drama"),
            runtimeSeconds = null,
            images = ClientImages(
                artwork = ResponsiveImageSet(null, null, null),
                logo = ResponsiveImageSet(null, null, null),
                still = ResponsiveImageSet(null, null, null),
            ),
            progress = null,
            parent = ClientParentRef(
                seriesItemId = "tt0903747",
                seriesTitle = "Series",
                seasonItemId = "tt0903747-season-$season",
                seasonNumber = season,
                episodeNumber = episode,
            ),
        )

        fun details(itemId: String? = "tt0903747", id: String = "tt0903747") = MediaDetails(
            id = id,
            itemId = itemId,
            imdbId = "",
            itemType = "series",
            title = "Series",
            artworkUrl = null,
            description = null,
            year = null,
            runtime = null,
            certification = null,
            rating = null,
            addonId = "backend",
        )

        fun identity(seriesItemId: String?) = PlaybackIdentity(
            itemId = "tt0903747",
            seriesItemId = seriesItemId,
            contentType = MetadataLabMediaType.SERIES,
            title = "Series",
        )
    }
}
