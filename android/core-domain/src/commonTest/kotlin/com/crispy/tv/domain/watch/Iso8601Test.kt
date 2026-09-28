package com.crispy.tv.domain.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the portable ISO-8601 helpers that replaced `java.time`.
 *
 * Every expected value was produced by `java.time.Instant` / `LocalDate` on a
 * JVM, so this suite fails if the hand-rolled civil-date arithmetic drifts.
 */
class Iso8601Test {

    @Test
    fun parsesUtcInstants() {
        assertEquals(0L, parseIso8601InstantToEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_704_067_200_000L, parseIso8601InstantToEpochMillis("2024-01-01T00:00:00Z"))
        assertEquals(1_735_689_600_000L, parseIso8601InstantToEpochMillis("2025-01-01T00:00:00Z"))
        assertEquals(1_739_145_600_000L, parseIso8601InstantToEpochMillis("2025-02-10T00:00:00Z"))
    }

    @Test
    fun parsesInstantsBeforeTheEpoch() {
        assertEquals(-86_400_000L, parseIso8601InstantToEpochMillis("1969-12-31T00:00:00Z"))
    }

    @Test
    fun parsesFractionalSecondsTruncatedToMilliseconds() {
        assertEquals(1_739_145_600_500L, parseIso8601InstantToEpochMillis("2025-02-10T00:00:00.500Z"))
        // Truncates rather than rounds, matching the documented behaviour.
        assertEquals(1_739_145_600_000L, parseIso8601InstantToEpochMillis("2025-02-10T00:00:00.0009Z"))
    }

    @Test
    fun appliesNumericUtcOffsets() {
        assertEquals(1_739_147_400_000L, parseIso8601InstantToEpochMillis("2025-02-10T01:30:00+01:00"))
        assertEquals(1_739_163_600_000L, parseIso8601InstantToEpochMillis("2025-02-10T00:00:00-05:00"))
    }

    @Test
    fun parsesLeapDay() {
        assertEquals(1_709_164_800_000L, parseIso8601InstantToEpochMillis("2024-02-29T00:00:00Z"))
        assertEquals(19_782L, parseIso8601DateToEpochDay("2024-02-29"))
    }

    @Test
    fun parsesCalendarDates() {
        assertEquals(0L, parseIso8601DateToEpochDay("1970-01-01"))
        assertEquals(19_723L, parseIso8601DateToEpochDay("2024-01-01"))
        assertEquals(20_129L, parseIso8601DateToEpochDay("2025-02-10"))
    }

    @Test
    fun rejectsMalformedValues() {
        assertNull(parseIso8601InstantToEpochMillis("not-a-date"))
        assertNull(parseIso8601InstantToEpochMillis("2025-02-10"))
        // No offset designator: rejected the way Instant.parse rejected it.
        assertNull(parseIso8601InstantToEpochMillis("2025-02-10T00:00:00"))
        assertNull(parseIso8601InstantToEpochMillis("2025-02-10T00:00:00Zjunk"))
        assertNull(parseIso8601InstantToEpochMillis("2025-02-10T00:00:00+99:00"))
        assertNull(parseIso8601InstantToEpochMillis("2025-13-10T00:00:00Z"))
        assertNull(parseIso8601InstantToEpochMillis("2025-02-30T00:00:00Z"))
        assertNull(parseIso8601InstantToEpochMillis("2023-02-29T00:00:00Z"))
        assertNull(parseIso8601InstantToEpochMillis("2025-02-10T24:00:00Z"))
        assertNull(parseIso8601DateToEpochDay("2025-02-30"))
        assertNull(parseIso8601DateToEpochDay("2023-02-29"))
        assertNull(parseIso8601DateToEpochDay("2025-2-10"))
    }

    @Test
    fun resolvesUtcDayIncludingPreEpochValues() {
        assertEquals(0L, utcEpochDayOf(0L))
        assertEquals(-1L, utcEpochDayOf(-1L))
        assertEquals(-1L, utcEpochDayOf(-86_400_000L))
        assertEquals(20_129L, utcEpochDayOf(1_739_145_600_000L))
    }

    /**
     * Pinned against `java.time.Instant.ofEpochMilli(ms).toString()` on a JDK 21
     * JVM, one expectation per line of that run.
     *
     * The pairing matters: the format is only correct if formatting and parsing
     * agree, so several entries are fed back through [parseIso8601InstantToEpochMillis]
     * below rather than only being compared to a literal.
     */
    @Test
    fun formatsInstantsAsJavaTimeDid() {
        // The epoch itself, and the two millisecond values either side of a second
        // boundary, which is where the omitted-fraction rule is easiest to get wrong.
        assertEquals("1970-01-01T00:00:00Z", formatIso8601Instant(0L))
        assertEquals("1970-01-01T00:00:00.001Z", formatIso8601Instant(1L))
        assertEquals("1970-01-01T00:00:00.999Z", formatIso8601Instant(999L))
        assertEquals("1970-01-01T00:00:01Z", formatIso8601Instant(1_000L))

        // A real event timestamp: the value `BackendWatchHistoryService` sends as
        // `occurredAt`, with and without a fraction.
        assertEquals("2024-01-02T03:04:05.123Z", formatIso8601Instant(1_704_164_645_123L))
        assertEquals("2024-01-02T03:04:05Z", formatIso8601Instant(1_704_164_645_000L))

        // Pre-epoch. The day and the time-of-day must be floored separately or these
        // render as negative times.
        assertEquals("1969-12-31T23:59:59.999Z", formatIso8601Instant(-1L))
        assertEquals("1969-12-31T23:59:59.001Z", formatIso8601Instant(-999L))
        assertEquals("1969-12-31T23:59:59Z", formatIso8601Instant(-1_000L))
        assertEquals("1969-12-31T00:00:00Z", formatIso8601Instant(-86_400_000L))

        // Leap day, a year that is divisible by 4 but not a leap year, the start of a
        // UTC day, and the last millisecond of year 9999.
        assertEquals("2000-02-29T00:00:00Z", formatIso8601Instant(951_782_400_000L))
        assertEquals("2100-01-01T00:00:00Z", formatIso8601Instant(4_102_444_800_000L))
        assertEquals("2021-01-01T00:00:00.123Z", formatIso8601Instant(1_609_459_200_123L))
        assertEquals("2033-05-18T03:33:20Z", formatIso8601Instant(2_000_000_000_000L))
        assertEquals("9999-12-31T23:59:59.999Z", formatIso8601Instant(253_402_300_799_999L))
    }

    /** Formatting and parsing must be inverses, or a formatted timestamp cannot be read back. */
    @Test
    fun formattedInstantsRoundTripThroughTheParser() {
        val samples = listOf(
            0L, 1L, 999L, 1_000L, -1L, -999L, -1_000L, -86_400_000L,
            1_704_164_645_123L, 1_609_459_200_123L, 951_782_400_000L,
            4_102_444_800_000L, 2_000_000_000_000L, 253_402_300_799_999L,
        )
        for (millis in samples) {
            assertEquals(millis, parseIso8601InstantToEpochMillis(formatIso8601Instant(millis)))
        }
    }
}
