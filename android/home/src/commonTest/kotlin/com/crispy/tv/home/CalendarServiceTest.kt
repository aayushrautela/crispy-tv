package com.crispy.tv.home

import com.crispy.tv.backend.CalendarResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CalendarService]'s own rules, and nothing else.
 *
 * The service is given a flat list of [com.crispy.tv.backend.CalendarItem] and is
 * solely responsible for bucketing them, ordering them, building the keys the UI
 * matches on, and deciding what happens when there is no profile or the network
 * fails. All of that is here. The only thing asserted about the client is that
 * the resolved access token and profile id reached it.
 *
 * [NOW] is `2026-01-08T00:00:00Z` and every date below is named by its ISO form
 * with its epoch in a comment, because a bucket boundary is the whole point of
 * these cases and a reader should not have to compute one.
 */
class CalendarServiceTest {

    private val backend = RecordingBackendApi()
    private val resolver = FixedBackendContextResolver(null)
    private val logger = RecordingAppLogger()

    private fun service() = CalendarService(
        backendClient = backend,
        backendContextResolver = resolver,
        logger = logger,
    )

    private fun signedIn() {
        resolver.answerWith(accessToken = ACCESS_TOKEN, profileId = PROFILE_ID)
    }

    // --- loadCalendar: who it asks, and what it says when it cannot ask ---

    @Test
    fun theResolvedCredentialsAreTheOnesTheClientIsAskedWith() = runTest {
        signedIn()
        backend.withCalendar()

        service().loadCalendar(NOW)

        assertEquals(listOf(ACCESS_TOKEN to PROFILE_ID), backend.calendarCalls)
    }

    @Test
    fun withNoProfileThereIsNoBackendCallAndAnError() = runTest {
        resolver.answerWithNothing()

        val snapshot = service().loadCalendar(NOW)

        assertTrue(backend.calendarCalls.isEmpty(), "the backend must not be asked without a profile")
        assertTrue(snapshot.isError)
        assertEquals("Sign in and select a profile to load your calendar.", snapshot.statusMessage)
        assertEquals(emptyList(), snapshot.sections)
    }

    @Test
    fun aFailingClientYieldsTheCachedSnapshotForTheSameProfile() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("ep-1"), airDate = PAST))
        val service = service()
        service.loadCalendar(NOW)

        backend.calendarFailure = IllegalStateException("network down")
        val second = service.loadCalendar(NOW)

        assertFalse(second.isError, "a cached snapshot is a better answer than an error")
        assertEquals(1, second.sections.sumOf { it.episodeItems.size })
    }

    @Test
    fun aFailingClientWithNoCacheYieldsTheGenericError() = runTest {
        signedIn()
        backend.calendarFailure = IllegalStateException("network down")

        val snapshot = service().loadCalendar(NOW)

        assertTrue(snapshot.isError)
        assertEquals("Unable to load your calendar right now.", snapshot.statusMessage)
        assertEquals(emptyList(), snapshot.sections)
    }

    @Test
    fun aFailingClientIsLoggedAsAWarningCarryingTheCause() = runTest {
        signedIn()
        val failure = IllegalStateException("network down")
        backend.calendarFailure = failure

        service().loadCalendar(NOW)

        assertEquals(1, logger.warnings.size)
        assertEquals("CalendarService", logger.warnings.single().first)
        assertSame(failure, logger.warnings.single().third)
    }

    // --- the per-profile cache ---

    @Test
    fun theCachedSnapshotIsNotHandedToADifferentProfile() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("ep-1"), airDate = PAST))
        val service = service()
        service.loadCalendar(NOW)

        resolver.answerWith(accessToken = ACCESS_TOKEN, profileId = "profile-2")
        backend.calendarFailure = IllegalStateException("network down")
        val other = service.loadCalendar(NOW)

        assertTrue(other.isError, "profile 1's calendar must not answer for profile 2")
    }

    @Test
    fun aFailedReadNeverOverwritesTheCachedSnapshot() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("ep-1"), airDate = PAST))
        val service = service()
        service.loadCalendar(NOW)

        backend.calendarFailure = IllegalStateException("network down")
        val afterFailure = service.loadCalendar(NOW)
        backend.calendarFailure = null
        backend.calendarFailure = IllegalStateException("network down")
        val afterASecondFailure = service.loadCalendar(NOW)

        // The cache is written only on a read that produced a snapshot, and a
        // thrown read produces none. The first failure therefore cannot be
        // mistaken for the second one's answer.
        assertEquals(afterFailure.sections, afterASecondFailure.sections)
        assertEquals(1, afterASecondFailure.sections.sumOf { it.episodeItems.size })
    }

    @Test
    fun aReadWithNoEpisodesIsAnAnswerAndIsCachedLikeOne() = runTest {
        signedIn()
        backend.withCalendar()
        val service = service()

        val first = service.loadCalendar(NOW)
        backend.calendarFailure = IllegalStateException("network down")
        val second = service.loadCalendar(NOW)

        // "Nothing is scheduled" is a real answer, not an error, so the offline
        // read is answered from the cache rather than claiming to be broken.
        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals("No upcoming episodes found right now.", second.statusMessage)
    }

    @Test
    fun aFreshResponseReplacesTheCachedOne() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("ep-1"), airDate = PAST))
        val service = service()
        service.loadCalendar(NOW)

        backend.calendarResponse = CalendarResponse(
            profileId = PROFILE_ID,
            source = "test",
            kind = null,
            generatedAt = null,
            items = listOf(
                calendarItem(mediaCard("ep-2"), airDate = PAST),
                calendarItem(mediaCard("ep-3"), airDate = PAST),
            ),
        )
        val second = service.loadCalendar(NOW)

        assertEquals(2, second.sections.sumOf { it.episodeItems.size })
    }

    // --- bucketing ---

    @Test
    fun itemsAreBucketedByHowFarTheirReleaseIsFromNow() = runTest {
        signedIn()
        backend.withCalendar(
            // 2026-01-05, three days before now.
            calendarItem(mediaCard("past"), airDate = "2026-01-05T00:00:00Z"),
            // 2026-01-10, inside the week.
            calendarItem(mediaCard("this-week"), airDate = "2026-01-10T00:00:00Z"),
            // 2026-01-14, the last instant still inside the week.
            calendarItem(mediaCard("week-edge"), airDate = "2026-01-14T00:00:00Z"),
            // 2026-01-16, one day past the week.
            calendarItem(mediaCard("upcoming"), airDate = "2026-01-16T00:00:00Z"),
            calendarItem(mediaCard("undated", releaseDate = "   ")),
        )

        val sections = service().loadCalendar(NOW).sections

        assertEquals(
            listOf(
                CalendarSectionKey.THIS_WEEK,
                CalendarSectionKey.UPCOMING,
                CalendarSectionKey.RECENTLY_RELEASED,
                CalendarSectionKey.NO_SCHEDULED,
            ),
            sections.map { it.key },
        )
        assertEquals(listOf("this-week", "week-edge"), sections[0].episodeItems.map { it.playbackItemId })
        assertEquals(listOf("upcoming"), sections[1].episodeItems.map { it.playbackItemId })
        assertEquals(listOf("past"), sections[2].episodeItems.map { it.playbackItemId })
    }

    @Test
    fun theLastInstantOfTheWeekIsStillThisWeek() = runTest {
        signedIn()
        // nowMs + WEEK_MS is 2026-01-15T00:00:00Z exactly, and the bucket is
        // closed on both ends, so this date is the one that decides whether the
        // comparison is `<` or `<=`. The day before it is 2026-01-14, which is
        // inside the week for a less interesting reason.
        backend.withCalendar(
            calendarItem(mediaCard("edge"), airDate = "2026-01-15T00:00:00Z"),
            calendarItem(mediaCard("past-edge"), airDate = "2026-01-15T00:00:00.001Z"),
        )

        val sections = service().loadCalendar(NOW).sections.associateBy { it.key }

        assertEquals(listOf("edge"), sections.getValue(CalendarSectionKey.THIS_WEEK).episodeItems.map { it.playbackItemId })
        assertEquals(listOf("past-edge"), sections.getValue(CalendarSectionKey.UPCOMING).episodeItems.map { it.playbackItemId })
    }

    @Test
    fun theFirstInstantOfTheWeekIsIncludedAndTheLastInstantBeforeItIsNot() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(mediaCard("at-now"), airDate = "2026-01-08T00:00:00Z"),
            calendarItem(mediaCard("before-now"), airDate = "2026-01-07T23:59:59.999Z"),
        )

        val sections = service().loadCalendar(NOW).sections.associateBy { it.key }

        assertEquals(listOf("at-now"), sections.getValue(CalendarSectionKey.THIS_WEEK).episodeItems.map { it.playbackItemId })
        assertEquals(listOf("before-now"), sections.getValue(CalendarSectionKey.RECENTLY_RELEASED).episodeItems.map { it.playbackItemId })
    }

    @Test
    fun thisWeekAndUpcomingAreOrderedSoonestFirstAndRecentlyReleasedNewestFirst() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(mediaCard("this-late"), airDate = "2026-01-14T00:00:00Z"),
            calendarItem(mediaCard("this-soon"), airDate = "2026-01-10T00:00:00Z"),
            calendarItem(mediaCard("soon"), airDate = "2026-01-16T00:00:00Z"),
            calendarItem(mediaCard("later"), airDate = "2026-01-20T00:00:00Z"),
            calendarItem(mediaCard("old"), airDate = "2026-01-01T00:00:00Z"),
            calendarItem(mediaCard("newer"), airDate = "2026-01-05T00:00:00Z"),
        )

        val sections = service().loadCalendar(NOW).sections.associateBy { it.key }

        assertEquals(
            listOf("this-soon", "this-late"),
            sections.getValue(CalendarSectionKey.THIS_WEEK).episodeItems.map { it.playbackItemId },
        )
        assertEquals(
            listOf("soon", "later"),
            sections.getValue(CalendarSectionKey.UPCOMING).episodeItems.map { it.playbackItemId },
        )
        assertEquals(
            listOf("newer", "old"),
            sections.getValue(CalendarSectionKey.RECENTLY_RELEASED).episodeItems.map { it.playbackItemId },
        )
    }

    @Test
    fun anEmptyBucketEmitsNoSectionAtAll() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("upcoming"), airDate = "2026-01-20T00:00:00Z"))

        val sections = service().loadCalendar(NOW).sections

        assertEquals(listOf(CalendarSectionKey.UPCOMING), sections.map { it.key })
    }

    @Test
    fun noItemsAtAllIsAStatusMessageAndNotAnError() = runTest {
        signedIn()
        backend.withCalendar()

        val snapshot = service().loadCalendar(NOW)

        assertFalse(snapshot.isError)
        assertEquals("No upcoming episodes found right now.", snapshot.statusMessage)
        assertEquals(emptyList(), snapshot.sections)
    }

    @Test
    fun anUnscheduledSeriesIsListedOnceHoweverManyOfItsEpisodesAreUndated() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(mediaCard("ep-1", parent = parentRef("series-1"))),
            calendarItem(mediaCard("ep-2", parent = parentRef("series-1"))),
            calendarItem(mediaCard("ep-3", parent = parentRef("series-2"))),
        )

        val section = service().loadCalendar(NOW).sections.single()

        assertEquals(CalendarSectionKey.NO_SCHEDULED, section.key)
        assertEquals(listOf("series-1", "series-2"), section.seriesItems.map { it.itemId })
    }

    @Test
    fun theCalendarAirDateWinsOverTheCardReleaseDate() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(
                mediaCard("ep-1", releaseDate = "2026-01-20T00:00:00Z"),
                airDate = "2026-01-10T00:00:00Z",
            ),
            // Nothing to fall back to, so the card's own date is used.
            calendarItem(mediaCard("ep-2", releaseDate = "2026-01-20T00:00:00Z")),
        )

        val sections = service().loadCalendar(NOW).sections.associateBy { it.key }

        assertEquals(listOf("ep-1"), sections.getValue(CalendarSectionKey.THIS_WEEK).episodeItems.map { it.playbackItemId })
        assertEquals(listOf("ep-2"), sections.getValue(CalendarSectionKey.UPCOMING).episodeItems.map { it.playbackItemId })
    }

    @Test
    fun aBlankDateIsTreatedAsNoDateRatherThanAsTheEpoch() = runTest {
        signedIn()
        backend.withCalendar(calendarItem(mediaCard("ep-1", releaseDate = ""), airDate = "   "))

        val snapshot = service().loadCalendar(NOW)

        assertEquals(listOf(CalendarSectionKey.NO_SCHEDULED), snapshot.sections.map { it.key })
        assertEquals("ep-1", snapshot.sections.single().seriesItems.single().itemId)
    }

    // --- the episode row itself ---

    @Test
    fun anEpisodeRowIsKeyedByItsOwnItemIdSeasonAndEpisode() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(
                mediaCard(
                    itemId = "ep-7",
                    title = "The One",
                    overview = "An overview",
                    releaseDate = "2026-01-10T00:00:00Z",
                    images = images(artwork = "https://img/art.jpg", still = "https://img/still.jpg"),
                    parent = parentRef("series-3", seriesTitle = "The Series", seasonNumber = 2, episodeNumber = 5),
                ),
            ),
        )

        val item = service().loadCalendar(NOW).sections.single().episodeItems.single()

        assertEquals("ep-7:2:5", item.localKey)
        assertEquals("ep-7:2:5", item.id)
        assertEquals("series-3", item.titleItemId)
        assertEquals("ep-7", item.playbackItemId)
        assertEquals("The Series", item.seriesName)
        assertEquals("The One", item.episodeTitle)
        assertEquals("An overview", item.overview)
        assertEquals(2, item.season)
        assertEquals(5, item.episode)
        assertEquals(1, item.episodeCount)
        assertFalse(item.isGroup)
        assertEquals("https://img/still.jpg", item.thumbnailUrl)
        assertEquals(setOf("ep-7:2:5"), item.watchedKeys)
    }

    @Test
    fun anEpisodeRowWithoutSeasonAndEpisodeFallsBackToADateKeyAndAnItemIdKey() = runTest {
        signedIn()
        backend.withCalendar(
            calendarItem(mediaCard(itemId = "ep-8", releaseDate = "2026-01-10T00:00:00Z")),
        )

        val item = service().loadCalendar(NOW).sections.single().episodeItems.single()

        assertEquals("ep-8:2026-01-10", item.localKey)
        assertEquals(setOf("ep-8"), item.watchedKeys)
        assertNull(item.season)
        assertNull(item.episode)
    }

    @Test
    fun anEpisodeRowWithNoDateAtAllHasNoSuffixOnItsKey() = runTest {
        signedIn()
        // Routed to NO_SCHEDULED as a series, so read the row through the
        // this-week endpoint instead, which projects every item it is given.
        backend.withThisWeek(calendarItem(mediaCard(itemId = "ep-9")))

        val item = service().loadThisWeek(NOW).items.single()

        assertEquals("ep-9", item.localKey)
    }

    @Test
    fun aStillIsPreferredForTheThumbnailAndFallsBackToArtwork() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(mediaCard(itemId = "with-still", images = images(artwork = "a", still = "s"))),
            calendarItem(mediaCard(itemId = "no-still", images = images(artwork = "a"))),
        )

        val items = service().loadThisWeek(NOW).items

        assertEquals("s", items.single { it.playbackItemId == "with-still" }.thumbnailUrl)
        assertEquals("a", items.single { it.playbackItemId == "no-still" }.thumbnailUrl)
    }

    @Test
    fun anItemInsideTheWeekIsNotMarkedReleasedAndAPastOneIs() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(mediaCard(itemId = "future", releaseDate = "2026-01-12T00:00:00Z")),
            calendarItem(mediaCard(itemId = "past", releaseDate = "2026-01-05T00:00:00Z")),
        )

        val items = service().loadThisWeek(NOW).items

        assertFalse(items.single { it.playbackItemId == "future" }.isReleased)
        assertTrue(items.single { it.playbackItemId == "past" }.isReleased)
    }

    // --- loadThisWeek: grouping ---

    @Test
    fun episodesOfOneSeriesOnOneDayBecomeASingleRow() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "ep-3", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 3)),
                airDate = DAY,
            ),
            calendarItem(
                mediaCard(itemId = "ep-1", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 1)),
                airDate = DAY,
            ),
            calendarItem(
                mediaCard(itemId = "ep-2", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 2)),
                airDate = DAY,
            ),
        )

        val item = service().loadThisWeek(NOW).items.single()

        assertTrue(item.isGroup)
        assertEquals(3, item.episodeCount)
        assertEquals("E1-E3", item.episodeRange)
        assertEquals("group_series-1_2026-01-10", item.localKey)
        assertEquals("group_series-1_2026-01-10", item.id)
        assertNull(item.episodeTitle)
        assertNull(item.overview)
    }

    @Test
    fun aLoneEpisodeOfADayIsPassedThroughUngrouped() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "ep-1", title = "Only", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 1)),
                airDate = DAY,
            ),
        )

        val item = service().loadThisWeek(NOW).items.single()

        assertFalse(item.isGroup)
        assertEquals(1, item.episodeCount)
        assertNull(item.episodeRange)
        assertEquals("ep-1:1:1", item.localKey)
        assertEquals("Only", item.episodeTitle)
    }

    @Test
    fun twoEpisodesOfOneSeriesOnDifferentDaysStaySeparateRows() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "ep-1", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 1)),
                airDate = "2026-01-10T00:00:00Z",
            ),
            calendarItem(
                mediaCard(itemId = "ep-2", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 2)),
                airDate = "2026-01-12T00:00:00Z",
            ),
        )

        assertEquals(2, service().loadThisWeek(NOW).items.size)
    }

    @Test
    fun twoDifferentSeriesOnOneDayStaySeparateRows() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "ep-1", parent = parentRef("series-1", seasonNumber = 1, episodeNumber = 1)),
                airDate = DAY,
            ),
            calendarItem(
                mediaCard(itemId = "ep-2", parent = parentRef("series-2", seasonNumber = 1, episodeNumber = 1)),
                airDate = DAY,
            ),
        )

        assertEquals(2, service().loadThisWeek(NOW).items.size)
    }

    @Test
    fun aGroupWithAnUnnumberedEpisodeReportsNoEpisodeRange() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(mediaCard(itemId = "a", parent = parentRef("series-1", episodeNumber = 2)), airDate = DAY),
            calendarItem(mediaCard(itemId = "b", parent = parentRef("series-1", episodeNumber = null)), airDate = DAY),
        )

        val item = service().loadThisWeek(NOW).items.single()

        assertTrue(item.isGroup)
        assertNull(item.episodeRange)
    }

    @Test
    fun aGroupTakesItsThumbnailFromTheFirstEpisodeThatHasOne() = runTest {
        signedIn()
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "a", images = images(), parent = parentRef("series-1", episodeNumber = 1)),
                airDate = DAY,
            ),
            calendarItem(
                mediaCard(itemId = "b", images = images(still = "s"), parent = parentRef("series-1", episodeNumber = 2)),
                airDate = DAY,
            ),
        )

        assertEquals("s", service().loadThisWeek(NOW).items.single().thumbnailUrl)
    }

    @Test
    fun aGroupIsMarkedReleasedFromItsFirstEpisodeRatherThanItsLast() = runTest {
        signedIn()
        // Both on the same day, but the "day" the group keys on is the release
        // date, so this checks the flag comes from the first item's own stamp.
        backend.withThisWeek(
            calendarItem(
                mediaCard(itemId = "a", parent = parentRef("series-1", episodeNumber = 1)),
                airDate = "2026-01-05T00:00:00Z",
            ),
            calendarItem(
                mediaCard(itemId = "b", parent = parentRef("series-1", episodeNumber = 2)),
                airDate = "2026-01-05T00:00:00Z",
            ),
        )

        val item = service().loadThisWeek(NOW).items.single()

        assertTrue(item.isReleased, "both episodes are in the past, so the group is released")
        assertEquals(2, item.episodeCount)
    }

    @Test
    fun thisWeekRowsAreOrderedSoonestFirstAndCappedAtTwenty() = runTest {
        signedIn()
        // 25 distinct series on the same day: one row each, so the render cap is
        // what decides the size rather than the grouping.
        backend.withThisWeek(
            *Array(25) { index ->
                calendarItem(
                    mediaCard(itemId = "ep-$index", parent = parentRef("series-$index", episodeNumber = 1)),
                    airDate = DAY,
                )
            },
        )

        val items = service().loadThisWeek(NOW).items

        assertEquals(20, items.size)
    }

    @Test
    fun thisWeekWithNoItemsIsAStatusMessageAndNotAnError() = runTest {
        signedIn()
        backend.withThisWeek()

        val result = service().loadThisWeek(NOW)

        assertFalse(result.isError)
        assertEquals("No episodes airing this week right now.", result.statusMessage)
        assertEquals(emptyList(), result.items)
    }

    @Test
    fun thisWeekWithNoProfileNeverReachesTheClient() = runTest {
        resolver.answerWithNothing()

        val result = service().loadThisWeek(NOW)

        assertTrue(result.isError)
        assertEquals("Sign in and select a profile to load this week.", result.statusMessage)
        assertTrue(backend.thisWeekCalls.isEmpty())
    }

    @Test
    fun thisWeekSurvivesAFailingClientWithTheGenericError() = runTest {
        signedIn()
        backend.thisWeekFailure = IllegalStateException("network down")

        val result = service().loadThisWeek(NOW)

        assertTrue(result.isError)
        assertEquals("Unable to load this week right now.", result.statusMessage)
        assertNotNull(logger.warnings.singleOrNull())
    }

    private companion object {
        /** 2026-01-08T00:00:00Z. */
        const val NOW = 1_767_830_400_000L
        const val DAY = "2026-01-10T00:00:00Z"
        const val PAST = "2026-01-05T00:00:00Z"
    }
}
