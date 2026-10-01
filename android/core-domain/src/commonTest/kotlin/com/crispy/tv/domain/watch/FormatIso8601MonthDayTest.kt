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
 *
 * **Every expectation here was checked against the `java.time` expression it replaced,
 * not against my reasoning about it.** The repo's own rule is to measure a JVM call's
 * real output before replacing it and copy the measurement into the test, and the
 * measurement is in `formatIso8601MonthDay`'s KDoc: all twelve months, the leap day,
 * three invalid dates that *threw* (a `RuntimeException`, so the caller's
 * `catch (_: Exception) { null }` turned them into the same `null` this returns), and
 * `0000-03-07` → `Mar 7`. That last one is why the final case exists — it pins the
 * year-of-era divergence between this function and its long-form sibling, which is the
 * only reason printing no year is safe rather than merely convenient.
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

    @Test
    fun theTwoMonthFunctionsDisagreeOnlyAboutAnInstantOnTheEnd() {
        // The one thing a reader comparing this function with its obvious sibling
        // `iso8601MonthLabel` would assume is that they are the same parser. They
        // are not, and the difference is load-bearing rather than accidental:
        //
        //     iso8601MonthLabel("2024-03-14T00:00:00Z")      == "Mar"
        //     formatIso8601MonthDay("2024-03-14T00:00:00Z")  == null
        //
        // `iso8601MonthLabel` treats the length check as a lower bound and reads the
        // date off the first ten characters; this function requires a date and
        // nothing more, so a caller passing a full instant gets `null` and is
        // expected to truncate first.
        //
        // **On the day they agree, and that is worth asserting too.** An earlier
        // draft of this file claimed `iso8601MonthLabel` skipped day validation, on
        // the strength of a KDoc sentence about truncation, and asserted
        // `iso8601MonthLabel("2024-02-30") == "Feb"`. The run failed — it is `null` —
        // because `parseIso8601MonthNumber` calls `isValidDate`, and its own comment
        // names `"2024-02-31"` as the case it exists to reject. So the two functions
        // are strict about an impossible day *identically*; only the instant differs.
        // The four assertions below are the shape of that correction, and the strict
        // pair is the half a reader is most likely to doubt.
        assertEquals("Mar", iso8601MonthLabel("2024-03-14T00:00:00Z"))
        assertNull(formatIso8601MonthDay("2024-03-14T00:00:00Z"))

        assertNull(iso8601MonthLabel("2024-02-30"), "both reject an impossible day")
        assertNull(formatIso8601MonthDay("2024-02-30"), "and they reject it the same way")
    }

    @Test
    fun theSameValueCarriesADifferentYearThroughTheLongFormAndThatIsWhyThisOnePrintsNone() {
        // The one thing the suite could not previously state, found by running the
        // `java.time` expression this function replaced on a real JDK rather than by
        // reasoning about it. `0000-03-07` rendered `Mar 7` there too — not because the
        // year is right, but because no year is printed. The long form *does* print one,
        // and has to shift it, so the two disagree on that value by exactly the year:
        //
        //     formatIso8601MonthDay("0000-03-07")  ==  "Mar 7"
        //     formatIso8601LongDate("0000-03-07")  ==  "Mar 7, 0001"
        //
        // This is the year-of-era trap this file's KDoc warns about, and it is *invisible*
        // in the month-day form. That invisibility is the reason it is safe to print no
        // year — and it is a reason, not a coincidence, so it belongs in an assertion
        // rather than in a comment where the next reader has to already know the trap to
        // find it. A change that made this function print a year would break here.
        assertEquals("Mar 7", formatIso8601MonthDay("0000-03-07"))
        assertEquals("Mar 7, 0001", formatIso8601LongDate("0000-03-07"))
    }
}
