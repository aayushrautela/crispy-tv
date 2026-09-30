package com.crispy.tv.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the one decision `DetailsHeader` makes about the CTA's second line.
 *
 * The `when` this replaces appeared twice, byte for byte, inside a `@Composable`
 * body. Two things followed from that and neither is about the copy:
 *
 * - **It was unreachable.** A `when` inside a composable cannot be called from a
 *   test, so the only way to pin it would have been to render the whole header, and
 *   deleting an arm would have failed nothing.
 * - **A test's own private copy of it would have been worse than no test.** The
 *   tempting shortcut is to re-type the `when` in the suite and assert against that
 *   -- which proves the suite's own string and passes even when the header's arm is
 *   deleted. So the decision is a named `internal fun` in `commonMain` and this
 *   suite calls it.
 *
 * The formatters are named slot values here rather than lambdas: the assertions are
 * about *which* arm ran and which argument it passed, so a slot that reports its
 * input is what makes that visible.
 */
class DetailsHeaderSubtextTest {
    // 2026-09-30T12:00:00Z, and the CTA says a show has 25 minutes left, so the
    // "Ends at" line reads the clock exactly once and adds 25 minutes to it.
    private val now = 1_790_769_600_000L
    private val dateArg = mutableListOf<Long>()
    private val timeArg = mutableListOf<Long>()
    private val clockCalls = mutableListOf<Unit>()

    private fun dateFormat(epochMs: Long): String {
        dateArg += epochMs
        return "DATE<$epochMs>"
    }

    private fun timeFormat(epochMs: Long): String {
        timeArg += epochMs
        return "TIME<$epochMs>"
    }

    private fun clock(): Long {
        clockCalls += Unit
        return now
    }

    private fun rewatch(lastWatchedAtEpochMs: Long? = 1_700_000_000_000L, remainingMinutes: Int? = null) =
        WatchCta(
            kind = WatchCtaKind.REWATCH,
            label = "Rewatch",
            icon = WatchCtaIcon.REPLAY,
            remainingMinutes = remainingMinutes,
            lastWatchedAtEpochMs = lastWatchedAtEpochMs,
        )

    @Test
    fun aRewatchNamesTheDayItWasLastWatched() {
        assertEquals(
            "Last watched on DATE<1700000000000>",
            watchCtaSubtext(rewatch(), ::dateFormat, ::timeFormat, ::clock),
        )
        assertEquals(listOf(1_700_000_000_000L), dateArg)
        assertEquals(emptyList(), timeArg)
        assertEquals(emptyList(), clockCalls)
    }

    @Test
    fun aRewatchWithARunningTimeStillNamesTheDay() {
        // The arm order is the copy's decision, not an accident of the branches: a
        // title being rewatched *and* still available is a real state -- "Rewatch,
        // ends at 21:45" -- and the header shows the day, not the end time. Pin it
        // so a reordering that swaps the two lines is a failure.
        assertEquals(
            "Last watched on DATE<1700000000000>",
            watchCtaSubtext(rewatch(remainingMinutes = 25), ::dateFormat, ::timeFormat, ::clock),
        )
        assertEquals(emptyList(), timeArg)
        assertEquals(emptyList(), clockCalls)
    }

    @Test
    fun aRewatchWithNoWatchedTimestampFallsThroughToTheRunningTime() {
        // `REWATCH` with a null timestamp is the state the first arm's second
        // condition exists for: there is no day to name, so the line says when it
        // ends instead. Without that condition the whole function would return null
        // and the CTA would lose its second line entirely.
        assertEquals(
            "Ends at TIME<${now + 25 * 60_000L}>",
            watchCtaSubtext(rewatch(lastWatchedAtEpochMs = null, remainingMinutes = 25), ::dateFormat, ::timeFormat, ::clock),
        )
        assertEquals(emptyList(), dateArg)
        assertEquals(listOf(now + 25 * 60_000L), timeArg)
    }

    @Test
    fun aContinueAddsTheRemainingMinutesToTheClock() {
        val cta = WatchCta(
            kind = WatchCtaKind.CONTINUE,
            label = "Continue",
            remainingMinutes = 25,
            lastWatchedAtEpochMs = 1_700_000_000_000L,
        )
        assertEquals("Ends at TIME<${now + 25 * 60_000L}>", watchCtaSubtext(cta, ::dateFormat, ::timeFormat, ::clock))
        // `CONTINUE` is not a rewatch, so a watched timestamp does not reach the
        // first arm even though one is present.
        assertEquals(emptyList(), dateArg)
    }

    @Test
    fun zeroRemainingMinutesIsStillALine() {
        // `remainingMinutes != null`, not `> 0`: a title that is about to expire
        // renders "Ends at <now>" rather than nothing. A `> 0` would silently drop
        // the line exactly when the title is most urgent.
        val cta = WatchCta(kind = WatchCtaKind.CONTINUE, remainingMinutes = 0)
        assertEquals("Ends at TIME<$now>", watchCtaSubtext(cta, ::dateFormat, ::timeFormat, ::clock))
    }

    @Test
    fun negativeRemainingMinutesAreNotClamped() {
        // Measured, not assumed: the arithmetic is `clock() + minutes * 60_000`, so
        // an expired title renders a time in the past. The header does not clamp, and
        // the value it would need is a copy decision rather than a formatting one.
        val cta = WatchCta(kind = WatchCtaKind.CONTINUE, remainingMinutes = -5)
        assertEquals("Ends at TIME<${now - 5 * 60_000L}>", watchCtaSubtext(cta, ::dateFormat, ::timeFormat, ::clock))
    }

    @Test
    fun aFirstWatchWithNoTimingHasNoSecondLine() {
        assertNull(watchCtaSubtext(WatchCta(), ::dateFormat, ::timeFormat, ::clock))
        assertEquals(emptyList(), dateArg)
        assertEquals(emptyList(), timeArg)
        assertEquals(emptyList(), clockCalls)
    }

    @Test
    fun aFirstWatchWithATimestampStillHasNoSecondLine() {
        // A `WATCH` is not a `REWATCH`, so a stale `lastWatchedAtEpochMs` left on the
        // object by the caller must not produce "Last watched on ..." for a title the
        // user has never seen.
        val cta = WatchCta(kind = WatchCtaKind.WATCH, lastWatchedAtEpochMs = 1_700_000_000_000L)
        assertNull(watchCtaSubtext(cta, ::dateFormat, ::timeFormat, ::clock))
        assertEquals(emptyList(), dateArg)
    }

    @Test
    fun theClockIsReadExactlyOnceAndOnlyForTheEndsAtLine() {
        // The clock is the only impure input, so reading it twice would be two
        // different "now"s and a minute boundary could fall between them.
        watchCtaSubtext(
            WatchCta(kind = WatchCtaKind.CONTINUE, remainingMinutes = 25),
            ::dateFormat,
            ::timeFormat,
            ::clock,
        )
        assertEquals(1, clockCalls.size)
    }
}
