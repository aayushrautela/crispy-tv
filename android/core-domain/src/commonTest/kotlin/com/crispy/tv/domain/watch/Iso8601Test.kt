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
}
