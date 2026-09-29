package com.crispy.tv.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the three decisions [formatLongDate] makes that the domain function does
 * not: what a blank value means, that a longer value is truncated to its date,
 * and -- the one that is easy to get wrong -- that an unparseable value is
 * returned **untrimmed**.
 *
 * The third is the load-bearing case. The previous implementation ended in
 * `catch (_: Throwable) { date }`, so `formatLongDate("  garbage  ")` returned
 * `"  garbage  "` with its spaces. Trimming the parsed value and then falling
 * back to the raw one is what preserves that, and a version that returned the
 * trimmed string would render a different row in the details metadata.
 */
class FormatLongDateTest {
    @Test
    fun formatsAReleaseDate() {
        assertEquals("Jan 5, 2026", formatLongDate("2026-01-05"))
    }

    @Test
    fun trimsBeforeParsing() {
        assertEquals("Jan 5, 2026", formatLongDate("  2026-01-05  "))
    }

    @Test
    fun readsTheDateOutOfAValueThatCarriesAnInstant() {
        assertEquals("Jan 5, 2026", formatLongDate("2026-01-05T18:30:00Z"))
    }

    @Test
    fun aNullValueIsNull() {
        assertNull(formatLongDate(null))
    }

    @Test
    fun aBlankValueIsNull() {
        assertNull(formatLongDate("   "))
    }

    @Test
    fun anUnparseableValueIsShownUnchangedRatherThanDropped() {
        assertEquals("coming soon", formatLongDate("coming soon"))
    }

    @Test
    fun anUnparseableValueIsReturnedUntrimmedNotAsTheParsedValue() {
        // The point of the case: the parse is given the trimmed text, the
        // fallback is given the original. Collapsing the two loses the spaces.
        assertEquals("  not a date  ", formatLongDate("  not a date  "))
    }

    @Test
    fun aShortUnparseableValueIsAlsoShownUnchanged() {
        // Shorter than ten characters, so it is handed to the parser as-is
        // rather than truncated -- truncating it would change what it is.
        assertEquals("soon", formatLongDate("soon"))
    }
}
