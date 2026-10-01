package com.crispy.tv.domain.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The month-and-day half of [formatIso8601LongDate], pinned.
 *
 * It was added beside that function to replace a `java.time.LocalDate.parse` call in
 * `:app`'s `commonMain` — a class of the Android platform, not a dependency, so a
 * KMP module does not have it, and one that a fully-qualified reference hides from
 * the import-scanning purity gate as completely as an import would not.
 *
 * Two things make this worth a suite rather than a call site:
 *
 * **The month table is a contract, not a derivation.** It is the `Locale.US` three-letter
 * set, chosen so the label cannot drift per device. So every one of the twelve is
 * asserted here rather than a sample of them, because a new row that is wrong would
 * otherwise pass.
 *
 * **It rejects where its caller truncates.** `formatIso8601MonthDay("2024-01-05T00:00:00Z")`
 * is `null`; the caller that holds a full ISO instant is expected to pass
 * `take(10)` first. That is the opposite of the truncating behaviour the function it
 * replaced had, and the difference is deliberate — so it is a case rather than a note.
 */
class FormatIso8601MonthDayTest {

    @Test
    fun aDateRendersAsItsThreeLetterMonthAndUnpaddedDay() {
        assertEquals("Jan 5", formatIso8601MonthDay("2024-01-05"))
        assertEquals("Dec 31", formatIso8601MonthDay("2024-12-31"))
    }

    @Test
    fun theDayIsNotZeroPadded() {
        // `5`, not `05`. The badge sits next to text that already groups by month, so a
        // padded day would read as a two-digit number where there is no such thing.
        assertEquals("Mar 7", formatIso8601MonthDay("2024-03-07"))
        assertEquals("Jan 9", formatIso8601MonthDay("2024-01-09"))
    }

    @Test
    fun noYearIsRenderedEvenThoughTheInputCarriesOne() {
        // The whole reason this function exists rather than reusing the long form: a
        // badge on an episode row wants the day the user recognises.
        assertEquals("Feb 29", formatIso8601MonthDay("2024-02-29"))
    }

    @Test
    fun everyOneOfTheTwelveMonthsRendersItsAgreedLabel() {
        // Enumerated rather than sampled. The set is the contract, and a table that
        // gained a thirteenth row or lost one must fail here.
        assertEquals(
            listOf(
                "Jan 1", "Feb 1", "Mar 1", "Apr 1", "May 1", "Jun 1",
                "Jul 1", "Aug 1", "Sep 1", "Oct 1", "Nov 1", "Dec 1",
            ),
            (1..12).map { formatIso8601MonthDay("2024-${it.toString().padStart(2, '0')}-01") },
        )
    }

    @Test
    fun septemberIsSepAndNotSept() {
        // The one label a derivation from a full month name would get wrong, and the
        // reason the table is written out rather than computed. `SEPTEMBER.take(3)` is
        // `SEP`, so the obvious implementation happens to agree here — the risk is the
        // inverse, and this pins the actual value rather than the rule that produced it.
        assertEquals("Sep 1", formatIso8601MonthDay("2024-09-01"))
    }

    @Test
    fun aValueWithATimeComponentIsRejectedRatherThanTruncated() {
        // Deliberately unlike the caller, which takes the first ten characters before
        // calling. So the truncation has to live in the caller and stay there.
        assertNull(formatIso8601MonthDay("2024-01-05T00:00:00Z"))
    }

    @Test
    fun anUnreadableValueIsNullRatherThanRendered() {
        assertNull(formatIso8601MonthDay(""))
        assertNull(formatIso8601MonthDay("   "))
        assertNull(formatIso8601MonthDay("not-a-date"))
        assertNull(formatIso8601MonthDay("2024-01"))
        assertNull(formatIso8601MonthDay("2024-13-01"), "month 13 is not a month")
        assertNull(formatIso8601MonthDay("2024-01-32"), "day 32 is not a day")
        assertNull(formatIso8601MonthDay("2024-02-30"), "February has no thirtieth")
    }

    @Test
    fun aLeapDayIsAcceptedInALeapYearAndRejectedInACommonOne() {
        // The day count is real calendar arithmetic, so this is the case where a
        // hand-rolled day-of-month would be wrong.
        assertEquals("Feb 29", formatIso8601MonthDay("2024-02-29"))
        assertNull(formatIso8601MonthDay("2023-02-29"))
    }

    @Test
    fun aYearOutsideTheCommonEraStillRendersItsMonthAndDay() {
        // No year-of-era correction is involved — the long form has to shift the year,
        // this one never prints one — so year zero is an ordinary date here. Asserted
        // because it is the input a truncation-and-slice implementation is most likely
        // to mangle.
        assertEquals("Mar 7", formatIso8601MonthDay("0000-03-07"))
    }
}
