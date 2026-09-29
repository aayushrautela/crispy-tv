package com.crispy.tv.home

import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.player.CanonicalContinueWatchingItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [UpNextService]'s own rules, and nothing else.
 *
 * The service resolves a profile, reads a page, drops the entries that cannot
 * become a row, and hands the rest to the shared rail projection. Each of those
 * has a failure mode worth pinning: a missing profile is a sign-in prompt and not
 * an error state, a read failure is reported without the cause leaking into the
 * message the user sees, and a missing timestamp has to fall back to the injected
 * clock rather than a second reading of the system clock.
 */
class UpNextServiceTest {

    private val backend = RecordingBackendApi()
    private val resolver = FixedBackendContextResolver(null)
    private val logger = RecordingAppLogger()
    private val clock = FixedTimeSource(CLOCK)

    private fun service() = UpNextService(
        backendClient = backend,
        backendContextResolver = resolver,
        timeSource = clock,
        logger = logger,
    )

    private fun signedIn() {
        resolver.answerWith(accessToken = ACCESS_TOKEN, profileId = PROFILE_ID)
    }

    private fun row(
        show: ClientMediaCard? = mediaCard("show-1", title = "The Series"),
        nextEpisode: ClientMediaCard? = mediaCard(
            itemId = "ep-1",
            title = "The One",
            parent = parentRef("show-1", seasonNumber = 1, episodeNumber = 2),
        ),
        lastInteractedAt: String? = "2026-01-01T00:00:00Z",
    ) = upNextItem(
        show = show,
        nextEpisode = nextEpisode,
        nextEpisodeAirDate = null,
        lastInteractedAt = lastInteractedAt,
        reason = null,
    )

    // --- what it asks for ---

    @Test
    fun theResolvedCredentialsAndThePageSizeAreTheOnesTheClientIsAskedWith() = runTest {
        signedIn()
        backend.withUpNext(row())

        service().loadUpNext(NOW)

        assertEquals(listOf(Triple(ACCESS_TOKEN, PROFILE_ID, 20)), backend.upNextCalls)
    }

    @Test
    fun theRailIsTheUpNextRailAndNothingElse() = runTest {
        signedIn()
        backend.withUpNext(row())

        val section = requireNotNull(service().loadUpNext(NOW))

        assertEquals(UP_NEXT_SECTION_KEY, section.key)
        assertEquals("Up Next", section.title)
        assertEquals(HomeWideRailSectionKind.UP_NEXT, section.kind)
    }

    // --- how it fails ---

    @Test
    fun withNoProfileItAsksTheUserToSignIn() = runTest {
        resolver.answerWithNothing()

        val failure = assertFailsWith<IllegalStateException> { service().loadUpNext(NOW) }

        assertEquals("Sign in and select a profile to load Up Next.", failure.message)
        assertTrue(backend.upNextCalls.isEmpty())
    }

    @Test
    fun aFailingReadIsReportedWithoutTheCauseLeakingIntoTheMessage() = runTest {
        signedIn()
        val cause = IllegalStateException("network down")
        backend.upNextFailure = cause

        val failure = assertFailsWith<IllegalStateException> { service().loadUpNext(NOW) }

        assertEquals("Unable to load Up Next right now.", failure.message)
        assertSame(cause, failure.cause, "the cause is kept for the log, not for the message")
    }

    @Test
    fun aFailingReadIsLoggedAsAWarningCarryingTheCause() = runTest {
        signedIn()
        val cause = IllegalStateException("network down")
        backend.upNextFailure = cause

        assertFailsWith<IllegalStateException> { service().loadUpNext(NOW) }

        assertEquals(1, logger.warnings.size)
        assertEquals("UpNextService", logger.warnings.single().first)
        assertSame(cause, logger.warnings.single().third)
    }

    @Test
    fun aPageWithNothingUsableInItIsNoRailRatherThanAnEmptyRail() = runTest {
        signedIn()
        backend.withUpNext()

        assertNull(service().loadUpNext(NOW))
    }

    // --- what it drops, and why ---

    @Test
    fun anEntryWithNoNextEpisodeIsDropped() = runTest {
        signedIn()
        backend.withUpNext(row(nextEpisode = null))

        assertNull(service().loadUpNext(NOW))
    }

    @Test
    fun anEntryWithNoShowIsDropped() = runTest {
        signedIn()
        backend.withUpNext(row(show = null))

        assertNull(service().loadUpNext(NOW))
    }

    @Test
    fun theUsableEntriesSurviveTheUnusableOnesBesideThem() = runTest {
        signedIn()
        backend.withUpNext(
            row(nextEpisode = null),
            row(show = mediaCard("show-9"), nextEpisode = mediaCard("ep-9", parent = parentRef("show-9"))),
            row(show = null),
        )

        val section = requireNotNull(service().loadUpNext(NOW))
        val items = (section.state as RailLoadState.Ready).items

        assertEquals(1, items.size)
        assertEquals("show-9", items.single().detailsItemId)
    }

    // --- the row it builds ---

    @Test
    fun aRowIsBuiltFromTheShowAndItsNextEpisode() = runTest {
        signedIn()
        backend.withUpNext(
            row(
                show = mediaCard("show-1", title = "The Series", images = images(artwork = "art.jpg")),
                nextEpisode = mediaCard(
                    itemId = "ep-1",
                    title = "The One",
                    images = images(still = "still.jpg"),
                    parent = parentRef("show-1", seasonNumber = 2, episodeNumber = 5),
                ),
            ),
        )

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()
        val canonical = requireNotNull(item.continueWatchingItem)

        assertEquals("ep-1", canonical.id)
        assertEquals("show-1", canonical.titleItemId)
        assertEquals("ep-1", canonical.playbackItemId)
        assertEquals("episode", canonical.itemType)
        assertEquals("The Series", canonical.title)
        assertEquals("The One", canonical.episodeTitle)
        assertEquals(2, canonical.season)
        assertEquals(5, canonical.episode)
        assertEquals(0.0, canonical.progressPercent)
        assertEquals("art.jpg", canonical.artworkUrl)
        // The rail subtitle is "S02E05: The One" -- the season/episode code and the
        // episode name joined. Both halves are this batch's padStart port.
        assertEquals("S02E05: The One", item.subtitle)
        assertEquals("show-1", item.detailsItemId)
    }

    @Test
    fun aRowWithNoShowTitleFallsBackToAnEmptyTitleRatherThanCrashing() = runTest {
        signedIn()
        backend.withUpNext(row(show = mediaCard("show-1", title = "")))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertEquals("", requireNotNull(item.continueWatchingItem).title)
    }

    @Test
    fun aRowKeepsItsTimestampWhenThereIsOne() = runTest {
        signedIn()
        backend.withUpNext(row(lastInteractedAt = "2026-01-01T00:00:00Z"))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertEquals(1_767_225_600_000L, requireNotNull(item.continueWatchingItem).lastUpdatedEpochMs)
    }

    @Test
    fun aRowWithNoTimestampFallsBackToTheInjectedClock() = runTest {
        signedIn()
        backend.withUpNext(row(lastInteractedAt = null))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertEquals(CLOCK, requireNotNull(item.continueWatchingItem).lastUpdatedEpochMs)
    }

    @Test
    fun aRowWithABlankTimestampFallsBackToTheInjectedClock() = runTest {
        signedIn()
        backend.withUpNext(row(lastInteractedAt = "   "))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertEquals(CLOCK, requireNotNull(item.continueWatchingItem).lastUpdatedEpochMs)
    }

    @Test
    fun aRowWithAnUnparseableTimestampFallsBackToTheInjectedClock() = runTest {
        signedIn()
        backend.withUpNext(row(lastInteractedAt = "last tuesday"))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertEquals(CLOCK, requireNotNull(item.continueWatchingItem).lastUpdatedEpochMs)
    }

    @Test
    fun aRowWithNoArtworkHasNoImage() = runTest {
        signedIn()
        backend.withUpNext(row(show = mediaCard("show-1", images = images())))

        val item = requireNotNull(service().loadUpNext(NOW)).readyItems().single()

        assertNull(item.imageUrl)
    }

    private fun HomeWideRailSectionUi.readyItems(): List<HomeWideRailItemUi> {
        return (state as RailLoadState.Ready).items
    }

    private companion object {
        /** 2026-01-08T00:00:00Z: the instant the rail is projected for. */
        const val NOW = 1_767_830_400_000L
        /** A different reading, so a clock fallback is distinguishable from `NOW`. */
        const val CLOCK = 1_700_000_000_000L
    }
}
