package com.crispy.tv.playerui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two clock decisions in `PlayerOverlayControls.kt`.
 *
 * Neither was reachable from a test before that file moved to `commonMain`, and
 * **both were unreachable for a reason no import list showed**: they were rendered
 * with `"%d:%02d".format(...)`, and `kotlin.text.String.format` is a **JVM-only**
 * extension. A pin that arrives through a symbol rather than an import is invisible
 * to every scan in the toolchain, and it is why the file survived a census that
 * reported it as having no forbidden import at all.
 *
 * The formatting below is therefore no longer the string library's job, and the
 * assertions pin the *shape* rather than the standard library.
 */
class PlayerOverlayControlsTimeTest {

    // ---- formatPlaybackTimeMs: the padding rule --------------------------------

    @Test
    fun minutesAndSecondsAreZeroPaddedButHoursAreNot() {
        val cases =
            mapOf(
                0L to "0:00",
                // 5 is one digit, so the ":05" proves the zero-pad is applied.
                65_000L to "1:05",
                // 30 is already two digits: the padding must not lengthen it.
                90_000L to "1:30",
                // A one-digit hour proves the hours field is deliberately UNpadded,
                // so this distinguishes the two fields rather than just the format.
                3_600_000L to "1:00:00",
                // 10 is two digits and must be left alone, not become "010".
                36_000_000L to "10:00:00",
            )
        for ((timeMs, expected) in cases) {
            assertEquals(
                expected,
                formatPlaybackTimeMs(timeMs),
                "formatPlaybackTimeMs($timeMs) should render as $expected",
            )
        }
    }

    @Test
    fun theHoursFieldAppearsExactlyAtOneHour() {
        // The branch is `hours > 0`, so this is the discriminating pair: one
        // millisecond either side of the boundary has to answer differently, or the
        // test would pass against a body that always took the same branch.
        assertEquals("59:59", formatPlaybackTimeMs(3_599_999L), "just under an hour has no hours field")
        assertEquals("1:00:00", formatPlaybackTimeMs(3_600_000L), "exactly an hour gains the hours field")
    }

    @Test
    fun everyMinuteAndSecondBelowTenIsPadded() {
        // A sweep rather than a sample: the padding is a comparison against 10, and
        // a hand-picked list would not notice a body that padded only some of them.
        for (minutes in 0..9) {
            for (seconds in 0..9) {
                val timeMs = (minutes * 60L + seconds) * 1000L
                val expected = "$minutes:0$seconds"
                assertEquals(
                    expected,
                    formatPlaybackTimeMs(timeMs),
                    "both fields single digit at ${minutes}m${seconds}s must both be padded",
                )
            }
        }
    }

    // ---- formatPlaybackTimeMs: the input edges ---------------------------------

    @Test
    fun subSecondRemaindersAreTruncatedNotRounded() {
        // Integer division, so 999ms is zero seconds. Rounding would make this "0:01",
        // which is a different clock: a player that has not reached one second must
        // not show one.
        assertEquals("0:00", formatPlaybackTimeMs(999L), "999ms is under a second and must not round up")
        assertEquals("0:01", formatPlaybackTimeMs(1_000L), "exactly one second is the first non-zero reading")
    }

    @Test
    fun aNegativeTimeClampsToZeroRatherThanRenderingASign() {
        // The clamp is `coerceAtLeast(0L)`, so this is the value a seek before the
        // start of the stream produces. A signed "-1:-3" would be the alternative,
        // and the private `twoDigits` deliberately has no sign handling.
        assertEquals("0:00", formatPlaybackTimeMs(-1L), "a negative time clamps to the origin")
        assertEquals("0:00", formatPlaybackTimeMs(Long.MIN_VALUE), "the extreme negative still clamps")
    }

    @Test
    fun aTimeBeyondAnyRealEpisodeStillRendersItsHours() {
        // Nothing clamps the hours, so a large value keeps counting rather than
        // rolling over. This pins that the helper is a formatter and not a duration
        // type with a ceiling.
        assertEquals("27:46:40", formatPlaybackTimeMs(100_000_000L), "100_000s is 27h46m40s and is not wrapped")
    }

    // ---- buildTimePillText: the unknown-duration rule --------------------------

    @Test
    fun anUnknownDurationRendersADoubleDashRatherThanZero() {
        // A live stream reports no duration, and "0:00 / 0:00" would claim the
        // episode is over. The placeholder is therefore a distinct value, and this
        // asserts it on BOTH sides: the position is still rendered normally.
        assertEquals("0:00 / --:--", buildTimePillText(0L, 0L), "zero duration means unknown, not finished")
        assertEquals("1:05 / --:--", buildTimePillText(65_000L, 0L), "a live position still renders")
    }

    @Test
    fun aKnownDurationRendersBothFieldsSeparatedByASlash() {
        assertEquals("1:05 / 2:10", buildTimePillText(65_000L, 130_000L), "both sides are formatted the same way")
    }

    @Test
    fun aNegativeDurationIsTreatedAsUnknownRatherThanClampedToZero() {
        // The guard is `durationMs > 0L`, which is why this differs from the
        // clamping inside `formatPlaybackTimeMs`: the two rules are independent and
        // this pair is what keeps them from being collapsed into one.
        assertEquals("0:00 / --:--", buildTimePillText(0L, -1L), "a negative duration is unknown, not zero")
    }

    @Test
    fun aKnownDurationOfOneSecondIsKnownAndNotPlaceholder() {
        // One second is the smallest value the guard lets through, so it separates
        // "known and very short" from "unknown" at the boundary.
        assertEquals("0:00 / 0:01", buildTimePillText(0L, 1_000L), "one second is a known duration")
    }
}
