package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the eight decisions the library's date grouping makes, now that `java.time`
 * no longer answers any of them.
 *
 * Everything here used to be inside `LibraryScreen.kt` as a `private fun` reading
 * `Instant.now()` and `ZoneId.systemDefault()`, which made it unreachable twice
 * over: the clock could not be set, and no test could call the function. Two
 * changes made it reachable and neither is a test-only change -- the clock and the
 * zone offset are parameters, and the decisions are `internal` rather than
 * `private`.
 *
 * The dates below are written as ISO strings with the epoch in a comment, because
 * the month a timestamp falls in *is* the assertion, and the surprising ones are
 * the boundaries.
 */
class LibraryMonthKeyTest {
    // 2026-09-30T12:00:00Z. UTC, so a `+00:00` offset leaves every case unchanged.
    private val noonSep30 = 1_790_769_600_000L

    private fun item(
        id: String,
        itemId: String = id,
        addedAt: String? = null,
        watchedAt: String? = null,
        lastActivityAt: String? = null,
        liked: Boolean? = null,
    ) = CatalogItem(
        id = id,
        itemId = itemId,
        title = "Title $id",
        artworkUrl = null,
        addonId = "addon-1",
        type = "movie",
        liked = liked,
        addedAt = addedAt,
        watchedAt = watchedAt,
        lastActivityAt = lastActivityAt,
    )

    private fun monthName(key: String): String = "name($key)"

    // region currentMonthKeyOf

    @Test
    fun currentMonthKeyIsTheMonthTheClockReadsIn() {
        assertEquals("2026-09", currentMonthKeyOf({ noonSep30 }, 0L))
    }

    @Test
    fun currentMonthKeyReadsTheZoneTheCallerGave() {
        // 2026-09-30T23:30Z. With `+02:00` that instant is already 2026-10-01T01:30
        // locally, so the same clock reads a different month -- the whole reason the
        // offset is a parameter rather than a reading of the device.
        val lateSep30Utc = 1_790_811_000_000L
        assertEquals("2026-09", currentMonthKeyOf({ lateSep30Utc }, 0L))
        assertEquals("2026-10", currentMonthKeyOf({ lateSep30Utc }, 2 * 3_600_000L))
    }

    @Test
    fun currentMonthKeyCallsTheClockOnce() {
        // `currentMonthKeyOf` is the only value in either grouping that changes
        // with no input changing, so a second read inside it would be a second
        // "now" and a month boundary could fall between them.
        var calls = 0
        currentMonthKeyOf({ calls++; noonSep30 }, 0L)
        assertEquals(1, calls)
    }

    // endregion

    // region historyMonthKey

    @Test
    fun historyMonthKeyReadsAnInstantString() {
        assertEquals("2026-09", historyMonthKey("2026-09-14T08:30:00Z", 0L))
    }

    @Test
    fun historyMonthKeyIsUnknownForEveryShapeOfMissing() {
        assertEquals("unknown", historyMonthKey(null, 0L))
        assertEquals("unknown", historyMonthKey("", 0L))
        assertEquals("unknown", historyMonthKey("   ", 0L))
    }

    @Test
    fun historyMonthKeyIsUnknownForAnUnparseableTimestamp() {
        assertEquals("unknown", historyMonthKey("not a date", 0L))
    }

    @Test
    fun historyMonthKeyCrossesIntoTheNextMonthAtTheOffset() {
        // 2026-10-01T02:00Z -- 2026-10-01T04:00 in `+02:00`, so a reader that ignored
        // the offset would call this September.
        val oct1 = "2026-10-01T02:00:00Z"
        assertEquals("2026-10", historyMonthKey(oct1, 0L))
        assertEquals("2026-10", historyMonthKey(oct1, 2 * 3_600_000L))
        // And the mirror: 2026-09-30T22:00Z is October in `+05:00` and September in
        // `-05:00`. Neither case can pass on arithmetic that drops the offset.
        val sep30 = "2026-09-30T22:00:00Z"
        assertEquals("2026-10", historyMonthKey(sep30, 5 * 3_600_000L))
        assertEquals("2026-09", historyMonthKey(sep30, -5 * 3_600_000L))
    }

    // endregion

    // region historyMonthLabel

    @Test
    fun historyMonthLabelNamesTheTwoRecentMonthsInWords() {
        assertEquals("This Month", historyMonthLabel("2026-09", "2026-09", ::monthName))
        assertEquals("Last Month", historyMonthLabel("2026-08", "2026-09", ::monthName))
    }

    @Test
    fun historyMonthLabelFallsBackToTheMonthNameSlot() {
        assertEquals("name(2026-07)", historyMonthLabel("2026-07", "2026-09", ::monthName))
    }

    @Test
    fun historyMonthLabelWinsOverBothMonthArmsForAnUnknownKey() {
        // "unknown" is checked first, so a `currentMonthKey` of literally "unknown"
        // cannot make an unparseable timestamp claim to be this month.
        assertEquals("Unknown date", historyMonthLabel("unknown", "unknown", ::monthName))
    }

    @Test
    fun historyMonthLabelSpansTheYearBoundaryForLastMonth() {
        // December's last month is November of the *same* year; the pair either side
        // of New Year is the case where a naive `MM - 1` produces "2025-00".
        assertEquals("Last Month", historyMonthLabel("2025-12", "2026-01", ::monthName))
        assertEquals("This Month", historyMonthLabel("2026-01", "2026-01", ::monthName))
        assertEquals("name(2025-11)", historyMonthLabel("2025-11", "2026-01", ::monthName))
    }

    // endregion

    // region buildHistoryMonthSections

    @Test
    fun historySectionsSplitOnMonthChangeAndKeepOrder() {
        val sections = buildHistoryMonthSections(
            items = listOf(
                item("a", watchedAt = "2026-09-14T00:00:00Z"),
                item("b", watchedAt = "2026-09-20T00:00:00Z"),
                item("c", watchedAt = "2026-08-20T00:00:00Z"),
                item("d", watchedAt = "2026-09-02T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        // Contiguous runs only: "d" is a third September after a gap, and the
        // grouping keeps it in its own section rather than merging backwards.
        assertEquals(listOf("2026-09", "2026-08", "2026-09"), sections.map { it.monthKey })
        assertEquals(listOf("a", "b"), sections[0].items.map { it.id })
        assertEquals(listOf("c"), sections[1].items.map { it.id })
        assertEquals(listOf("d"), sections[2].items.map { it.id })
    }

    @Test
    fun historySectionsLabelEachSectionAgainstTheCurrentMonth() {
        val sections = buildHistoryMonthSections(
            items = listOf(
                item("a", watchedAt = "2026-09-14T00:00:00Z"),
                item("b", watchedAt = "2026-08-14T00:00:00Z"),
                item("c", watchedAt = "2026-07-14T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        assertEquals(listOf("This Month", "Last Month", "name(2026-07)"), sections.map { it.label })
    }

    @Test
    fun historySectionsPreferLastActivityOverWatched() {
        val sections = buildHistoryMonthSections(
            items = listOf(
                item(
                    "a",
                    watchedAt = "2026-01-05T00:00:00Z",
                    lastActivityAt = "2026-09-05T00:00:00Z",
                ),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        assertEquals("2026-09", sections.single().monthKey)
    }

    @Test
    fun historySectionsFallBackToWatchedWhenThereIsNoLastActivity() {
        val sections = buildHistoryMonthSections(
            items = listOf(item("a", watchedAt = "2026-08-05T00:00:00Z")),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        assertEquals("2026-08", sections.single().monthKey)
    }

    @Test
    fun historySectionsGroupAnUnparseableTimestampUnderUnknown() {
        val sections = buildHistoryMonthSections(
            items = listOf(item("a", watchedAt = "nonsense")),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        assertEquals("unknown", sections.single().monthKey)
        assertEquals("Unknown date", sections.single().label)
    }

    @Test
    fun historySectionsCollapseEpisodesByShow() {
        // The section builder ends by collapsing episodes into shows, so a section's
        // item count is not the input count. The collapse keys on `itemId` -- the
        // *show's* id, which is how the backend models an episode row -- not on the
        // row's own id, so the two episodes below share it and the movie does not.
        val sections = buildHistoryMonthSections(
            items = listOf(
                item("row-1", itemId = "show-1", watchedAt = "2026-09-05T00:00:00Z"),
                item("row-2", itemId = "show-1", watchedAt = "2026-09-06T00:00:00Z"),
                item("movie-1", itemId = "movie-1", watchedAt = "2026-09-07T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        val collapsed = sections.single().items
        assertEquals(listOf("show-1", "movie-1"), collapsed.map { it.itemId })
        // Pinned as it behaves, not as it looks like it should: the merge is
        // `(existing.episodeCount ?: 0) + (item.episodeCount ?: 1)`, and the first
        // episode row carries no count of its own, so two watched episodes merge to
        // **1**. That is the arithmetic `LibraryScreen` shipped and this landing did
        // not touch it -- a port that quietly fixed it would be a behaviour change
        // wearing a move. Recorded here so the number is not a surprise to whoever
        // does decide, because `1` understates "episodes watched" for a show and
        // `3` for the third row.
        assertEquals(1, collapsed.first().episodeCount)
    }

    @Test
    fun historySectionsCountMergedEpisodesAsRowsMinusOne() {
        // The same arithmetic on three rows, so the shape is pinned rather than a
        // single accidental value: each merge adds the *incoming* row's count and
        // never re-reads the accumulated one as if it were a row.
        val sections = buildHistoryMonthSections(
            items = listOf(
                item("row-1", itemId = "show-1", watchedAt = "2026-09-05T00:00:00Z"),
                item("row-2", itemId = "show-1", watchedAt = "2026-09-06T00:00:00Z"),
                item("row-3", itemId = "show-1", watchedAt = "2026-09-07T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        val show = sections.single().items.single()
        assertEquals(2, show.episodeCount)
        assertEquals("row-1", show.id)
    }

    @Test
    fun aRowThatAlreadyCarriesACountContributesIt() {
        // The other half of the merge: a row whose `episodeCount` is set is a show
        // row the backend already summarised, so its number is added as-is rather
        // than defaulted to 1.
        val sections = buildHistoryMonthSections(
            items = listOf(
                item("show-1", itemId = "show-1", watchedAt = "2026-09-05T00:00:00Z").copy(episodeCount = 12),
                item("row-2", itemId = "show-1", watchedAt = "2026-09-06T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
            monthName = ::monthName,
        )
        assertEquals(13, sections.single().items.single().episodeCount)
    }

    @Test
    fun historySectionsOfNothingAreNoSections() {
        assertTrue(
            buildHistoryMonthSections(
                items = emptyList(),
                currentMonthKey = "2026-09",
                utcOffsetMillis = 0L,
                monthName = ::monthName,
            ).isEmpty(),
        )
    }

    // endregion

    // region watchlistGroupKey

    @Test
    fun watchlistGroupKeyReadsTheMonthBeforeTheYear() {
        // This month and last month are both *this* year, so a grouping that read the
        // year first would put every recent addition in "earlier this year".
        assertEquals(
            WATCHLIST_GROUP_THIS_MONTH,
            watchlistGroupKey("2026-09-14T08:30:00Z", "2026-09", 0L),
        )
        assertEquals(
            WATCHLIST_GROUP_LAST_MONTH,
            watchlistGroupKey("2026-08-14T08:30:00Z", "2026-09", 0L),
        )
    }

    @Test
    fun watchlistGroupKeyBandsByYearOnceTheMonthIsOlder() {
        assertEquals(
            WATCHLIST_GROUP_EARLIER_THIS_YEAR,
            watchlistGroupKey("2026-02-01T00:00:00Z", "2026-09", 0L),
        )
        assertEquals(
            WATCHLIST_GROUP_LAST_YEAR,
            watchlistGroupKey("2025-09-14T00:00:00Z", "2026-09", 0L),
        )
        assertEquals(
            WATCHLIST_GROUP_OLDER,
            watchlistGroupKey("2024-09-14T00:00:00Z", "2026-09", 0L),
        )
    }

    @Test
    fun watchlistGroupKeySpansTheYearBoundaryForLastMonth() {
        // January's last month is December of the *previous* year, and that pair has
        // to beat the "last year" band -- otherwise every December addition vanishes
        // into "last year" instead of appearing under "last month".
        assertEquals(
            WATCHLIST_GROUP_LAST_MONTH,
            watchlistGroupKey("2025-12-20T00:00:00Z", "2026-01", 0L),
        )
        assertEquals(
            WATCHLIST_GROUP_LAST_YEAR,
            watchlistGroupKey("2025-11-20T00:00:00Z", "2026-01", 0L),
        )
    }

    @Test
    fun watchlistGroupKeyIsOlderForEveryShapeOfMissing() {
        assertEquals(WATCHLIST_GROUP_OLDER, watchlistGroupKey(null, "2026-09", 0L))
        assertEquals(WATCHLIST_GROUP_OLDER, watchlistGroupKey("", "2026-09", 0L))
        assertEquals(WATCHLIST_GROUP_OLDER, watchlistGroupKey("  ", "2026-09", 0L))
    }

    @Test
    fun watchlistGroupKeyIsOlderForAnUnparseableTimestamp() {
        // The original caught this with `catch (_: Exception)` around a
        // `YearMonth.parse`. The portable parser returns null instead, and the two
        // have to answer the same band.
        assertEquals(WATCHLIST_GROUP_OLDER, watchlistGroupKey("yesterday", "2026-09", 0L))
    }

    @Test
    fun watchlistGroupKeyAppliesTheOffset() {
        // 2026-10-01T02:00Z is October at `+02:00` and September at `-05:00`, so the
        // *same* addition lands in two adjacent bands depending only on the zone the
        // caller passed. A reader that dropped the offset could not tell them apart.
        val oct1 = "2026-10-01T02:00:00Z"
        assertEquals(WATCHLIST_GROUP_THIS_MONTH, watchlistGroupKey(oct1, "2026-10", 2 * 3_600_000L))
        assertEquals(WATCHLIST_GROUP_LAST_MONTH, watchlistGroupKey(oct1, "2026-10", -5 * 3_600_000L))
    }

    // endregion

    // region watchlistGroupLabel

    @Test
    fun everyWatchlistGroupKeyHasItsOwnLabel() {
        assertEquals("This Month", watchlistGroupLabel(WATCHLIST_GROUP_THIS_MONTH))
        assertEquals("Last Month", watchlistGroupLabel(WATCHLIST_GROUP_LAST_MONTH))
        assertEquals("Earlier This Year", watchlistGroupLabel(WATCHLIST_GROUP_EARLIER_THIS_YEAR))
        assertEquals("Last Year", watchlistGroupLabel(WATCHLIST_GROUP_LAST_YEAR))
        assertEquals("Older", watchlistGroupLabel(WATCHLIST_GROUP_OLDER))
    }

    @Test
    fun anUnknownGroupKeyReadsAsOlderRatherThanBlankingTheHeader() {
        assertEquals("Older", watchlistGroupLabel("no-such-band"))
    }

    @Test
    fun noTwoWatchlistGroupsShareALabel() {
        // The header is keyed by group key and shows the label, so two groups sharing
        // a label is not a cosmetic bug: it makes two distinct rows indistinguishable.
        val labels = listOf(
            WATCHLIST_GROUP_THIS_MONTH,
            WATCHLIST_GROUP_LAST_MONTH,
            WATCHLIST_GROUP_EARLIER_THIS_YEAR,
            WATCHLIST_GROUP_LAST_YEAR,
            WATCHLIST_GROUP_OLDER,
        ).map { watchlistGroupLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "duplicate watchlist group labels: $labels")
    }

    // endregion

    // region buildWatchlistDateSections

    @Test
    fun watchlistSectionsSplitOnGroupChangeAndKeepOrder() {
        val sections = buildWatchlistDateSections(
            items = listOf(
                item("a", addedAt = "2026-09-14T00:00:00Z"),
                item("b", addedAt = "2026-09-20T00:00:00Z"),
                item("c", addedAt = "2026-01-20T00:00:00Z"),
                item("d", addedAt = "2024-05-20T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
        )
        assertEquals(
            listOf(
                WATCHLIST_GROUP_THIS_MONTH,
                WATCHLIST_GROUP_EARLIER_THIS_YEAR,
                WATCHLIST_GROUP_OLDER,
            ),
            sections.map { it.groupKey },
        )
        assertEquals(listOf("a", "b"), sections[0].items.map { it.id })
        assertEquals("Earlier This Year", sections[1].label)
    }

    @Test
    fun watchlistSectionsDoNotCollapseEpisodes() {
        // The history grouping ends in `collapseEpisodesByShow`; this one does not,
        // because a watchlist holds what the user added rather than what they watched.
        val sections = buildWatchlistDateSections(
            items = listOf(
                item("show-1-s1e1", addedAt = "2026-09-05T00:00:00Z"),
                item("show-1-s1e2", addedAt = "2026-09-06T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
        )
        assertEquals(2, sections.single().items.size)
    }

    @Test
    fun watchlistSectionsOfNothingAreNoSections() {
        assertTrue(
            buildWatchlistDateSections(emptyList(), currentMonthKey = "2026-09", utcOffsetMillis = 0L).isEmpty(),
        )
    }

    @Test
    fun watchlistSectionsKeepEveryUnparseableAdditionTogether() {
        val sections = buildWatchlistDateSections(
            items = listOf(
                item("a", addedAt = "2026-09-14T00:00:00Z"),
                item("b", addedAt = "nonsense"),
                item("c", addedAt = null),
                item("d", addedAt = "2026-09-20T00:00:00Z"),
            ),
            currentMonthKey = "2026-09",
            utcOffsetMillis = 0L,
        )
        // All three unbandable additions share one band, and they do not merge with
        // the run either side of them.
        assertEquals(
            listOf(WATCHLIST_GROUP_THIS_MONTH, WATCHLIST_GROUP_OLDER, WATCHLIST_GROUP_THIS_MONTH),
            sections.map { it.groupKey },
        )
        assertEquals(listOf("b", "c"), sections[1].items.map { it.id })
    }

    // endregion

    // region buildRatingBandSections

    @Test
    fun ratingBandsKeepLikedBeforeDisliked() {
        val bands = buildRatingBandSections(
            listOf(
                item("d1", liked = false),
                item("l1", liked = true),
                item("d2", liked = false),
                item("l2", liked = true),
            ),
        )
        assertEquals(listOf(RATING_BAND_LIKED, RATING_BAND_DISLIKED), bands.map { it.bandKey })
        assertEquals(listOf("Liked", "Disliked"), bands.map { it.label })
        assertEquals(listOf("l1", "l2"), bands[0].items.map { it.id })
        assertEquals(listOf("d1", "d2"), bands[1].items.map { it.id })
    }

    @Test
    fun anEmptyRatingBandIsDroppedRatherThanRendered() {
        val bands = buildRatingBandSections(listOf(item("l1", liked = true)))
        assertEquals(listOf(RATING_BAND_LIKED), bands.map { it.bandKey })
    }

    @Test
    fun anUnratedItemIsInNeitherBand() {
        // `liked == null` is a real state -- a title in the ratings list whose rating
        // the backend has not recorded -- and it matches neither `== true` nor
        // `== false`, so it belongs in no band at all.
        val bands = buildRatingBandSections(listOf(item("u1", liked = null)))
        assertTrue(bands.isEmpty(), "an unrated item produced bands: ${bands.map { it.bandKey }}")
    }

    // endregion
}
