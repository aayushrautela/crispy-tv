package com.crispy.tv.details

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.testing.StubUserMediaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Covers the CTA decision in [WatchCtaResolver.resolveWatchCta], reached through the same
 * `resolveProviderState` + `resolveWatchCta` pair `DetailsUseCases` calls.
 *
 * The arm this suite exists for is the rewatch one: for its first life the resolver had
 * only `CONTINUE` and a hardcoded `WATCH` fallback, so a title whose server watch flag was
 * set rendered "Watch now". The web app derives the same label from `played && !isContinue`
 * and these cases pin that precedence: a progress inside the continue window beats the
 * played flag, the played flag beats the plain watch label.
 */
class WatchCtaResolverTest {
    private val nowMs = 1_790_769_600_000L
    private val userMedia = StubUserMediaRepository()

    private fun details(
        itemType: String,
        itemId: String = "t1",
        runtime: String? = null,
        videos: List<MediaVideo> = emptyList(),
    ) = MediaDetails(
        id = itemId,
        itemId = itemId,
        imdbId = "tt12345",
        itemType = itemType,
        title = "Title",
        artworkUrl = null,
        description = null,
        year = null,
        runtime = runtime,
        certification = null,
        rating = null,
        addonId = "backend",
        videos = videos,
    )

    private fun snapshot(
        isWatched: Boolean,
        watchedAtEpochMs: Long? = null,
        progressPercent: Double? = null,
    ) = CanonicalWatchStateSnapshot(
        isWatched = isWatched,
        watchedAtEpochMs = watchedAtEpochMs,
        isInWatchlist = false,
        liked = null,
        progressPercent = progressPercent,
    )

    private suspend fun resolve(
        requestedMediaType: MetadataLabMediaType,
        details: MediaDetails,
    ): WatchCtaResolver.Resolution {
        val resolver = WatchCtaResolver(userMedia, requestedMediaType)
        val providerState = resolver.resolveProviderState(details, details.itemId ?: details.id)
        return resolver.resolveWatchCta(details, providerState, nowMs)
    }

    @Test
    fun aPlayedMovieWithNoProgressResolvesToRewatch() = runTest {
        // The case the fallback used to swallow: `played` with nothing left to continue.
        userMedia.titleWatchStateAnswer = snapshot(isWatched = true, watchedAtEpochMs = 1_700_000_000_000L)

        val resolution = resolve(MetadataLabMediaType.MOVIE, details("movie", runtime = "1 h 40 min"))

        assertEquals(WatchCtaKind.REWATCH, resolution.watchCta.kind)
        assertEquals("Rewatch", resolution.watchCta.label)
        assertEquals(WatchCtaIcon.REPLAY, resolution.watchCta.icon)
        assertEquals(1_700_000_000_000L, resolution.watchCta.lastWatchedAtEpochMs)
        assertNull(resolution.watchCta.remainingMinutes)
        assertNull(resolution.continueVideoId)
        // A movie's resume state lives on the title; the continue list is a series tool.
        assertEquals(emptyList(), userMedia.continueWatchingNowMs)
    }

    @Test
    fun aPlayedMovieInsideTheContinueWindowStillContinues() = runTest {
        // `played && !isContinue`, not just `played`: the user started rewatching, and a
        // 40% progress is a continue, not a rewatch offer.
        userMedia.titleWatchStateAnswer = snapshot(isWatched = true, progressPercent = 40.0)

        val resolution = resolve(MetadataLabMediaType.MOVIE, details("movie"))

        assertEquals(WatchCtaKind.CONTINUE, resolution.watchCta.kind)
        assertEquals("Continue", resolution.watchCta.label)
    }

    @Test
    fun aPlayedMoviePastCompletionResolvesToRewatch() = runTest {
        // 92% is outside the 2-85% continue window, so the entry the user actually sees
        // is the played flag -- the exact state that previously rendered "Watch now".
        userMedia.titleWatchStateAnswer = snapshot(isWatched = true, progressPercent = 92.0)

        val resolution = resolve(MetadataLabMediaType.MOVIE, details("movie"))

        assertEquals(WatchCtaKind.REWATCH, resolution.watchCta.kind)
    }

    @Test
    fun anUnwatchedMovieKeepsTheWatchLabel() = runTest {
        userMedia.titleWatchStateAnswer = snapshot(isWatched = false)

        val resolution = resolve(MetadataLabMediaType.MOVIE, details("movie"))

        assertEquals(WatchCtaKind.WATCH, resolution.watchCta.kind)
        assertEquals("Watch now", resolution.watchCta.label)
    }

    @Test
    fun aPlayedSeriesWithoutAContinueEntryResolvesToRewatch() = runTest {
        // A series consults the continue list first and finds nothing usable, so the
        // played flag decides.
        userMedia.titleWatchStateAnswer = snapshot(isWatched = true, watchedAtEpochMs = 1_700_000_000_000L)

        val resolution = resolve(MetadataLabMediaType.SERIES, details("series"))

        assertEquals(WatchCtaKind.REWATCH, resolution.watchCta.kind)
        assertEquals(listOf(nowMs), userMedia.continueWatchingNowMs)
    }

    @Test
    fun aPlayedSeriesOnAContinueEntryStillContinues() = runTest {
        userMedia.titleWatchStateAnswer = snapshot(isWatched = true)
        userMedia.continueWatchingAnswer = CanonicalContinueWatchingResult(
            statusMessage = "",
            entries = listOf(
                CanonicalContinueWatchingItem(
                    id = "t1",
                    titleItemId = "t1",
                    playbackItemId = "t1",
                    itemType = "series",
                    title = "Title",
                    season = 1,
                    episode = 2,
                    progressPercent = 30.0,
                    lastUpdatedEpochMs = nowMs,
                ),
            ),
        )

        val resolution = resolve(MetadataLabMediaType.SERIES, details("series"))

        assertEquals(WatchCtaKind.CONTINUE, resolution.watchCta.kind)
        assertEquals("Continue S1:E2", resolution.watchCta.label)
    }
}
