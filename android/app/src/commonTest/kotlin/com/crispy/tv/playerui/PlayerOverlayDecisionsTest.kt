package com.crispy.tv.playerui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three decisions [PlayerOverlay] delegates out to, now that they are nameable.
 *
 * These were `val`s inside a `@Composable` body, which means **nothing could call them** --
 * not a test, and not another composable. Moving `PlayerOverlay.kt` into `commonMain` made the
 * *file* portable and left every one of these untestable, because a `val` inside a composable
 * needs the composition to run. So the file move and this lift are two landings, and this is
 * the second.
 *
 * Each class below is about one decision, and each names the rewrite it exists to catch. A
 * decision that has been lifted unchanged has not been pinned until something would break.
 */
class PlayerOverlayDecisionsTest {

    // ---- effectiveDurationMs --------------------------------------------------------

    /**
     * The guard is `> 0L`, so the boundary is 1 and not 0.
     *
     * A single one-sided case proves nothing here: an `>= 0L` rewrite would make the `0L`
     * case fail but say nothing about the other end, and the guard is about the end.
     */
    @Test
    fun anUnsettledDurationOfZeroFallsThroughToTheEnginesValue() {
        assertEquals(45_000L, effectiveDurationMs(stableDurationMs = 0L, durationMs = 45_000L))
    }

    @Test
    fun theFirstMillisecondIsAlreadySettledEnoughToWin() {
        // The discriminating pair: `>= 0L` would answer 1L here instead of 7_000L.
        assertEquals(1L, effectiveDurationMs(stableDurationMs = 1L, durationMs = 7_000L))
    }

    @Test
    fun aSettledDurationWinsOverTheEnginesStillMovingOne() {
        assertEquals(7_000L, effectiveDurationMs(stableDurationMs = 7_000L, durationMs = 9_000L))
    }

    /**
     * Both unknown is the state a video with no reported duration is in, and it has to answer
     * `0L` rather than diverge -- and a negative stable duration has to be treated as unset
     * too, since `> 0L` is the only guard there is.
     *
     * Each row is asserted on its own. An earlier version of this case also asserted that the
     * list of answers had the same key set as the list of inputs, which is true no matter what
     * the function returns: **an assertion that cannot fail is worse than no assertion,
     * because it reads as coverage.**
     */
    @Test
    fun anUnsetDurationIsTheEnginesValueEvenWhenBothAreZeroOrNegative() {
        val cases = mapOf(
            "both zero" to (0L to 0L),
            "settled nothing, engine nothing" to (0L to 1L),
            "both negative" to (-1L to -1L),
            "settled negative, engine positive" to (-1L to 500L),
            "settled equals engine" to (5_000L to 5_000L),
        )
        for ((label, pair) in cases) {
            val (stable, raw) = pair
            assertEquals(raw, effectiveDurationMs(stable, raw), label)
        }
    }

    // ---- isSurfaceOpen --------------------------------------------------------------

    /**
     * The two sources of truth are independent, so each arm is exercised on its own.
     *
     * A single case where both are true passes under `||` and under `&&`, so it distinguishes
     * nothing; the pair below is what makes the rule nameable at all.
     */
    @Test
    fun anOpenSurfaceIsOpenWhateverTheSelectorSays() {
        assertTrue(isSurfaceOpen(PlayerSurface.INFO, selectorVisible = false))
        assertTrue(isSurfaceOpen(PlayerSurface.EPISODES, selectorVisible = false))
    }

    @Test
    fun aVisibleSelectorIsOpenWhateverTheOverlaySurfaceSays() {
        assertTrue(isSurfaceOpen(PlayerSurface.NONE, selectorVisible = true))
    }

    @Test
    fun bothClosedIsTheOnlyWayNothingIsOpen() {
        assertFalse(isSurfaceOpen(PlayerSurface.NONE, selectorVisible = false))
    }

    /**
     * Every surface value is a case, and they are compared as a SET against the enum rather
     * than a hand-picked list -- so a new `PlayerSurface` value that is not treated as open
     * fails here instead of being silently skipped.
     */
    @Test
    fun everySurfaceButNoneIsOpenOnItsOwn() {
        val opensWhenAlone = PlayerSurface.entries
            .filter { isSurfaceOpen(it, selectorVisible = false) }
            .toSet()
        assertEquals(PlayerSurface.entries.filter { it != PlayerSurface.NONE }.toSet(), opensWhenAlone)
    }

    // ---- isSeriesDetails ------------------------------------------------------------

    /**
     * `true` only when the type is present and is not `movie`.
     *
     * The null case is the one that distinguishes the two possible readings of the
     * `== false` at the end of the expression, so it is asserted separately rather than swept
     * into a table where it would hide.
     */
    @Test
    fun anUnknownTypeIsNotASeries() {
        assertFalse(isSeriesDetails(null))
    }

    @Test
    fun aMovieIsNotASeriesAndTheComparisonIgnoresCase() {
        for (movie in listOf("movie", "MOVIE", "Movie", "mOvIe")) {
            assertFalse(isSeriesDetails(movie), "itemType=$movie")
        }
    }

    @Test
    fun anythingElseIsASeries() {
        for (other in listOf("series", "show", "tv", "anime", "SHOW", "documentary", "", " ")) {
            assertTrue(isSeriesDetails(other), "itemType=$other")
        }
    }

    /**
     * The exact split, asserted as a set over the input list so that a function returning a
     * constant fails: it would have to agree with both halves at once.
     */
    @Test
    fun theSplitIsExactlyPresentAndNotMovie() {
        val inputs = listOf<String?>(null, "movie", "MOVIE", "series", "show", "anime", "")
        val answerable = inputs.filter { isSeriesDetails(it) }
        assertEquals(listOf("series", "show", "anime", ""), answerable)
        assertEquals(4, answerable.size)
    }
}