package com.crispy.tv.library

import com.crispy.tv.accounts.FixedBackendContextResolver
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.WatchGenerationsResponse
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.optimistic.MutationExecutor
import com.crispy.tv.optimistic.MutationResult
import com.crispy.tv.optimistic.PendingMutationStore
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.watchhistory.sync.WatchSyncSource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The per-section pager map is the thing that makes the tab switch animation
 * possible: `AnimatedContent` needs the old section's data still present while
 * the new section loads, so a section switch must not kill its pager. These
 * cases pin the retention without collecting a paging runtime -- the identity
 * of the returned flow is the whole contract.
 */
class LibrarySectionPagersTest {
    private class StubUserMediaRepository : UserMediaRepository {
        override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? = null

        override suspend fun getTitleWatchState(
            itemId: String,
            contentType: MetadataLabMediaType,
        ): CanonicalWatchStateSnapshot? = null

        override suspend fun getCanonicalContinueWatching(
            limit: Int,
            nowMs: Long,
        ): CanonicalContinueWatchingResult = CanonicalContinueWatchingResult(statusMessage = "")

        override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? = null

        override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryResult =
            throw AssertionError("markWatched is not stubbed")

        override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryResult =
            throw AssertionError("unmarkWatched is not stubbed")

        override suspend fun setInWatchlist(
            request: WatchHistoryRequest,
            inWatchlist: Boolean,
        ): WatchHistoryResult = throw AssertionError("setInWatchlist is not stubbed")

        override suspend fun setTitleInWatchlist(
            itemId: String,
            inWatchlist: Boolean,
        ): WatchHistoryResult = throw AssertionError("setTitleInWatchlist is not stubbed")

        override suspend fun setLiked(
            request: WatchHistoryRequest,
            liked: Boolean?,
        ): WatchHistoryResult = throw AssertionError("setLiked is not stubbed")

        override suspend fun setTitleLiked(
            itemId: String,
            liked: Boolean?,
        ): WatchHistoryResult = throw AssertionError("setTitleLiked is not stubbed")
    }

    private class StubMutationStore : PendingMutationStore {
        override suspend fun loadAll(): List<UserMutation> = emptyList()

        override suspend fun saveAll(mutations: List<UserMutation>) = Unit
    }

    private class StubMutationExecutor : MutationExecutor {
        override suspend fun execute(mutation: UserMutation): MutationResult = MutationResult(success = true)
    }

    private class StubSyncSource : WatchSyncSource {
        override fun onSurfaceVisible() = Unit

        override fun onSurfaceHidden() = Unit

        override fun close() = Unit
    }

    private class GenerationsBackendApi : RecordingBackendApi() {
        override suspend fun getWatchGenerations(
            accessToken: String,
            profileId: String,
        ): WatchGenerationsResponse =
            WatchGenerationsResponse(
                continueWatchingMs = null,
                historyMs = null,
                watchlistMs = null,
                ratingsMs = null,
                homeMs = null,
            )
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        // The viewmodel subscribes to the global `HomeRefreshBus` in `init`,
        // and that subscription outlives the test unless the scope is
        // cancelled: a leaked collector would still be resumed by a later
        // test's bus emission, on a `Main` dispatcher this test has already
        // reset. Cancelling first unsubscribes while `Main` is still installed.
        // (`ViewModel.clear()` would be the usual call, but it is `internal`
        // in common; cancelling the public scope is the same cancellation.)
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    private val vms = mutableListOf<LibraryViewModel>()

    private fun viewModel(scope: CoroutineScope): LibraryViewModel {
        val outbox =
            UserMutationOutbox(
                store = StubMutationStore(),
                executor = StubMutationExecutor(),
                scope = scope,
                clock = { 0L },
            )
        return LibraryViewModel(
            backend = GenerationsBackendApi(),
            backendContextResolver = FixedBackendContextResolver(BackendContext(accessToken = "token", profileId = "profile")),
            userMediaRepository = StubUserMediaRepository(),
            outbox = outbox,
            libraryCache = FakeLibraryDiskCache(),
            watchSyncFactory = { _, _, _ -> StubSyncSource() },
            clock = { 0L },
            newMutationId = { "mid" },
            ioDispatcher = Dispatchers.Main,
        ).also { vms += it }
    }

    @Test
    fun `same section returns the same pager on repeat calls`() =
        runTest {
            val vm = viewModel(this)

            assertSame(vm.itemsFor(LIBRARY_SECTION_HISTORY), vm.itemsFor(LIBRARY_SECTION_HISTORY))
        }

    @Test
    fun `different sections return different pagers`() =
        runTest {
            val vm = viewModel(this)

            assertNotSame(vm.itemsFor(LIBRARY_SECTION_HISTORY), vm.itemsFor(LIBRARY_SECTION_WATCHLIST))
        }

    @Test
    fun `returning to a section keeps its original pager`() =
        runTest {
            val vm = viewModel(this)
            val first = vm.itemsFor(LIBRARY_SECTION_HISTORY)
            vm.itemsFor(LIBRARY_SECTION_WATCHLIST)

            assertSame(first, vm.itemsFor(LIBRARY_SECTION_HISTORY))
        }
}
