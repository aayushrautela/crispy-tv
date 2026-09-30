package com.crispy.tv.details

import com.crispy.tv.accounts.FixedBackendContextResolver
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ClientParentRef
import com.crispy.tv.backend.MediaExternalIds
import com.crispy.tv.backend.MetadataProductionInfoView
import com.crispy.tv.backend.MetadataSeriesEpisodesResponse
import com.crispy.tv.backend.MetadataTitleExtrasResponse
import com.crispy.tv.backend.MetadataTitleRatingsResponse
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.ai.AiInsightsResult
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.CrispySession
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.testing.RecordingAppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DetailsUseCases] reached `commonMain` in the `DetailsUseCases` port batch,
 * and this is its first coverage. The cases pin what the move changed: the
 * `Mutex`-guarded cache (hit, eviction, key separation), the AI slots
 * receiving the language tag, the `lowercase()` without a locale, and the
 * `logger` calls on the no-token branches. The pure `resolveRuntimeEpisodeTarget`
 * comes along because it has behaviour of its own.
 *
 * What is deliberately not here: the success paths of `loadExtras` and
 * `loadRatings` only map a repository answer into a wrapper with no rule of
 * their own, so a test would re-assert the compiler.
 */
class DetailsUseCasesTest {
    // ---------------------------------------------------------------- doubles

    private class RecordingCatalogRepository : CatalogRepository {
        val detailCalls = mutableListOf<String>()
        var detailAnswer: Result<MetadataTitleDetailResponse> =
            Result.failure(AssertionError("getTitleDetail is not stubbed"))
        var episodesAnswer: MetadataSeriesEpisodesResponse = MetadataSeriesEpisodesResponse(emptyList())

        override suspend fun getTitleDetail(
            accessToken: String,
            itemId: String,
        ): MetadataTitleDetailResponse {
            detailCalls += itemId
            return detailAnswer.getOrThrow()
        }

        override suspend fun getTitleExtras(
            accessToken: String,
            itemId: String,
        ): MetadataTitleExtrasResponse = throw AssertionError("getTitleExtras is not stubbed")

        override suspend fun getSeriesEpisodes(
            accessToken: String,
            seriesItemId: String,
            season: Int?,
        ): MetadataSeriesEpisodesResponse = episodesAnswer

        override suspend fun getTitleRatings(
            accessToken: String,
            profileId: String,
            itemId: String,
        ): MetadataTitleRatingsResponse = throw AssertionError("getTitleRatings is not stubbed")
    }

    private class FixedSessionRepository(
        private val session: CrispySession?,
    ) : SessionRepository {
        override suspend fun ensureValidSession(): CrispySession? = session
    }

    /**
     * The resolvers ask exactly two questions; the other eight throw loudly so
     * a new call site fails rather than quietly reading a default.
     */
    private class StubUserMediaRepository : UserMediaRepository {
        override suspend fun getTitleWatchState(
            itemId: String,
            contentType: MetadataLabMediaType,
        ): CanonicalWatchStateSnapshot? = null

        override suspend fun getCanonicalContinueWatching(
            limit: Int,
            nowMs: Long,
        ): CanonicalContinueWatchingResult = CanonicalContinueWatchingResult(statusMessage = "")

        override suspend fun getLocalWatchProgress(identity: PlaybackIdentity): WatchProgressSnapshot? = null

        override suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? =
            unused("getCanonicalWatchState")

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

    // ---------------------------------------------------------------- fixture

    private fun images(): ClientImages {
        val set = ResponsiveImageSet(low = null, medium = "m", high = null)
        return ClientImages(artwork = set, logo = set, still = set)
    }

    private fun card(
        itemId: String = "t1",
        title: String = "Title",
        imdb: String? = "tt12345",
        season: Int? = null,
        episode: Int? = null,
    ): ClientMediaCard =
        ClientMediaCard(
            itemId = itemId,
            mediaType = "movie",
            title = title,
            overview = "overview",
            year = 2020,
            releaseDate = null,
            rating = 7.5,
            maturityRating = null,
            genres = emptyList(),
            runtimeSeconds = 3_600,
            images = images(),
            progress = null,
            parent =
                if (season == null && episode == null) {
                    null
                } else {
                    ClientParentRef(
                        seriesItemId = "t1",
                        seriesTitle = "Show",
                        seasonItemId = "s1",
                        seasonNumber = season,
                        episodeNumber = episode,
                    )
                },
            providerIds = MediaExternalIds(tmdb = null, imdb = imdb, tvdb = null),
        )

    private fun titleDetail(titleCard: ClientMediaCard = card()): MetadataTitleDetailResponse =
        MetadataTitleDetailResponse(
            item = titleCard,
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

    private fun useCases(
        catalog: RecordingCatalogRepository,
        session: CrispySession? = null,
        context: BackendContext? = BackendContext(accessToken = "token", profileId = "profile"),
        contextResolver: com.crispy.tv.backend.BackendContextResolver? = null,
        logger: RecordingAppLogger = RecordingAppLogger(),
        loadCached: (String, String) -> AiInsightsResult? = { _, _ -> null },
        generate: suspend (String, String) -> AiInsightsResult = { _, _ -> AiInsightsResult(emptyList()) },
    ): DetailsUseCases =
        DetailsUseCases(
            sessionRepository = FixedSessionRepository(session),
            catalogRepository = catalog,
            userMediaRepository = StubUserMediaRepository(),
            backendContextResolver = contextResolver ?: FixedBackendContextResolver(context),
            backendApi = RecordingBackendApi(),
            logger = logger,
            cachedInsights = loadCached,
            generateInsights = generate,
        )

    // ------------------------------------------------------------------ load

    @Test
    fun `without any token the screen asks the user to sign in`() = runTest {
        val catalog = RecordingCatalogRepository()
        val result = useCases(catalog, session = null, context = null)
            .loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)

        assertEquals("Sign in to load details.", result.statusMessage)
        assertNull(result.details)
        assertTrue("no token means no backend call", catalog.detailCalls.isEmpty())
    }

    @Test
    fun `a mapped detail is served from the cache on the second load`() = runTest {
        val catalog = RecordingCatalogRepository()
        catalog.detailAnswer = Result.success(titleDetail())
        val logger = RecordingAppLogger()
        val useCases = useCases(catalog, logger = logger)

        val first = useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        assertEquals("", first.statusMessage)
        assertEquals("Title", first.details?.title)
        assertEquals("tt12345", first.details?.imdbId)

        val second = useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        assertEquals("Title", second.details?.title)
        assertEquals("the second load must not reach the backend", 1, catalog.detailCalls.size)
        assertTrue(
            "the hit is logged, which is how the cache path shows up at all",
            logger.debugs.any { (_, message) -> "CACHE HIT" in message },
        )
    }

    @Test
    fun `signing out evicts the cached entry`() = runTest {
        val catalog = RecordingCatalogRepository()
        catalog.detailAnswer = Result.success(titleDetail())
        // The cache lives on the instance, so the token has to change under
        // one instance: a resolver over a var, not two instances.
        var context: BackendContext? = BackendContext(accessToken = "token", profileId = "profile")
        val resolver = object : com.crispy.tv.backend.BackendContextResolver {
            override suspend fun resolve(): BackendContext? = context
            override fun clear() = Unit
        }
        val useCases = useCases(catalog, contextResolver = resolver)
        useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        assertEquals(1, catalog.detailCalls.size)

        // The tokenless load misses the cache (the hit needs a token), stores
        // nothing, and removes the line.
        context = null
        val signedOut = useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        assertEquals("Sign in to load details.", signedOut.statusMessage)
        assertEquals("no token means no backend call", 1, catalog.detailCalls.size)

        // Without the eviction this would serve the stale detail with one call.
        context = BackendContext(accessToken = "token", profileId = "profile")
        useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        assertEquals(2, catalog.detailCalls.size)
    }

    @Test
    fun `the cache key separates media types`() = runTest {
        val catalog = RecordingCatalogRepository()
        catalog.detailAnswer = Result.success(titleDetail())
        val useCases = useCases(catalog)

        useCases.loadScreen("t1", MetadataLabMediaType.MOVIE, null, 0L)
        useCases.loadScreen("t1", MetadataLabMediaType.SERIES, null, 0L)

        assertEquals("movie and series entries must not share a cache line", 2, catalog.detailCalls.size)
    }

    // -------------------------------------------------------------------- ai

    @Test
    fun `the cached-insights slot receives the language tag`() = runTest {
        var seenTag: String? = null
        val useCases = useCases(
            RecordingCatalogRepository(),
            loadCached = { _, tag ->
                seenTag = tag
                null
            },
        )

        useCases.loadCachedAiInsights("t1", "fr-FR")

        // The assertion reads the value the production code forwarded, the same
        // shape as the handoff-key test that caught the blanked lookup id.
        assertEquals("fr-FR", seenTag)
    }

    @Test
    fun `the generate slot receives the language tag`() = runTest {
        var seenTag: String? = null
        val useCases = useCases(
            RecordingCatalogRepository(),
            generate = { _, tag ->
                seenTag = tag
                AiInsightsResult(emptyList())
            },
        )

        useCases.generateAiInsights("t1", "de-DE")

        assertEquals("de-DE", seenTag)
    }

    // --------------------------------------------------------------- runtime

    @Test
    fun `the runtime target prefers the exact season and episode match`() = runTest {
        val useCases = useCases(RecordingCatalogRepository())
        val videos = listOf(
            episodeVideo("e1", season = 2, episode = 7),
            episodeVideo("e2", season = 2, episode = 8),
        )

        val target = useCases.resolveRuntimeEpisodeTarget(
            videos,
            RuntimeDetailsEntry(seasonNumber = 2, episodeNumber = 8),
        )

        assertEquals("e2", target?.episodeId)
        assertEquals(2, target?.seasonNumber)
    }

    @Test
    fun `the runtime target falls back to the absolute number`() = runTest {
        val useCases = useCases(RecordingCatalogRepository())
        val videos = listOf(
            episodeVideo("e1", season = 2, episode = 7, absolute = 40),
            episodeVideo("e2", season = 3, episode = 1, absolute = 41),
        )

        val target = useCases.resolveRuntimeEpisodeTarget(
            videos,
            RuntimeDetailsEntry(seasonNumber = 9, episodeNumber = 9, absoluteEpisodeNumber = 41),
        )

        assertEquals("e2", target?.episodeId)
    }

    @Test
    fun `the runtime target is null without an entry, without videos, or without a match`() = runTest {
        val useCases = useCases(RecordingCatalogRepository())
        val videos = listOf(episodeVideo("e1", season = 2, episode = 7))

        assertNull(useCases.resolveRuntimeEpisodeTarget(videos, null))
        assertNull(useCases.resolveRuntimeEpisodeTarget(emptyList(), RuntimeDetailsEntry(seasonNumber = 2)))
        assertNull(
            "season 3 is absent and no absolute number is given",
            useCases.resolveRuntimeEpisodeTarget(videos, RuntimeDetailsEntry(seasonNumber = 3, episodeNumber = 1)),
        )
    }

    private fun episodeVideo(
        id: String,
        season: Int?,
        episode: Int?,
        absolute: Int? = null,
    ): com.crispy.tv.addons.model.MediaVideo =
        com.crispy.tv.addons.model.MediaVideo(
            id = id,
            title = id,
            season = season,
            episode = episode,
            released = null,
            overview = null,
            thumbnailUrl = null,
            absoluteEpisodeNumber = absolute,
        )

    // ------------------------------------------------------------------ misc

    @Test
    fun `ensureImdbId lowercases a tt-prefixed id and leaves the rest alone`() = runTest {
        val useCases = useCases(RecordingCatalogRepository())
        fun details(imdbId: String?) = MediaDetails(
            id = "t1",
            imdbId = imdbId,
            itemType = "movie",
            title = "Title",
            artworkUrl = null,
            description = null,
            year = null,
            runtime = null,
            certification = null,
            rating = null,
            addonId = "backend",
        )

        assertEquals("tt12345", useCases.ensureImdbId(details("TT12345"), MetadataLabMediaType.MOVIE).imdbId)
        assertEquals("nm123", useCases.ensureImdbId(details("nm123"), MetadataLabMediaType.MOVIE).imdbId)
        assertNull(useCases.ensureImdbId(details(null), MetadataLabMediaType.MOVIE).imdbId)
    }

    @Test
    fun `the no-token branches stay quiet and say so`() = runTest {
        val logger = RecordingAppLogger()
        val useCases = useCases(RecordingCatalogRepository(), session = null, context = null, logger = logger)

        assertNull(useCases.loadExtras("t1").titleExtras)
        assertNull("a blank profile filters the ratings call", useCases.loadRatings("t1").titleRatings)
        assertTrue(useCases.loadAllEpisodes("t1").isEmpty())
        val season = useCases.loadSeasonEpisodes(
            2,
            MediaDetails(
                id = "t1",
                itemId = "t1",
                imdbId = null,
                itemType = "series",
                title = "Show",
                artworkUrl = null,
                description = null,
                year = null,
                runtime = null,
                certification = null,
                rating = null,
                addonId = "backend",
            ),
        )
        assertEquals("Sign in to load episodes.", season.errorMessage)
        val warned = logger.warns.map { it.second }
        assertTrue(
            "the skipped loads warn",
            warned.any { "title extras" in it } && warned.any { "series episodes" in it },
        )
    }

    @Test
    fun `a blank profile filters the ratings call even with a token`() = runTest {
        val catalog = RecordingCatalogRepository()
        val useCases = useCases(
            catalog,
            context = BackendContext(accessToken = "token", profileId = "  "),
        )

        assertNull(useCases.loadRatings("t1").titleRatings)
    }

    @Test
    fun `season episodes map the episode cards`() = runTest {
        val catalog = RecordingCatalogRepository()
        catalog.episodesAnswer =
            MetadataSeriesEpisodesResponse(
                listOf(
                    card(itemId = "e1", title = "One", imdb = null, season = 2, episode = 1),
                    card(itemId = "e2", title = "Two", imdb = null, season = 2, episode = 2),
                ),
            )
        val useCases = useCases(catalog)
        val details = MediaDetails(
            id = "t1",
            itemId = "t1",
            imdbId = null,
            itemType = "series",
            title = "Show",
            artworkUrl = null,
            description = null,
            year = null,
            runtime = null,
            certification = null,
            rating = null,
            addonId = "backend",
        )

        val result = useCases.loadSeasonEpisodes(2, details)

        assertNull(result.errorMessage)
        assertEquals(listOf("e1", "e2"), result.videos.map { it.id })
        assertEquals(2, result.effectiveSeasonNumber)
    }

    @Test
    fun `season episodes without an item id stop before the backend`() = runTest {
        val catalog = RecordingCatalogRepository()
        val useCases = useCases(catalog)
        val details = MediaDetails(
            id = "t1",
            itemId = "  ",
            imdbId = null,
            itemType = "series",
            title = "Show",
            artworkUrl = null,
            description = null,
            year = null,
            runtime = null,
            certification = null,
            rating = null,
            addonId = "backend",
        )

        val result = useCases.loadSeasonEpisodes(2, details)

        assertEquals("No episodes found for this season.", result.errorMessage)
    }
}
