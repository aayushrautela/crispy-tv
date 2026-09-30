package com.crispy.tv.home

import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.FixedBackendContextResolver
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.domain.watch.WatchSyncEffect
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.testing.FakeKeyValueStore
import com.crispy.tv.testing.RecordingAppLogger
import com.crispy.tv.watchhistory.sync.WatchSyncSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HomeViewModel] reached `commonMain` in the HomeViewModel batch, and this is
 * its first coverage. The viewmodel is driven through the real
 * [HomeRefreshCoordinator] -- a public `commonMain` class whose constructor
 * takes only ports and fakes, so no port was needed there -- with a fake
 * catalog service, a fake watch-history service, the real suppression store
 * over a [FakeKeyValueStore], and real calendar/up-next services over a
 * [RecordingBackendApi] that throws for everything (the viewmodel swallows rail
 * failures, so unanswered rails read as absent rails).
 *
 * `setMain` is installed before construction because `viewModelScope` captures
 * `Dispatchers.Main` at construction time, and the same unconfined dispatcher
 * is the injected `ioDispatcher`, so no hop ever leaves the test thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    // ---------------------------------------------------------------- doubles

    private class FakeCatalogService : HomeCatalogService {
        var feed: HomePrimaryFeedLoadResult = HomePrimaryFeedLoadResult()
        var cachedFeed: HomePrimaryFeedLoadResult? = null
        var expiresAtMs: Long? = null
        var primaryLoads: Int = 0

        override suspend fun loadPrimaryHomeFeed(sectionLimit: Int): HomePrimaryFeedLoadResult {
            primaryLoads++
            return feed
        }

        override suspend fun loadCachedPrimaryHomeFeed(sectionLimit: Int): HomePrimaryFeedLoadResult? = cachedFeed

        override suspend fun fetchCatalogPage(
            section: CatalogSectionRef,
            page: Int,
            pageSize: Int,
        ): CatalogPageResult = throw AssertionError("fetchCatalogPage is not stubbed")

        override suspend fun cachedHomeExpiresAtMs(): Long? = expiresAtMs
    }

    private class FakeWatchHistoryService : WatchHistoryService {
        var continueWatchingAnswer: CanonicalContinueWatchingResult =
            CanonicalContinueWatchingResult(statusMessage = "")
        var continueWatchingFailures: Int = 0
        val continueWatchingCalls = mutableListOf<Long>()
        var removeAnswer: WatchHistoryResult = WatchHistoryResult(statusMessage = "", accepted = true)
        val removedIds = mutableListOf<String>()

        override suspend fun getCanonicalContinueWatching(
            limit: Int,
            nowMs: Long,
        ): CanonicalContinueWatchingResult {
            continueWatchingCalls += nowMs
            if (continueWatchingFailures > 0) {
                continueWatchingFailures--
                throw IllegalStateException("offline")
            }
            return continueWatchingAnswer
        }

        override suspend fun removeFromPlayback(playbackId: String): WatchHistoryResult {
            removedIds += playbackId
            return removeAnswer
        }

        override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
            throw AssertionError("markWatched is not stubbed")

        override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
            throw AssertionError("unmarkWatched is not stubbed")
    }

    private class FakeSyncSource : WatchSyncSource {
        var visibleCalls: Int = 0
        var hiddenCalls: Int = 0
        var closeCalls: Int = 0

        override fun onSurfaceVisible() {
            visibleCalls++
        }

        override fun onSurfaceHidden() {
            hiddenCalls++
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeTimeSource(var nowMs: Long = 0L) : com.crispy.tv.platform.TimeSource {
        override fun nowMs(): Long = nowMs
    }

    // ---------------------------------------------------------------- fixture

    private fun entry(
        id: String = "pb-1",
        titleItemId: String = "movie-1",
        lastUpdated: Long = 1_000L,
    ): CanonicalContinueWatchingItem =
        CanonicalContinueWatchingItem(
            id = id,
            titleItemId = titleItemId,
            playbackItemId = id,
            itemType = "movie",
            title = "Title $titleItemId",
            season = null,
            episode = null,
            progressPercent = 40.0,
            lastUpdatedEpochMs = lastUpdated,
        )

    private fun hero(id: String): HomeHeroItem =
        HomeHeroItem(
            id = id,
            title = "Title $id",
            description = "Description $id",
            rating = null,
            artworkUrl = null,
            addonId = "addon",
            type = "movie",
        )

    private fun feedWith(vararg ids: String): HomePrimaryFeedLoadResult =
        HomePrimaryFeedLoadResult(heroResult = HomeHeroLoadResult(items = ids.map(::hero)))

    private class Harness(val clock: FakeTimeSource = FakeTimeSource(nowMs = 5_000L)) {
        val catalog = FakeCatalogService()
        val watchHistory = FakeWatchHistoryService()
        val keyValues = FakeKeyValueStore()
        val suppression = ContinueWatchingSuppressionStore(keyValues)
        val backendApi = RecordingBackendApi()
        val resolver = FixedBackendContextResolver(BackendContext("token", "profile"))
        val logger = RecordingAppLogger()
        val syncSource = FakeSyncSource()
        val syncFactoryCalls = mutableListOf<Triple<String, String, (WatchSyncEffect) -> Unit>>()
        var syncEffect: (WatchSyncEffect) -> Unit = {}

        fun viewModel(): HomeViewModel {
            val calendar = CalendarService(
                backendClient = backendApi,
                backendContextResolver = resolver,
                logger = logger,
            )
            val upNext = UpNextService(
                backendClient = backendApi,
                backendContextResolver = resolver,
                timeSource = clock,
                logger = logger,
            )
            val coordinator = HomeRefreshCoordinator(
                homeCatalogService = catalog,
                homeWatchActivityService = HomeWatchActivityService(),
                watchHistoryService = watchHistory,
                calendarService = calendar,
                upNextService = upNext,
                suppressionStore = suppression,
                timeSource = clock,
            )
            return HomeViewModel(
                refreshCoordinator = coordinator,
                watchHistoryService = watchHistory,
                suppressionStore = suppression,
                backendResolver = resolver,
                watchSyncFactory = { accessToken, profileId, onEffect ->
                    syncFactoryCalls += Triple(accessToken, profileId, onEffect)
                    syncEffect = onEffect
                    syncSource
                },
                timeSource = clock,
                logger = logger,
                ioDispatcher = Dispatchers.Main,
            )
        }
    }

    private fun kotlinx.coroutines.test.TestScope.runLoaded(harness: Harness): HomeViewModel {
        val viewModel = harness.viewModel()
        viewModel.ensureLoaded()
        advanceUntilIdle()
        return viewModel
    }

    // ------------------------------------------------------------------ load

    @Test
    fun `the cached snapshot paints first, then the primary load replaces it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.catalog.cachedFeed = feedWith("cached")
            harness.catalog.feed = feedWith("fresh")
            viewModel = runLoaded(harness)

            assertEquals(listOf("fresh"), viewModel.uiState.value.heroState.items.map { it.id })
            assertEquals(1, harness.catalog.primaryLoads)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a fresh cache skips the primary load`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            // The clock reads 5_000; an expiry after that is still fresh.
            harness.catalog.expiresAtMs = 9_000L
            harness.catalog.cachedFeed = feedWith("cached")
            harness.catalog.feed = feedWith("fresh")
            viewModel = runLoaded(harness)

            assertEquals(listOf("cached"), viewModel.uiState.value.heroState.items.map { it.id })
            assertEquals(0, harness.catalog.primaryLoads)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loading twice still loads once`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            viewModel = runLoaded(harness)
            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(1, harness.catalog.primaryLoads)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------- continue watching

    @Test
    fun `the continue watching rail is applied to the state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry()))
            viewModel = runLoaded(harness)

            val rail = viewModel.uiState.value.wideRailSections[CONTINUE_WATCHING_SECTION_KEY]
            assertNotNull(rail)
            assertEquals(1, (rail!!.state as RailLoadState.Ready).items.size)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a suppressed entry is filtered until its content updates`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.suppression.write(mapOf("movie-1" to 2_000L))
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry(lastUpdated = 1_000L)))
            viewModel = runLoaded(harness)

            val rail = viewModel.uiState.value.wideRailSections[CONTINUE_WATCHING_SECTION_KEY]
            assertNotNull(rail)
            assertEquals(RailLoadState.Loading, rail!!.state)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an entry updated after its suppression reappears and the suppression is dropped`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.suppression.write(mapOf("movie-1" to 2_000L))
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry(lastUpdated = 3_000L)))
            viewModel = runLoaded(harness)

            assertNotNull(viewModel.uiState.value.wideRailSections[CONTINUE_WATCHING_SECTION_KEY])
            assertTrue(harness.suppression.read().isEmpty())
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a rail that fails twice still loads on the third attempt`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.watchHistory.continueWatchingFailures = 2
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry()))
            viewModel = runLoaded(harness)

            assertEquals(3, harness.watchHistory.continueWatchingCalls.size)
            assertNotNull(viewModel.uiState.value.wideRailSections[CONTINUE_WATCHING_SECTION_KEY])
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    // ---------------------------------------------------------------- removal

    @Test
    fun `removing an item suppresses it, drops it from the rail and persists`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry()))
            viewModel = runLoaded(harness)

            viewModel.removeContinueWatchingItem(entry())
            advanceUntilIdle()

            assertEquals(listOf("pb-1"), harness.watchHistory.removedIds)
            assertEquals(mapOf("pb-1" to 5_000L, "movie-1" to 5_000L), harness.suppression.read())
            val rail = viewModel.uiState.value.wideRailSections[CONTINUE_WATCHING_SECTION_KEY]
            assertNotNull(rail)
            assertTrue((rail!!.state as RailLoadState.Ready).items.isEmpty())
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a refused removal surfaces the server message as an error event`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            harness.watchHistory.removeAnswer = WatchHistoryResult(statusMessage = "gone", accepted = false)
            harness.watchHistory.continueWatchingAnswer =
                CanonicalContinueWatchingResult(statusMessage = "", entries = listOf(entry()))
            viewModel = runLoaded(harness)

            // `errorEvents` has no replay, so the collector subscribes first.
            val error = async(start = CoroutineStart.UNDISPATCHED) { viewModel.errorEvents.first() }
            viewModel.removeContinueWatchingItem(entry())

            assertEquals("gone", error.await())
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------- sync cable

    @Test
    fun `showing the surface builds the channel from the resolved context`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            viewModel = runLoaded(harness)

            viewModel.onHomeVisible()
            advanceUntilIdle()

            assertEquals(listOf(Triple("token", "profile", harness.syncEffect)), harness.syncFactoryCalls)
            assertEquals(1, harness.syncSource.visibleCalls)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a refetch-home effect reloads the primary feed`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            viewModel = runLoaded(harness)
            viewModel.onHomeVisible()
            advanceUntilIdle()
            val loadsAfterVisible = harness.catalog.primaryLoads

            harness.syncEffect(WatchSyncEffect.RefetchHome)
            advanceUntilIdle()

            assertEquals(loadsAfterVisible + 1, harness.catalog.primaryLoads)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `hiding the surface parks the channel instead of closing it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        lateinit var viewModel: HomeViewModel
        try {
            val harness = Harness()
            viewModel = runLoaded(harness)
            viewModel.onHomeVisible()
            advanceUntilIdle()

            viewModel.onHomeHidden()

            assertEquals(1, harness.syncSource.hiddenCalls)
            assertEquals(0, harness.syncSource.closeCalls)
        } finally {
            // A viewmodel that outlives its test keeps its `HomeRefreshBus`
            // collector subscribed, and the bus is process-wide: the next test
            // class that emits (the outbox suite does on every success) resumes
            // that collector on a `Main` dispatcher that no longer exists, and
            // the failure lands in the wrong test. Cancelling the scope is what
            // `onCleared` does in production -- but a collector parked in
            // `collect` never wakes to notice, so one harmless event is emitted
            // to flush the parked collectors while `Main` is still set, and only
            // then is it reset. `RatingsChanged` is the no-op branch.
            viewModel.viewModelScope.cancel()
            HomeRefreshBus.emit(HomeRefreshEvent.RatingsChanged)
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }
}
