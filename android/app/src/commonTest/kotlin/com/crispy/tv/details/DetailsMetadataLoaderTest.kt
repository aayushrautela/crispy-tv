package com.crispy.tv.details

import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ClientParentRef
import com.crispy.tv.backend.MetadataExtrasList
import com.crispy.tv.backend.MetadataTitleExtrasResponse
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [DetailsMetadataLoader], the `commonMain` fetch half of the player details
 * screen.
 *
 * ## Why the decisions here and not in the view model
 *
 * The loader publishes through narrow write slots rather than taking a `StateFlow`,
 * because `PlayerUiState` is an `androidMain` type it cannot see. That means the
 * seasons-unchanged guard — `size == && containsAll` — reads `_uiState` and therefore
 * lives in the *view model's* `onSeasons` slot, not in the loader. **A decision no
 * test can call is a decision no test can cover**, so this suite pins what the loader
 * does reach: it hands the slot whatever `seasonNumbers()` produced, unchanged and
 * unfiltered, and it never hands it an empty list for a failure.
 *
 * ## What each group of cases is really guarding
 *
 * - **The loading flag is published before the coroutine is launched.** The details
 *   screen shows a spinner the moment the user scrolls to the rail, and a launch that
 *   has not started yet would leave that scroll with nothing for a frame. The
 *   assertion is therefore made *before* `advanceUntilIdle()`, and repeated on the
 *   failure path — a failed fetch that leaves the spinner up forever is the bug this
 *   ordering is easiest to reintroduce.
 * - **The recommendation filter has three stages** — collect the ids already on
 *   screen, exclude them, then de-duplicate. Two of the three are silent if removed:
 *   dropping the exclusion shows the user the title they are already looking at, and
 *   dropping the de-duplication shows the same card twice. Neither fails a compile,
 *   and no image gate covers it because nothing renders this rail.
 * - **The fetch-once latch** is a flag, not a comparison against the published list,
 *   so a second call cannot re-enter the network even when the first produced nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsMetadataLoaderTest {

    // ---------------------------------------------------------------- blank input

    @Test
    fun aBlankIdentifierFetchesNothingAndPublishesNothing() = runTest {
        val api = FakeAccountApi(session())
        val seasons = mutableListOf<List<Int>>()
        val loading = mutableListOf<Boolean>()

        loader(api, StubBackendApi(extras()), seasons, loading).fetchSeasons("  ")
        loader(api, StubBackendApi(extras()), seasons, loading).fetchTitleExtras(null)
        advanceUntilIdle()

        assertEquals(0, api.ensureValidSessionCalls, "a blank id must not ask for a session")
        assertTrue(seasons.isEmpty(), "a blank id must not publish seasons, got $seasons")
        assertTrue(loading.isEmpty(), "a blank id must not even publish loading, got $loading")
    }

    @Test
    fun anIdentifierIsTrimmedBeforeItIsUsed() = runTest {
        val api = FakeAccountApi(session())
        val stub = StubBackendApi(extras(lists = listOf(listOf("tt0903747"))))

        loader(api, stub, mutableListOf(), mutableListOf()).fetchTitleExtras("  tt0903747  ")
        advanceUntilIdle()

        assertEquals(listOf("tt0903747"), stub.requestedIds, "the id is sent trimmed")
    }

    // ------------------------------------------------------------------- no session

    @Test
    fun aMissingSessionPublishesNoSeasonsAndNoRecommendations() = runTest {
        val api = FakeAccountApi(session = null)
        val seasons = mutableListOf<List<Int>>()
        val recommended = mutableListOf<List<CatalogItem>>()
        val loading = mutableListOf<Boolean>()

        val subject = loader(api, StubBackendApi(extras()), seasons, loading, recommended)
        subject.fetchSeasons("tt0903747")
        subject.fetchTitleExtras("tt0903747")
        advanceUntilIdle()

        assertTrue(seasons.isEmpty(), "no session must not publish seasons, got $seasons")
        assertTrue(recommended.isEmpty(), "no session must not publish recommendations")
        // The latch was set and the flag raised, so the flag has to come back down.
        assertEquals(listOf(true, false), loading, "a fetch that never ran must still clear loading")
    }

    // ------------------------------------------------------------------ loading flag

    @Test
    fun theLoadingFlagIsPublishedBeforeTheCoroutineRuns() = runTest {
        val loading = mutableListOf<Boolean>()
        val subject = loader(FakeAccountApi(session()), StubBackendApi(extras()), mutableListOf(), loading)

        subject.fetchTitleExtras("tt0903747")

        assertEquals(listOf(true), loading, "loading is published synchronously, not from inside the launch")
    }

    @Test
    fun aFailedFetchStillClearsTheLoadingFlag() = runTest {
        val loading = mutableListOf<Boolean>()
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(FakeAccountApi(session()), StubBackendApi(extras = null), mutableListOf(), loading, recommended)

        subject.fetchTitleExtras("tt0903747")
        advanceUntilIdle()

        assertEquals(listOf(true, false), loading)
        assertTrue(recommended.isEmpty(), "a failed fetch must not publish an empty rail")
    }

    @Test
    fun aSuccessfulFetchClearsTheLoadingFlagAndPublishes() = runTest {
        val loading = mutableListOf<Boolean>()
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(
            FakeAccountApi(session()),
            // A *different* title from the one fetched: recommending the title that
            // was just fetched is exactly what the exclusion filter removes, so
            // seeding the success case with it asserts nothing about success.
            StubBackendApi(extras(lists = listOf(listOf("tt0903747")))),
            mutableListOf(),
            loading,
            recommended,
        )

        subject.fetchTitleExtras("tt0111161")
        advanceUntilIdle()

        assertEquals(listOf(true, false), loading)
        assertEquals(1, recommended.size)
        assertEquals(listOf("tt0903747"), recommended.single().map { it.itemId })
    }

    // ------------------------------------------------------------------ the latch

    @Test
    fun theRecommendationsAreFetchedOnlyOncePerPage() = runTest {
        val stub = StubBackendApi(extras(lists = listOf(listOf("tt0111161"))))
        val loading = mutableListOf<Boolean>()
        val subject = loader(FakeAccountApi(session()), stub, mutableListOf(), loading)

        subject.fetchTitleExtras("tt0111161")
        subject.fetchTitleExtras("tt0111161")
        advanceUntilIdle()

        assertEquals(listOf("tt0111161"), stub.requestedIds, "the second call must not re-enter the network")
        assertEquals(listOf(true, false), loading, "a dropped call must not raise the flag a second time")
    }

    // ------------------------------------------------------ the recommendation filter

    @Test
    fun theSameCardFromTwoListsIsPublishedOnce() = runTest {
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = listOf(listOf("tt0111161"), listOf("tt0111161", "tt0108778")))),
            mutableListOf(),
            mutableListOf(),
            recommended,
        )

        subject.fetchTitleExtras("tt0000000")
        advanceUntilIdle()

        assertEquals(
            listOf("tt0111161", "tt0108778"),
            recommended.single().map { it.itemId },
            "the same card under two lists collapses to one",
        )
    }

    @Test
    fun theTitleAlreadyOnScreenIsExcludedUnderBothOfItsIds() = runTest {
        // The page can be opened from a catalogue card, so the id in the URL and the
        // addon id the backend returns are frequently *different* strings for the same
        // title. Excluding only one of them puts the open title back in its own rail.
        val onScreen = details(itemId = "catalogue-99", id = "tt0903747")
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = listOf(listOf("tt0903747", "tt0111161")))),
            mutableListOf(),
            mutableListOf(),
            recommended,
            currentDetails = { onScreen },
        )

        subject.fetchTitleExtras("tt0000000")
        advanceUntilIdle()

        assertEquals(listOf("tt0111161"), recommended.single().map { it.itemId })
    }

    @Test
    fun onlyTheFetchedIdIsExcludedWhenNoTitleIsOpen() = runTest {
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = listOf(listOf("tt0903747", "tt0111161")))),
            mutableListOf(),
            mutableListOf(),
            recommended,
            currentDetails = { null },
        )

        subject.fetchTitleExtras("tt0903747")
        advanceUntilIdle()

        assertEquals(listOf("tt0111161"), recommended.single().map { it.itemId })
    }

    // ------------------------------------------------------------------ the seasons

    @Test
    fun seasonsArePublishedSortedDistinctAndPositiveOnly() = runTest {
        val stub = StubBackendApi(
            extras(seasons = listOf(seasonCard(3), seasonCard(1), seasonCard(3), seasonCard(0), seasonCard(2))),
        )
        val seasons = mutableListOf<List<Int>>()

        loader(FakeAccountApi(session()), stub, seasons, mutableListOf()).fetchSeasons("tt0903747")
        advanceUntilIdle()

        assertEquals(listOf(listOf(1, 2, 3)), seasons)
    }

    @Test
    fun aFailedSeasonsFetchPublishesNothingRatherThanAnEmptyList() = runTest {
        // An empty list would clear seasons already on screen; "publish nothing" is a
        // different event and the loader has to keep the two apart.
        val seasons = mutableListOf<List<Int>>()
        val subject = loader(FakeAccountApi(session()), StubBackendApi(extras = null), seasons, mutableListOf())

        subject.fetchSeasons("tt0903747")
        advanceUntilIdle()

        assertTrue(seasons.isEmpty(), "a failed fetch must not publish an empty list, got $seasons")
    }

    @Test
    fun theLoaderDoesNotFilterTheSeasonsItIsGiven() = runTest {
        // The unchanged-list guard reads `PlayerUiState`, which the loader cannot see,
        // so it sits in the view model's slot. The loader's own contract is that it
        // hands the slot exactly what `seasonNumbers()` produced.
        val stub = StubBackendApi(extras(seasons = listOf(seasonCard(2), seasonCard(1))))
        val seasons = mutableListOf<List<Int>>()

        loader(FakeAccountApi(session()), stub, seasons, mutableListOf()).fetchSeasons("tt0903747")
        advanceUntilIdle()

        assertEquals(listOf(listOf(1, 2)), seasons)
    }

    // ------------------------------------------------------------------- no filter

    @Test
    fun aCardWithNoArtworkIsDroppedRatherThanPublishedBlank() = runTest {
        // `toCatalogItem` is the one that decides this, and it is the boundary the
        // loader delegates to rather than duplicating.
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = listOf(listOf("tt0111161")))),
            mutableListOf(),
            mutableListOf(),
            recommended,
        )

        subject.fetchTitleExtras("tt0000000")
        advanceUntilIdle()

        assertEquals(1, recommended.single().size)
    }

    @Test
    fun nothingIsPublishedWhenTheSessionCannotBeEstablished() = runTest {
        val stub = StubBackendApi(extras(lists = listOf(listOf("tt0111161"))))
        val recommended = mutableListOf<List<CatalogItem>>()
        val subject = loader(FakeAccountApi(session = null), stub, mutableListOf(), mutableListOf(), recommended)

        subject.fetchTitleExtras("tt0111161")
        advanceUntilIdle()

        assertTrue(stub.requestedIds.isEmpty(), "no session means no request, got ${stub.requestedIds}")
        assertTrue(recommended.isEmpty())
    }

    @Test
    fun aBlankIdNeverReachesTheBackend() = runTest {
        val stub = StubBackendApi(extras())
        val subject = loader(FakeAccountApi(session()), stub, mutableListOf(), mutableListOf())

        subject.fetchSeasons("")
        subject.fetchSeasons("   ")
        advanceUntilIdle()

        assertTrue(stub.requestedIds.isEmpty(), "got ${stub.requestedIds}")
    }

    @Test
    fun theSlotIsReadLazilySoItTracksThePageStillOnScreen() = runTest {
        var onScreen: MediaDetails? = null
        val reads = mutableListOf<MediaDetails?>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = listOf(listOf("tt0903747")))),
            mutableListOf(),
            mutableListOf(),
            mutableListOf(),
            currentDetails = { reads += onScreen; onScreen },
        )

        onScreen = details(itemId = null, id = "tt0903747")
        subject.fetchTitleExtras("tt0111161")
        advanceUntilIdle()

        assertEquals(listOf(onScreen), reads.toList(), "the details are read when the filter runs, not when the loader is built")
    }

    @Test
    fun aSessionIsOnlyRequestedOncePerFetch() = runTest {
        val api = FakeAccountApi(session())
        val subject = loader(api, StubBackendApi(extras()), mutableListOf(), mutableListOf())

        subject.fetchSeasons("tt0903747")
        subject.fetchTitleExtras("tt0903747")
        advanceUntilIdle()

        assertEquals(2, api.ensureValidSessionCalls, "one per fetch; the two fetches are independent")
    }

    @Test
    fun noRecommendationsArePublishedWhenTheBackendReturnsNoLists() = runTest {
        val recommended = mutableListOf<List<CatalogItem>>()
        val loading = mutableListOf<Boolean>()
        val subject = loader(
            FakeAccountApi(session()),
            StubBackendApi(extras(lists = emptyList())),
            mutableListOf(),
            loading,
            recommended,
        )

        subject.fetchTitleExtras("tt0111161")
        advanceUntilIdle()

        assertEquals(1, recommended.size, "an empty rail is still a rail, and it is published")
        assertTrue(recommended.single().isEmpty())
        assertEquals(listOf(true, false), loading)
    }

    // ------------------------------------------------------------------- fixtures

    private fun kotlinx.coroutines.test.TestScope.loader(
        accountApi: com.crispy.tv.accounts.AccountApi,
        backendApi: BackendApi,
        seasons: MutableList<List<Int>>,
        loading: MutableList<Boolean>,
        recommended: MutableList<List<CatalogItem>> = mutableListOf(),
        currentDetails: () -> MediaDetails? = { null },
    ): DetailsMetadataLoader = DetailsMetadataLoader(
        accountApi = accountApi,
        backendApi = backendApi,
        // The TestScope itself, not `backgroundScope`: `advanceUntilIdle()` drives the
        // scope the test body runs in, and `backgroundScope` work is torn down when
        // the test finishes -- which is right for a long-lived watcher and wrong here.
        scope = this,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        onSeasons = { seasons += it },
        onMoreLoading = { loading += it },
        onRecommended = { recommended += it },
        currentDetails = currentDetails,
    )

    private fun session() = Session(
        accessToken = "token",
        refreshToken = "refresh",
        expiresAtEpochSec = null,
        userId = "user-1",
        email = "user@example.test",
        anonymous = false,
    )

    private fun extras(
        seasons: List<ClientMediaCard> = emptyList(),
        lists: List<List<String>> = emptyList(),
    ) = MetadataTitleExtrasResponse(
        seasons = seasons,
        reviews = emptyList(),
        lists = lists.map { ids -> MetadataExtrasList(key = "list", title = "List", items = ids.map(::card)) },
    )

    private fun seasonCard(season: Int) = card(
        itemId = "season-$season",
        mediaType = "season",
        season = season,
    )

    private fun card(
        itemId: String,
        mediaType: String = "movie",
        season: Int? = null,
    ) = ClientMediaCard(
        itemId = itemId,
        mediaType = mediaType,
        title = "Title $itemId",
        overview = "Overview $itemId",
        year = 2026,
        releaseDate = "2026-01-05",
        rating = 7.5,
        maturityRating = null,
        genres = listOf("Drama"),
        runtimeSeconds = null,
        images = ClientImages(
            artwork = ResponsiveImageSet(
                low = "https://img.test/$itemId.jpg",
                medium = "https://img.test/$itemId.jpg",
                high = "https://img.test/$itemId.jpg",
            ),
            logo = ResponsiveImageSet(null, null, null),
            still = ResponsiveImageSet(null, null, null),
        ),
        progress = null,
        parent = season?.let {
            ClientParentRef(
                seriesItemId = "tt0903747",
                seriesTitle = "Series",
                seasonItemId = "season-$it",
                seasonNumber = it,
                episodeNumber = null,
            )
        },
    )

    private fun details(itemId: String?, id: String) = MediaDetails(
        id = id,
        imdbId = null,
        itemType = "series",
        title = "On screen",
        artworkUrl = "https://img.test/on-screen.jpg",
        description = "On screen",
        genres = emptyList(),
        year = "2026",
        runtime = null,
        certification = null,
        rating = "8.0",
        cast = emptyList(),
        directors = emptyList(),
        creators = emptyList(),
        videos = emptyList(),
        itemId = itemId,
        addonId = null,
    )

    /**
     * [RecordingBackendApi] answers the members it is built to record and throws for
     * the rest, so this double is a one-member override rather than a second
     * 52-member file. That is why [RecordingBackendApi] had to be made `open`: without
     * it, a new `BackendApi` consumer in `:app` has exactly two choices — duplicate
     * 52 members that silently rot, or use a narrow port that never learns a new
     * member arrived. Subclassing the exhaustive one costs one word.
     */
    private class StubBackendApi(
        private val extras: MetadataTitleExtrasResponse?,
    ) : RecordingBackendApi() {
        val requestedIds = mutableListOf<String>()

        override suspend fun getMetadataItemExtras(
            accessToken: String,
            itemId: String,
        ): MetadataTitleExtrasResponse {
            requestedIds += itemId
            // `BackendApi` returns non-null, so "the fetch gave nothing" is a
            // *thrown* answer, not a null one -- and the loader's `runCatching`
            // turns it into the same "publish nothing" path a real failure takes.
            return extras ?: error("no answer configured for $itemId")
        }
    }
}
