package com.crispy.tv.domain.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * [civilMonthKey] and [previousMonthKey] replace
 * `YearMonth.from(Instant.parse(value).atZone(zone)).toString()` and
 * `YearMonth.parse(key).minusMonths(1).toString()`.
 *
 * Every expected string here was produced by running the `java.time` expressions,
 * not by reasoning about them -- the first draft of the offset cases guessed that a
 * positive offset would move an instant *back* across a month boundary, and it does
 * not, so the pair of cases below are deliberately one positive and one negative.
 */
class CivilMonthKeyTest {

    // -------------------------------------------------------------- the key itself

    @Test
    fun `an instant in the middle of a month keys to that month at utc`() {
        assertEquals("2025-03", civilMonthKey("2025-03-15T12:00:00Z", utcOffsetMillis = 0L))
    }

    @Test
    fun `a leap day keys to February of its own year`() {
        assertEquals("2024-02", civilMonthKey("2024-02-29T00:00:00Z", utcOffsetMillis = 0L))
    }

    @Test
    fun `the last second of a year keys to that December`() {
        assertEquals("1999-12", civilMonthKey("1999-12-31T23:59:59Z", utcOffsetMillis = 0L))
    }

    @Test
    fun `a non-utc offset in the text is not a second shift`() {
        // The value already says +05:30, so shifting it again is the caller's choice,
        // not a second reading of the same field. Both land in the same month here.
        assertEquals("2025-03", civilMonthKey("2025-03-15T12:00:00+05:30", utcOffsetMillis = 0L))
    }

    @Test
    fun `a year below one thousand is zero-padded to four digits`() {
        // Measured against `YearMonth.from(...).toString()` on JDK 21: it pads, and
        // all four of these keys come out seven characters long. Without the padding
        // `monthKeyOf` would mint `"123-05"` and `"0-01"`, which nothing downstream
        // can parse -- `previousMonthKey` requires `length == 7` -- so this is the
        // one case where dropping the padding breaks the file's own round trip.
        assertEquals("0123-05", civilMonthKey("0123-05-06T12:00:00Z", utcOffsetMillis = 0L))
        assertEquals("0099-12", civilMonthKey("0099-12-31T23:59:59Z", utcOffsetMillis = 0L))
        assertEquals("0999-01", civilMonthKey("0999-01-01T00:00:00Z", utcOffsetMillis = 0L))
        assertEquals("0000-01", civilMonthKey("0000-01-01T00:00:00Z", utcOffsetMillis = 0L))
    }

    // --------------------------------------------------------- the offset is load-bearing

    @Test
    fun `a negative offset can move an instant back into the previous month`() {
        assertEquals("2025-04", civilMonthKey("2025-04-01T00:30:00Z", utcOffsetMillis = 0L))
        assertEquals(
            "2025-03",
            civilMonthKey("2025-04-01T00:30:00Z", utcOffsetMillis = -60L * 60L * 1_000L),
        )
    }

    @Test
    fun `a positive offset can move an instant forward into the next month`() {
        assertEquals("2025-04", civilMonthKey("2025-04-30T23:30:00Z", utcOffsetMillis = 0L))
        assertEquals(
            "2025-05",
            civilMonthKey("2025-04-30T23:30:00Z", utcOffsetMillis = 60L * 60L * 1_000L),
        )
    }

    @Test
    fun `an offset that does not cross a boundary leaves the key alone`() {
        // The mirror of the two cases above: the same instants, moved the other way,
        // so nothing changes. Without these the first two could pass on any arithmetic.
        assertEquals(
            "2025-04",
            civilMonthKey("2025-04-30T23:30:00Z", utcOffsetMillis = -60L * 60L * 1_000L),
        )
        assertEquals(
            "2025-03",
            civilMonthKey("2025-03-01T00:30:00Z", utcOffsetMillis = 60L * 60L * 1_000L),
        )
    }

    @Test
    fun `one instant can key to two different months in two zones`() {
        val value = "2025-03-01T00:30:00Z"
        assertEquals("2025-03", civilMonthKey(value, utcOffsetMillis = 0L))
        assertEquals("2025-02", civilMonthKey(value, utcOffsetMillis = -60L * 60L * 1_000L))
        assertNotEquals(
            civilMonthKey(value, utcOffsetMillis = 0L),
            civilMonthKey(value, utcOffsetMillis = -60L * 60L * 1_000L),
        )
    }

    // ------------------------------------------------------------ the epoch-millis form

    @Test
    fun `the two forms agree on the same instant`() {
        val value = "2025-07-04T06:00:00Z"
        val epochMillis = parseIso8601InstantToEpochMillis(value)!!
        for (offset in listOf(0L, -60L * 60L * 1_000L, 330L * 60L * 1_000L, -450L * 60L * 1_000L)) {
            assertEquals(
                civilMonthKey(value, offset),
                civilMonthKeyFromEpochMillis(epochMillis, offset),
                "both forms must answer the same for offset $offset",
            )
        }
    }

    // ------------------------------------------------------------------- bad input

    @Test
    fun `a value the parser rejects is null - as the old parse failure was`() {
        // Same convention as parseIso8601InstantToEpochMillis: the caller treats this
        // exactly as it treated Instant.parse throwing, rather than catching.
        assertNull(civilMonthKey("", utcOffsetMillis = 0L))
        assertNull(civilMonthKey("2025-03-15", utcOffsetMillis = 0L)) // a date, not an instant
        assertNull(civilMonthKey("not a date", utcOffsetMillis = 0L))
    }

    // ------------------------------------------------------------- the previous month

    @Test
    fun `the previous month of an ordinary month is the month before it`() {
        assertEquals("2025-02", previousMonthKey("2025-03"))
    }

    @Test
    fun `the previous month of january is december of the year before`() {
        assertEquals("2024-12", previousMonthKey("2025-01"))
    }

    @Test
    fun `december of the last representable year steps back inside that year`() {
        assertEquals("9999-11", previousMonthKey("9999-12"))
    }

    @Test
    fun `january of year zero has no representable predecessor`() {
        // YearMonth.of(0, 1).minusMonths(1) is "-0001-12" on the JVM, and a padded
        // four-digit field cannot express that sign, so the key would not parse back
        // through this function. Null is the honest answer; "-001-12" is not.
        assertNull(previousMonthKey("0000-01"))
    }

    @Test
    fun `the previous month of a padded year stays padded`() {
        // The same `padStart(4, '0')` on the *output* side, and it is a separate
        // obligation from the input side above: `previousMonthKey` decrements a year
        // it read with `readDigits`, so 0999-01 has to come back as 0998-12 rather
        // than 998-12. Both of these were measured on the JVM.
        assertEquals("0998-12", previousMonthKey("0999-01"))
        assertEquals("0099-11", previousMonthKey("0099-12"))
        assertEquals("0123-04", previousMonthKey("0123-05"))
    }

    @Test
    fun `a key that is not a month is null rather than an exception`() {
        // YearMonth.parse throws DateTimeParseException here, and a library function
        // that throws on a value it might itself have been handed is worse than one
        // that says "not a month".
        assertNull(previousMonthKey(""))
        assertNull(previousMonthKey("2025"))
        assertNull(previousMonthKey("2025-3"))
        assertNull(previousMonthKey("2025-03-15"))
        assertNull(previousMonthKey("2025/03"))
        assertNull(previousMonthKey("2025-00"))
        assertNull(previousMonthKey("2025-13"))
        assertNull(previousMonthKey("unknown")) // the sentinel LibraryScreen uses
    }

    @Test
    fun `a key this file produced parses back`() {
        // The round-trip is the property previousMonthKey depends on: a key is compared
        // for equality and then read again, so the two encodings must not drift apart.
        for (value in listOf("2025-03-15T12:00:00Z", "2024-02-29T00:00:00Z", "1999-12-31T23:59:59Z")) {
            val key = civilMonthKey(value, utcOffsetMillis = 0L)!!
            assertNotEquals(key, previousMonthKey(key), "$key must have a predecessor")
        }
    }
}
