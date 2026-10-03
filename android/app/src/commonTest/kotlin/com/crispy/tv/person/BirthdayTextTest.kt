package com.crispy.tv.person

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `birthdayText`'s four rules, with the rendering replaced by a recorder.
 *
 * The renderer is injected precisely because it cannot be: `"MMMM d, yyyy"` under
 * `Locale.getDefault()` is a device-language answer, so there is nothing to assert about
 * it from `commonTest`. What *is* assertable is everything the function decides before it
 * asks: what counts as absent, what gets passed on, and what happens when the date does
 * not parse.
 *
 * The recorder returns a fixed string rather than echoing its input, so a case cannot pass
 * by the value happening to look like what it sent -- the recording is the assertion.
 */
class BirthdayTextTest {

    private class RecordingFormat : (Long) -> String {
        val seen = mutableListOf<Long>()
        override fun invoke(epochMillis: Long): String {
            seen += epochMillis
            return "FORMATTED"
        }
    }

    @Test
    fun anAbsentBirthdayIsNullAndNotAnEmptyString() {
        val format = RecordingFormat()
        // The row is hidden on a null, so null and "" are the same answer to the screen --
        // but they are different answers to "does this person have a birthday", and only
        // one of them keeps the renderer out of being called at all.
        assertNull(birthdayText(null, format))
        assertEquals(emptyList(), format.seen, "an absent birthday must not reach the renderer")
    }

    @Test
    fun aBlankBirthdayIsTreatedAsAbsentRatherThanRendered() {
        // Whitespace-only is a backend that sent an empty column. Rendering the raw
        // whitespace would put an invisible, unselectable value in the BORN row.
        for (blank in listOf("", " ", "\t", "\n", "   ")) {
            val format = RecordingFormat()
            assertNull(birthdayText(blank, format), "blank ${blank.length} char(s) must read as absent")
            assertTrue(format.seen.isEmpty(), "blank ${blank.length} char(s) must not reach the renderer")
        }
    }

    @Test
    fun aValidDateIsTrimmedThenHandedToTheRenderer() {
        val format = RecordingFormat()
        assertEquals("FORMATTED", birthdayText("  1981-09-04  ", format))
        // Midnight UTC on that date. Asserting the exact number -- not "it was called" --
        // is what makes this a test of the *value*, since a renderer handed the wrong
        // instant would happily print the 3rd.
        assertEquals(listOf(368_409_600_000L), format.seen)
    }

    @Test
    fun aDateTheBackendCannotExpressRendersVerbatimAndTheRendererIsNotAsked() {
        // The fallback is user-visible: a backend that stored "unknown" or "circa 1912"
        // shows exactly that instead of an empty row, which is the only thing worse.
        for (raw in listOf("unknown", "circa 1912", "1981", "09/04/1981", "1981-13-04", "1981-02-30")) {
            val format = RecordingFormat()
            assertEquals(raw, birthdayText(raw, format), "$raw must render verbatim")
            assertTrue(format.seen.isEmpty(), "$raw must not reach the renderer")
        }
    }

    @Test
    fun theVerbatimFallbackIsTheTrimmedTextAndNotTheRawColumn() {
        // Trim happens before the parse, so the fallback returns what the parse was given.
        // The old body had the same order; this pins that the trim is not bypassed by the
        // fallback path, which is the one place a reordering would be invisible.
        val format = RecordingFormat()
        assertEquals("unknown", birthdayText("  unknown  ", format))
    }

    @Test
    fun aSignedOrOverlongYearNowTakesTheFallbackRatherThanBeingReformatted() {
        // The one input answered differently than the old `LocalDate.parse(raw)` body.
        // `ISO_LOCAL_DATE` gives its year field `SignStyle.EXCEEDS_PAD` over 4 to 10
        // digits, so `LocalDate.parse("+1981-09-04")` used to succeed and be reformatted;
        // `parseIso8601DateToEpochMillis` requires exactly ten characters, so these take
        // the fallback now. Recorded in `birthdayText`'s KDoc as a narrowing, and pinned
        // here so it stays a decision rather than becoming an accident.
        val format = RecordingFormat()
        for (raw in listOf("+1981-09-04", "12345-01-01")) {
            assertEquals(raw, birthdayText(raw, format), "$raw must now take the fallback")
            assertTrue(format.seen.isEmpty(), "$raw must not reach the renderer")
        }
    }

    @Test
    fun theBoundaryDaysOfAMonthAndYearAreAcceptedAndImpossibleOnesAreNot() {
        // The parser is `:core-domain`'s `parseIso8601DateToEpochMillis`, so its
        // `isValidDate` rules are what decide these. A test that only used a mid-month
        // date could not tell a real calendar check from a regex that matches digits.
        val format = RecordingFormat()
        assertEquals("FORMATTED", birthdayText("2000-02-29", format)) // a leap year
        assertEquals(listOf(951_782_400_000L), format.seen)
        assertEquals("FORMATTED", birthdayText("1970-01-01", format)) // the epoch itself
        assertEquals("1900-02-29", birthdayText("1900-02-29", RecordingFormat())) // not a leap year
    }

    @Test
    fun aValidDateAndAFallbackValueProduceDifferentAnswersFromTheSameInputShape() {
        // The discriminating pair: two 10-character strings, one parsed and one not. A
        // case set of only near misses would pass for a function that always falls back,
        // and one of only valid dates would pass for one that never does.
        val valid = RecordingFormat()
        val invalid = RecordingFormat()
        assertEquals("FORMATTED", birthdayText("1981-09-04", valid))
        assertEquals("1981-09-0X", birthdayText("1981-09-0X", invalid))
        assertEquals(1, valid.seen.size)
        assertEquals(0, invalid.seen.size)
    }
}