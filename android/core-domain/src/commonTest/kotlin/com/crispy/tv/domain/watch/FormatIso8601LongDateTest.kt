package com.crispy.tv.domain.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [formatIso8601LongDate] to the `java.time` output it replaced.
 *
 * Every expectation below was produced by running the original expression --
 * `LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))`
 * -- on JDK 21 and copying the result. The point of the file is that no
 * expectation here was reasoned out: the one that could not have been guessed
 * is [a year zero is a year of era and prints as one], where `0000-01-01`
 * yields `Jan 1, 0001` rather than `Jan 1, 0000`.
 *
 * The cases that *throw* in `java.time` are here too, because the portable
 * version returns null instead. That difference is deliberate and is the
 * reason the caller decides what an unparseable value means.
 */
class FormatIso8601LongDateTest {
    @Test
    fun formatsAnOrdinaryDate() {
        assertEquals("Jan 5, 2026", formatIso8601LongDate("2026-01-05"))
    }

    @Test
    fun formatsTheLastDayOfALeapYear() {
        assertEquals("Feb 29, 2024", formatIso8601LongDate("2024-02-29"))
    }

    @Test
    fun formatsTheLastDayOfTheYear() {
        assertEquals("Dec 31, 2026", formatIso8601LongDate("2026-12-31"))
    }

    @Test
    fun formatsTheLargestFourDigitYear() {
        assertEquals("Dec 31, 9999", formatIso8601LongDate("9999-12-31"))
    }

    @Test
    fun formatsTheFirstDayOfYearOne() {
        assertEquals("Jan 1, 0001", formatIso8601LongDate("0001-01-01"))
    }

    @Test
    fun aYearZeroIsAYearOfEraAndPrintsAsOne() {
        // `java.time` prints the *year of era*, so proleptic year 0 is 1 BCE and
        // renders as 0001. Printing 0000 here would look like a bug and is not.
        assertEquals("Jan 1, 0001", formatIso8601LongDate("0000-01-01"))
    }

    @Test
    fun rejectsAMalformedDay() {
        assertNull(formatIso8601LongDate("2024-02-31"))
    }

    @Test
    fun rejectsAMonthAboveTwelve() {
        assertNull(formatIso8601LongDate("2024-13-01"))
    }

    @Test
    fun rejectsSingleDigitComponents() {
        assertNull(formatIso8601LongDate("2024-3-4"))
    }

    @Test
    fun rejectsZeroPaddedButUnseparatedDigits() {
        // This rejection comes from `parseIso8601DateToEpochDay`, the function
        // this formatter actually calls -- not from `parseIso8601MonthNumber`,
        // which has its own separator check and is covered by `Iso8601Test`.
        // A mutation of the wrong one of the two passes this test, which is how
        // the two were told apart.
        assertNull(formatIso8601LongDate("2024010526"))
    }

    @Test
    fun rejectsAnInstantRatherThanTruncatingIt() {
        // The callers truncate to ten characters before calling; this pins that
        // the domain function does not do it for them.
        assertNull(formatIso8601LongDate("2026-01-05T00:00:00Z"))
    }

    @Test
    fun rejectsABlankValue() {
        assertNull(formatIso8601LongDate(""))
    }
}
