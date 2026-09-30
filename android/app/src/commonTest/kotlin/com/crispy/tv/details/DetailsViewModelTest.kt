package com.crispy.tv.details

import com.crispy.tv.accounts.FixedBackendContextResolver
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ClientParentRef
import com.crispy.tv.backend.MediaExternalIds
import com.crispy.tv.backend.MetadataProductionInfoView
import com.crispy.tv.backend.MetadataSeriesEpisodesResponse
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.backend.MetadataTitleExtrasResponse
import com.crispy.tv.backend.MetadataTitleRatingsResponse
import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.images.ResponsiveImageSet
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
import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.addons.streams.ProviderStreamsResult
import com.crispy.tv.addons.streams.StreamProviderDescriptor
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.testing.FakeStreamResolver
import com.crispy.tv.testing.RecordingAppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DetailsViewModel] reached `commonMain` in the `DetailsViewModel` port batch,
 * and this is its first coverage. The move reshaped the constructor around ports
 * and slots, so the cases pin the slots carrying the right values rather than
 * the wiring itself:
 *
 * - [viewModel] installs `Dispatchers.setMain` **before** constructing the
 *   viewmodel, and builds ONE dispatcher used as BOTH `setMain` and
 *   `ioDispatcher`. The class hops to IO around every load; `setMain` alone
 *   would leave the real thread pool in place and the assertions racing it.
 * - The outbox is never started: `enqueue` commits to memory without a running
 *   processor, so toggle assertions read `mutationsForItem` with no scheduler.
 * - `navigationEvents` is a `SharedFlow` with no replay, so the player test
 *   subscribes with `async(CoroutineStart.UNDISPATCHED)` before triggering.
 * - The stash assertion reads the lookup id the production code computed
 *   (`stashedFor`), not a key the stub invented — a stub cannot be evidence
 *   about the value it replaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelTest {
    // ---------------------------------------------------------------- doubles

    private class RecordingCatalogRepository : CatalogRepository {
        var detailAnswer: Result<MetadataTitleDetailResponse> =
            Result.failure(AssertionError("getTitleDetail is not stubbed"))

        override suspend fun getTitleDetail(
            accessToken: String,
            itemId: String,
        ): MetadataTitleDetailResponse = detailAnswer.getOrThrow()

        override suspend fun getTitleExtras(
            accessToken: String,
            itemId: String,
        ): MetadataTitleExtrasResponse = throw AssertionError("getTitleExtras is not stubbed")

        override suspend fun getSeriesEpisodes(
            accessToken: String,
            seriesItemId: String,
            season: Int?,
        ): MetadataSeriesEpisodesResponse = MetadataSeriesEpisodesResponse(emptyList())

        override suspend fun getTitleRatings(
            accessToken: String,
            profileId: String,
            itemId: String,
        ): MetadataTitleRatingsResponse = throw AssertionError("getTitleRatings is not stubbed")
    }

    private class FixedSessionRepository : SessionRepository {
        override suspend fun ensureValidSession(): com.crispy.tv.domain.repository.CrispySession? = null
    }

    private class StubUserMediaRepository : UserMediaRepository {
        var progressAnswer: WatchProgressSnapshot? = null
        val continueWatchingNowMs = mutableListOf<Long>()

        override suspend fun getTitleWatchState(
            itemId: String,
            contentType: MetadataLabMediaType,
        ): CanonicalWatchStateSnapshot? = null

        override suspend fun getCanonicalContinueWatching(
            limit: Int,
            nowMs: Long,
        ): CanonicalContinueWatchingResult {
            continueWatchingNowMs += nowMs
            return CanonicalContinueWatchingResult(statusMessage = "")
        }

        override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? = progressAnswer

        override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? =
            throw AssertionError("getCanonicalWatchState is not stubbed")

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

    private class RecordingStore : PendingMutationStore {
        private var stored: List<UserMutation> = emptyList()

        override suspend fun loadAll(): List<UserMutation> = stored

        override suspend fun saveAll(mutations: List<UserMutation>) {
            stored = mutations
        }
    }

    private class RecordingExecutor : MutationExecutor {
        val calls = mutableListOf<UserMutation>()

        override suspend fun execute(mutation: UserMutation): MutationResult {
            calls += mutation
            return MutationResult(success = true)
        }
    }

    // ---------------------------------------------------------------- fixture

    private val catalog = RecordingCatalogRepository()
    private val userMedia = StubUserMediaRepository()
    private val streamResolver = FakeStreamResolver()
    private val cachedTags = mutableListOf<String>()
    private val logger = RecordingAppLogger()
    private val outboxHolder = mutableListOf<UserMutationOutbox>()
    private var nowMs = 7_000L
    private var nextId = 0
    private val generatedTags = mutableListOf<String>()
    private var cachedAnswer: AiInsightsResult? = null
    private var stashedFor: String? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun images(): ClientImages {
        val set = ResponsiveImageSet(low = null, medium = "m", high = null)
        return ClientImages(artwork = set, logo = set, still = set)
    }

    private fun card(): ClientMediaCard =
        ClientMediaCard(
            itemId = "t1",
            mediaType = "movie",
            title = "Title",
            overview = "overview",
            year = 2020,
            releaseDate = null,
            rating = 7.5,
            maturityRating = null,
            genres = emptyList(),
            runtimeSeconds = 3_600,
            images = images(),
            progress = null,
            parent = null,
            providerIds = MediaExternalIds(tmdb = null, imdb = "tt12345", tvdb = null),
        )

    private fun titleDetail(): MetadataTitleDetailResponse =
        MetadataTitleDetailResponse(
            item = card(),
            nextEpisode = null,
            videos = emptyList(),
            cast = emptyList(),
            directors = emptyList(),
            creators = emptyList(),
            production =
                MetadataProductionInfoView(
                    originalLanguage = null,
                    originCountries = emptyList(),
                    spokenLanguages = emptyList(),
                    productionCountries = emptyList(),
                    companies = emptyList(),
                    networks = emptyList(),
                ),
        )

    private fun useCases(context: BackendContext? = BackendContext(accessToken = "token", profileId = "profile")): DetailsUseCases =
        DetailsUseCases(
            sessionRepository = FixedSessionRepository(),
            catalogRepository = catalog,
            userMediaRepository = userMedia,
            backendContextResolver = FixedBackendContextResolver(context),
            backendApi = RecordingBackendApi(),
            logger = logger,
            cachedInsights = { itemId, tag ->
                cachedTags += "$itemId:$tag"
                cachedAnswer
            },
            generateInsights = { itemId, tag ->
                generatedTags += "$itemId:$tag"
                AiInsightsResult(emptyList())
            },
        )

    private fun viewModel(
        context: BackendContext? = BackendContext(accessToken = "token", profileId = "profile"),
        itemType: String = "movie",
        resolver: StreamResolver = streamResolver,
    ): DetailsViewModel {
        val outbox =
            UserMutationOutbox(
                store = RecordingStore(),
                executor = RecordingExecutor(),
                scope = CoroutineScope(UnconfinedTestDispatcher()),
                clock = { nowMs },
            )
        outboxHolder += outbox
        return DetailsViewModel(
            itemId = "t1",
            itemType = itemType,
            runtimeEntry = null,
            detailsUseCases = useCases(context),
            outbox = outbox,
            streamResolver = resolver,
            logger = logger,
            getMetadataItemDetail = { _, _ -> titleDetail() },
            sessionTokenProvider = { "token" },
            pluginStreamLoader = null,
            stashHandoff = { _, lookupId ->
                stashedFor = lookupId
                "handoff-key"
            },
            newMutationId = { "mid-${++nextId}" },
            languageTagProvider = { "fr-FR" },
            clock = { nowMs },
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun loaded(resolver: StreamResolver = streamResolver): DetailsViewModel {
        catalog.detailAnswer = Result.success(titleDetail())
        return viewModel(resolver = resolver)
    }

    private fun outbox(): UserMutationOutbox = outboxHolder.last()

    // ---------------------------------------------------------------- reload

    @Test
    fun `reload publishes the loaded title and clears loading`() = runTest {
        val vm = loaded()

        assertEquals("Title", vm.uiState.value.details?.title)
        assertEquals("tt12345", vm.uiState.value.details?.imdbId)
        assertEquals(false, vm.uiState.value.isLoading)
        assertEquals("", vm.uiState.value.statusMessage)
    }

    @Test
    fun `reload hands its clock to the repository through the load`() = runTest {
        catalog.detailAnswer = Result.success(titleDetail())
        viewModel(itemType = "series")

        // The clock slot is one of the port's changes; the only seam that can
        // see it is the timestamp the repository receives.
        assertEquals(listOf(7_000L), userMedia.continueWatchingNowMs)
    }

    @Test
    fun `the cached insights load carries the provider tag`() = runTest {
        loaded()

        assertEquals(listOf("t1:fr-FR"), cachedTags)
    }

    @Test
    fun `without any token the screen asks the user to sign in`() = runTest {
        val vm = viewModel(context = null)

        assertEquals("Sign in to load details.", vm.uiState.value.statusMessage)
        assertNull(vm.uiState.value.details)
    }

    // ---------------------------------------------------------------- toggles

    @Test
    fun `toggleWatchlist enqueues a watchlist mutation with the slot id and clock`() = runTest {
        val vm = loaded()

        vm.toggleWatchlist()

        val mutation = outbox().mutationsForItem("t1").single() as WatchlistMutation
        assertEquals("mid-1", mutation.id)
        assertEquals(7_000L, mutation.createdAtMs)
        assertEquals(7_000L, mutation.nextAttemptAtMs)
        assertEquals(true, mutation.desired)
        assertEquals(MutationStatus.Pending, mutation.status)
    }

    @Test
    fun `toggleWatched enqueues a title mutation with the mapped content type`() = runTest {
        val vm = loaded()

        vm.toggleWatched()

        val mutation = outbox().mutationsForItem("t1").single() as TitleWatchedMutation
        assertEquals("mid-1", mutation.id)
        assertEquals(true, mutation.desired)
    }

    @Test
    fun `toggleEpisodeWatched refuses incomplete metadata without enqueueing`() = runTest {
        val vm = loaded()
        val video =
            MediaVideo(
                id = "v1",
                title = "Episode",
                season = null,
                episode = 7,
                released = null,
                overview = null,
                thumbnailUrl = null,
                lookupId = null,
                absoluteEpisodeNumber = null,
            )

        vm.toggleEpisodeWatched(video)

        assertEquals("Episode metadata is incomplete.", vm.uiState.value.statusMessage)
        assertTrue(outbox().mutationsForItem("t1").isEmpty())
    }

    @Test
    fun `toggleEpisodeWatched keys the entity on title season and episode`() = runTest {
        val vm = loaded()
        val video =
            MediaVideo(
                id = "v1",
                title = "Episode",
                season = 2,
                episode = 7,
                released = null,
                overview = null,
                thumbnailUrl = null,
                lookupId = null,
                absoluteEpisodeNumber = null,
            )

        vm.toggleEpisodeWatched(video)

        val mutation = outbox().mutationsForItem("t1").single() as EpisodeWatchedMutation
        assertEquals("t1#S2:E7", mutation.entityId)
        assertEquals(2, mutation.season)
        assertEquals(7, mutation.episode)
        assertEquals("v1", mutation.videoId)
    }

    @Test
    fun `toggleSeasonWatched and setLiked enqueue their own shapes`() = runTest {
        val vm = loaded()
        nowMs = 9_000L

        vm.toggleSeasonWatched(seasonItemId = "s1", seasonNumber = 3)
        vm.setLiked(true)

        val season = outbox().mutationsForItem("t1").filterIsInstance<SeasonWatchedMutation>().single()
        assertEquals("s1", season.entityId)
        assertEquals(3, season.seasonNumber)
        assertEquals(9_000L, season.createdAtMs)
        val rating = outbox().mutationsForItem("t1").filterIsInstance<RatingMutation>().single()
        assertEquals(true, rating.desired)
        assertEquals("mid-2", rating.id)
    }

    @Test
    fun `toggles without details enqueue nothing`() = runTest {
        val vm = viewModel()

        vm.toggleWatchlist()
        vm.toggleWatched()
        vm.setLiked(false)

        assertTrue(outbox().mutationsForItem("t1").isEmpty())
    }

    // ---------------------------------------------------------------- AI

    @Test
    fun `a cached insight opens the story without generating`() = runTest {
        cachedAnswer = AiInsightsResult(emptyList())
        val vm = loaded()

        vm.onAiInsightsClick()

        assertEquals(true, vm.uiState.value.aiStoryVisible)
        assertTrue(generatedTags.isEmpty())
    }

    @Test
    fun `without a cache the click generates with the provider tag`() = runTest {
        val vm = loaded()

        vm.onAiInsightsClick()

        assertEquals(listOf("t1:fr-FR"), generatedTags)
        assertEquals(false, vm.uiState.value.aiIsLoading)
        assertEquals(true, vm.uiState.value.aiStoryVisible)
    }

    // ---------------------------------------------------------------- player

    @Test
    fun `selecting a stream without a playable source warns and stays put`() = runTest {
        val vm = loaded()
        val stream =
            AddonStream(
                providerId = "provider-1",
                providerName = "Provider",
                stableKey = "stable-1",
            )

        vm.onStreamSelected(stream)

        assertEquals("Selected stream has no playable source.", vm.uiState.value.statusMessage)
        assertNull(stashedFor)
        assertTrue(logger.warns.any { it.second.contains("without playable source") })
    }

    @Test
    fun `selecting a playable stream stashes the resolved lookup id and navigates`() = runTest {
        val vm = loaded()
        val stream =
            AddonStream(
                providerId = "provider-1",
                providerName = "Provider",
                name = "Provider",
                url = "https://example.invalid/stream",
                stableKey = "stable-1",
            )
        val navigation = async(start = CoroutineStart.UNDISPATCHED) { vm.navigationEvents.first() }

        vm.onStreamSelected(stream)

        val event = navigation.await() as DetailsNavigationEvent.OpenPlayer
        assertEquals("handoff-key", event.chosenStreamHandoffKey)
        assertEquals("stable-1", event.chosenStreamStableKey)
        // The stash must receive the id this screen resolved — a blank id here
        // would mean the handoff can never be consumed by the player.
        assertEquals("tt12345", stashedFor)
        assertEquals(0L, event.resumePositionMs)
    }

    @Test
    fun `opening the same title twice resolves once and reshows`() = runTest {
        val vm = loaded()

        vm.onOpenStreamSelector()
        vm.onOpenStreamSelector()

        // The short-circuit reshows instead of resolving again; two resolves
        // would mean the guard stopped seeing the open surface.
        assertEquals(1, streamResolver.resolveCalls.size)
        assertEquals(true, vm.coordinator.state.value.visible)
    }

    // A resolver that parks its answer behind a gate, so the test can
    // observe the window where the lookup id is set but no provider has
    // reported yet. Delegates everything else to the shared fake.
    private class GatedResolver(
        private val delegate: FakeStreamResolver,
        private val gate: CompletableDeferred<Unit>,
    ) : StreamResolver {
        /** Entries parked at the gate, counted before awaiting. */
        val resolveEntries = mutableListOf<StreamLookupTarget>()

        override suspend fun resolve(
            target: StreamLookupTarget,
            onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
            onProviderResult: ((ProviderStreamsResult) -> Unit)?,
        ): List<ProviderStreamsResult> {
            resolveEntries += target
            gate.await()
            return delegate.resolve(target, onProvidersResolved, onProviderResult)
        }

        override suspend fun cachedStreams(target: StreamLookupTarget): List<ProviderStreamsResult>? =
            delegate.cachedStreams(target)

        override suspend fun loadProviderStreams(
            mediaType: com.crispy.tv.player.MetadataLabMediaType,
            lookupId: String,
            providerId: String,
        ): ProviderStreamsResult? = delegate.loadProviderStreams(mediaType, lookupId, providerId)

        override suspend fun fetchAddonSubtitles(
            mediaType: com.crispy.tv.player.MetadataLabMediaType,
            lookupId: String,
        ): List<com.crispy.tv.addons.streams.AddonSubtitle> =
            delegate.fetchAddonSubtitles(mediaType, lookupId)
    }

    @Test
    fun `reopening while the first resolve is still in flight resolves again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = GatedResolver(streamResolver, gate)
        val vm = loaded(resolver = gated)

        vm.onOpenStreamSelector()
        runCurrent()
        // The lookup id is set synchronously by open, but the resolve is
        // parked at the gate, so providers are still empty here. The guard
        // must see the empty providers and resolve again; reshowing would
        // strand the surface on a load that never lands.
        assertEquals("tt12345", vm.coordinator.state.value.lookupId)
        assertEquals(true, vm.coordinator.state.value.providers.isEmpty())

        vm.onOpenStreamSelector()
        runCurrent()

        assertEquals(2, gated.resolveEntries.size)
        gate.complete(Unit)
    }

    @Test
    fun `a progress inside the resumable band resumes from the position`() = runTest {
        userMedia.progressAnswer = WatchProgressSnapshot(
            currentTimeSeconds = 2_400.0,
            durationSeconds = 6_000.0,
            lastUpdatedEpochMs = 0L,
        )
        val vm = loaded()
        val stream =
            AddonStream(
                providerId = "provider-1",
                providerName = "Provider",
                url = "https://example.invalid/stream",
                stableKey = "stable-1",
            )
        val navigation = async(start = CoroutineStart.UNDISPATCHED) { vm.navigationEvents.first() }

        vm.onStreamSelected(stream)

        assertEquals(2_400_000L, (navigation.await() as DetailsNavigationEvent.OpenPlayer).resumePositionMs)
    }

    @Test
    fun `a progress outside the resumable band resumes from the start`() = runTest {
        for (current in listOf(30.0, 5_760.0)) {
            userMedia.progressAnswer = WatchProgressSnapshot(
                currentTimeSeconds = current,
                durationSeconds = 6_000.0,
                lastUpdatedEpochMs = 0L,
            )
            val vm = loaded()
            val stream =
                AddonStream(
                    providerId = "provider-1",
                    providerName = "Provider",
                    url = "https://example.invalid/stream",
                    stableKey = "stable-1",
                )
            val navigation = async(start = CoroutineStart.UNDISPATCHED) { vm.navigationEvents.first() }

            vm.onStreamSelected(stream)

            assertEquals(0L, (navigation.await() as DetailsNavigationEvent.OpenPlayer).resumePositionMs)
        }
    }

    @Test
    fun `dismissing the selector clears the status message`() = runTest {
        val vm = loaded()

        vm.onDismissStreamSelector()

        assertEquals("", vm.uiState.value.statusMessage)
        assertEquals(false, vm.coordinator.state.value.visible)
    }

    @Test
    fun `opening the selector targets the resolved lookup id`() = runTest {
        val vm = loaded()

        vm.onOpenStreamSelector()

        assertEquals("tt12345", vm.coordinator.state.value.lookupId)
        assertEquals(true, vm.coordinator.state.value.visible)
    }
}
