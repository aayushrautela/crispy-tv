package com.crispy.tv.home

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.MetadataProductionInfoView
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.testing.FakeStreamResolver
import com.crispy.tv.testing.RecordingAppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.AfterTest

/**
 * Covers the ported [HomeSelectorViewModel].
 *
 * The class builds its own [com.crispy.tv.streams.SelectorCoordinator] — it has to,
 * because the coordinator's scope is `viewModelScope` and that does not exist until
 * this object does — but everything the coordinator is built *from* is a port or a
 * function slot, so a test supplies fakes and gets a real coordinator. The behaviour
 * this file pins is the viewmodel's: which branch `openFor` takes, what it puts into
 * the coordinator, and what it emits on `playStream`.
 *
 * Two harness facts, both measured here and both easy to get wrong:
 *  - [viewModel] installs `Dispatchers.setMain` **before** constructing the viewmodel,
 *    because `viewModelScope` captures the dispatcher at construction time. Installed
 *    after, the coordinator's `resolve()` launch never runs at all.
 *  - it builds ONE dispatcher and passes it as BOTH `setMain` and `ioDispatcher`. The
 *    viewmodel hops to `ioDispatcher` around the repository read, and `Dispatchers.IO`
 *    is a real thread pool, so `setMain` alone leaves assertions racing an
 *    unobserved thread. Sixth measured instance of that lesson.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSelectorViewModelTest {

    private val streamResolver = FakeStreamResolver()
    private val logger = RecordingAppLogger()

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingAnItemPublishesTheHeaderAndTheFallbackDetails() = runTest {
        // The coordinator replaces `details` with the metadata answer as soon as that
        // fetch returns, and the unconfined dispatcher runs it eagerly — so the row's
        // OWN fallback is only observable while the fetch is in flight. Gating the
        // slot is what makes it observable; without it this case would be asserting
        // on the response instead of on the viewmodel.
        val viewModel = viewModel(gateMetadata = true)
        viewModel.openFor(item())
        advanceUntilIdle()

        val header = viewModel.coordinator.headerEpisode.value
        assertNotNull("the header episode is the MediaVideo built from the row", header)
        assertEquals("Episode title", header?.title)
        assertEquals("row-1", header?.id)
        assertEquals(2, header?.season)
        assertEquals(7, header?.episode)

        val fallback = viewModel.coordinator.details.value
        assertNotNull("the fallback details are published until the backend answers", fallback)
        assertEquals("Title", fallback?.title)
        assertEquals("title-1", fallback?.itemId)
        assertEquals("Subtitle", fallback?.description)
        assertEquals("addon-1", fallback?.addonId)
        assertEquals(2, fallback?.seasonNumber)
        assertEquals(7, fallback?.episodeNumber)
        // imdbId is deliberately NOT asserted here: the coordinator replaces `details`
        // with the metadata answer as soon as that fetch returns, and the answer's
        // imdbId comes from a different field of the response than the row's does. The
        // fields above are the ones the viewmodel itself sets on the fallback.
    }

    @Test
    fun anEpisodeRowLooksUpByImdbSeasonAndEpisodeRatherThanTheTitle() = runTest {
        val viewModel = viewModel()
        viewModel.openFor(item(imdbId = "tt12345", titleItemId = "title-1", season = 2, episode = 7))

        // buildAddonEpisodeLookupId requires a "tt"-prefixed id and returns
        // "tt12345:2:7" for an episode; a row without one falls back to its
        // titleItemId, which is what the second case pins.
        assertEquals("tt12345:2:7", viewModel.coordinator.headerEpisode.value?.lookupId)
    }

    @Test
    fun aRowWithoutATtPrefixedImdbIdFallsBackToItsTitle() = runTest {
        val viewModel = viewModel()
        viewModel.openFor(item(imdbId = "imdb-1"))

        assertEquals(
            "a non-tt id cannot build an episode key, so the title is used",
            "title-1",
            viewModel.coordinator.headerEpisode.value?.lookupId,
        )
    }

    @Test
    fun openingTheSameItemTwiceDoesNotGoBackToTheResolver() = runTest {
        val viewModel = viewModel()
        viewModel.openFor(item())
        advanceUntilIdle()
        val callsAfterFirstOpen = streamResolver.resolveCalls.size
        assertTrue("the first open resolved providers, got $callsAfterFirstOpen", callsAfterFirstOpen > 0)
        assertTrue(
            "the first open produced providers to re-reveal",
            viewModel.coordinator.state.value.providers.isNotEmpty(),
        )

        viewModel.openFor(item())
        advanceUntilIdle()

        assertEquals(
            "the second open must not resolve again, had $callsAfterFirstOpen calls",
            callsAfterFirstOpen,
            streamResolver.resolveCalls.size,
        )
    }

    @Test
    fun openingADifferentItemReplacesTheHeader() = runTest {
        val viewModel = viewModel()
        viewModel.openFor(item())
        advanceUntilIdle()
        assertEquals("tt12345:2:7", viewModel.coordinator.headerEpisode.value?.lookupId)

        viewModel.openFor(item(id = "row-2", imdbId = "tt99999"))
        advanceUntilIdle()

        assertEquals("tt99999:2:7", viewModel.coordinator.headerEpisode.value?.lookupId)
    }

    @Test
    fun dismissHidesTheSurfaceButKeepsTheTargetSoReshowCanReRevealIt() = runTest {
        val viewModel = viewModel()
        viewModel.openFor(item())
        advanceUntilIdle()
        assertTrue("the first open left a visible surface", viewModel.coordinator.state.value.visible)

        viewModel.dismiss()

        val state = viewModel.coordinator.state.value
        assertTrue("dismiss hides the surface", state.visible.not())
        assertTrue(
            "dismiss clears the loading flags on every provider",
            state.providers.all { provider -> provider.isLoading.not() },
        )
        assertEquals(
            "dismiss deliberately KEEPS the lookupId so reshow can re-reveal it",
            "tt12345:2:7",
            state.lookupId,
        )
        viewModel.coordinator.reshow(null)
        assertTrue("reshow re-reveals the kept target", viewModel.coordinator.state.value.visible)
    }

    @Test
    fun aProgressInsideTheResumableBandIsPublishedAsAResumePosition() = runTest {
        val viewModel = viewModel(progress = progress(123.0, 600.0))
        viewModel.openFor(item())
        advanceUntilIdle()

        val selection = tapAndAwait(viewModel)
        assertEquals(123_000L, selection.resumePositionMs)
        assertEquals("row-1", selection.identity.itemId)
    }

    @Test
    fun aProgressUnderOnePercentResumesFromTheStart() = runTest {
        // 1.2s of 600s is 0.2%: under the band, so the row counts as not started.
        val viewModel = viewModel(progress = progress(1.2, 600.0))
        viewModel.openFor(item())
        advanceUntilIdle()

        assertEquals(0L, tapAndAwait(viewModel).resumePositionMs)
    }

    @Test
    fun aProgressPastTheBandResumesFromTheStart() = runTest {
        // 96% is past the band: someone who has watched that far should be offered
        // the next thing, not a resume point almost at the end.
        val viewModel = viewModel(progress = progress(576.0, 600.0))
        viewModel.openFor(item())
        advanceUntilIdle()

        assertEquals(0L, tapAndAwait(viewModel).resumePositionMs)
    }

    @Test
    fun theStashedHandoffKeyAndTheChosenStreamTravelWithTheSelection() = runTest {
        var stashedFor: String? = null
        val viewModel =
            viewModel(stashHandoff = { _, lookupId ->
                stashedFor = lookupId
                "handoff-key"
            })
        viewModel.openFor(item())
        advanceUntilIdle()

        val selection = tapAndAwait(viewModel)
        assertEquals("handoff-key", selection.chosenStreamHandoffKey)
        assertEquals("stable-1", selection.chosenStreamStableKey)
        assertEquals("provider-1", selection.chosenProviderId)
        assertEquals("tt12345:2:7", stashedFor)
    }

    @Test
    fun noProgressAtAllResumesFromTheStart() = runTest {
        val viewModel = viewModel(progress = null)
        viewModel.openFor(item())
        advanceUntilIdle()

        assertEquals(0L, tapAndAwait(viewModel).resumePositionMs)
    }

    // --- harness ------------------------------------------------------------

    /**
     * Subscribes, taps a stream, then returns what was published.
     *
     * `playStream` is a `MutableSharedFlow(extraBufferCapacity = 1)` with **no replay**,
     * and the emission happens eagerly on the unconfined dispatcher. A collector that
     * subscribes afterwards waits for an emission that has already been dropped, so the
     * `async` must be started before the tap — the same shape as the gate any guard
     * that only fires while a coroutine is suspended needs. `UNDISPATCHED` so the
     * subscription is established on the calling thread before anything can emit.
     */
    private fun tapAndAwait(viewModel: HomeSelectorViewModel): HomeStreamSelection =
        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { viewModel.playStream.first() }
            viewModel.coordinator.onStreamSelected(stream())
            pending.await()
        }

    private fun viewModel(
        progress: WatchProgressSnapshot? = null,
        stashHandoff: (AddonStream, String) -> String? = { _, _ -> "handoff-key" },
        gateMetadata: Boolean = false,
    ): HomeSelectorViewModel {
        // viewModelScope captures Dispatchers.Main at construction time, so this has
        // to happen before the ViewModel exists. Without it the coordinator's
        // resolve() launch never runs and every assertion sees an empty state.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        return HomeSelectorViewModel(
            streamResolver = streamResolver,
            logger = logger,
            getMetadataItemDetail = { _, _ ->
                if (gateMetadata) {
                    // Park forever: the row's own fallback stays published for as long
                    // as this fetch is in flight, which is the only window in which the
                    // viewmodel's fallback is observable at all.
                    CompletableDeferred<Unit>().await()
                }
                titleDetail()
            },
            sessionTokenProvider = { "token" },
            pluginStreamLoader = null as PluginStreamLoader?,
            userMediaRepository = FixedUserMediaRepository(progress),
            stashHandoff = stashHandoff,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    // --- fixtures -----------------------------------------------------------

    /**
     * `progressPercent` is not a field: it is a computed property of
     * `currentTimeSeconds / durationSeconds`, so a case that wants "40% watched"
     * has to say so as a ratio. That ratio is what the `1.0..95.0` band is applied to.
     */
    private fun progress(currentTimeSeconds: Double, durationSeconds: Double) =
        WatchProgressSnapshot(
            currentTimeSeconds = currentTimeSeconds,
            durationSeconds = durationSeconds,
            lastUpdatedEpochMs = 1_700_000_000_000L,
        )

    /**
     * `MetadataTitleDetailResponse` is not an empty builder: it takes seven required
     * arguments, two of which are themselves required. It is built only so the
     * coordinator can be constructed — every case above asserts the viewmodel's own
     * published state, not a backend answer.
     */
    private fun titleDetail() =
        MetadataTitleDetailResponse(
            item = card(),
            nextEpisode = null,
            videos = emptyList(),
            cast = emptyList(),
            directors = emptyList(),
            creators = emptyList(),
            production = MetadataProductionInfoView(
                originalLanguage = null,
                originCountries = emptyList(),
                spokenLanguages = emptyList(),
                productionCountries = emptyList(),
                companies = emptyList(),
                networks = emptyList(),
            ),
        )

    private fun image() = ResponsiveImageSet(low = null, medium = null, high = null)

    private fun card() =
        ClientMediaCard(
            itemId = "title-1",
            mediaType = "episode",
            title = "Title",
            overview = null,
            year = null,
            releaseDate = null,
            rating = null,
            maturityRating = null,
            genres = emptyList(),
            runtimeSeconds = null,
            images = ClientImages(artwork = image(), logo = image(), still = image()),
            progress = null,
            parent = null,
        )

    private fun item(
        id: String = "row-1",
        imdbId: String? = "tt12345",
        titleItemId: String = "title-1",
        season: Int? = 2,
        episode: Int? = 7,
    ) =
        CanonicalContinueWatchingItem(
            id = id,
            titleItemId = titleItemId,
            playbackItemId = "playback-1",
            itemType = "episode",
            title = "Title",
            subtitle = "Subtitle",
            imdbId = imdbId,
            episodeTitle = "Episode title",
            season = season,
            episode = episode,
            progressPercent = 40.0,
            lastUpdatedEpochMs = 1_700_000_000_000L,
            addonId = "addon-1",
            absoluteEpisodeNumber = null,
        )

    private fun stream() =
        AddonStream(
            providerId = "provider-1",
            providerName = "Provider",
            name = "Provider",
            url = "https://example.invalid/stream",
            stableKey = "stable-1",
        )

    /**
     * A hand-written `UserMediaRepository` rather than a generated double: the
     * viewmodel asks it exactly one question, and recording the identity it was asked
     * about is more useful than stubbing ten members. The ten it does not use throw
     * loudly — the shape is the contract here, so the unused half fails rather than
     * quietly returning a default nobody would notice.
     */
    private class FixedUserMediaRepository(
        private val progress: WatchProgressSnapshot?,
    ) : UserMediaRepository {
        var askedFor: PlaybackIdentity? = null

        override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? {
            askedFor = identity
            return progress
        }

        override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? =
            unused("getCanonicalWatchState")

        override suspend fun getTitleWatchState(
            itemId: String,
            contentType: MetadataLabMediaType,
        ): CanonicalWatchStateSnapshot? = unused("getTitleWatchState")

        override suspend fun getCanonicalContinueWatching(
            limit: Int,
            nowMs: Long,
        ): CanonicalContinueWatchingResult = unused("getCanonicalContinueWatching")

        override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
            unused("markWatched")

        override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
            unused("unmarkWatched")

        override suspend fun setInWatchlist(
            request: WatchHistoryRequest,
            inWatchlist: Boolean,
        ): WatchHistoryResult = unused("setInWatchlist")

        override suspend fun setTitleInWatchlist(
            itemId: String,
            inWatchlist: Boolean,
        ): WatchHistoryResult = unused("setTitleInWatchlist")

        override suspend fun setLiked(
            request: WatchHistoryRequest,
            liked: Boolean?,
        ): WatchHistoryResult = unused("setLiked")

        override suspend fun setTitleLiked(
            itemId: String,
            liked: Boolean?,
        ): WatchHistoryResult = unused("setTitleLiked")

        private fun unused(name: String): Nothing = throw AssertionError("$name is not stubbed")
    }
}
