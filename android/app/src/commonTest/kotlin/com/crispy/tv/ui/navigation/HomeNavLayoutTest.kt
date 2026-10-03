package com.crispy.tv.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two layout thresholds the Home graph used to read inline, behind a
 * `LocalConfiguration` call, inside a `@Composable`.
 *
 * They are here because the landing that gave them names also made them reachable:
 * `isWideScreenLayout` and `isCompactWidth` are plain functions over two `Int`s, so a
 * `commonTest` can call them on every target. Before that they were
 * `configuration.screenWidthDp >= 768 && configuration.screenHeightDp <
 * configuration.screenWidthDp` and `configuration.screenWidthDp < 600` written into
 * two `DetailsRoute` call sites and one `HomeRoute` call site, with **no** test able
 * to reach either. The boundary on each threshold was a character in a literal.
 *
 * The cases below are written to fail for the *specific* rewrites that would be
 * tempting, not to cover the arithmetic. Each one's comment says which rewrite it
 * defeats, because a case with no rewrite behind it is decoration.
 */
class HomeNavLayoutTest {
    // ---- isWideScreenLayout -----------------------------------------------------

    /**
     * 767 vs 768. A `>` instead of a `>=` passes every wide case here and fails
     * exactly one: a 768dp-wide phone in landscape is the narrowest device the rule
     * is meant to admit, and it is the case the old phone layout was built for.
     */
    @Test
    fun theWideThresholdIsInclusiveAtSevenHundredAndSixtyEight() {
        assertFalse(isWideScreenLayout(screenWidthDp = 767, screenHeightDp = 400))
        assertTrue(isWideScreenLayout(screenWidthDp = 768, screenHeightDp = 400))
    }

    /**
     * The discriminating pair for the *second* clause. A 800x1200 tablet is above the
     * width threshold and would be called wide by the first clause alone -- but it is
     * taller than it is wide, so it is a portrait device and the details screen must
     * lay out for that. Dropping `screenHeightDp < screenWidthDp` -- as redundancy
     * invites -- passes every landscape case and fails this one, which is why the
     * clause is in the function at all.
     */
    @Test
    fun aPortraitTabletWiderThanTheThresholdIsNotWide() {
        assertFalse(isWideScreenLayout(screenWidthDp = 800, screenHeightDp = 1200))
        assertTrue(isWideScreenLayout(screenWidthDp = 1200, screenHeightDp = 800))
    }

    /**
     * The exact square. `screenHeightDp < screenWidthDp` is strict, so a square
     * window is not wide even though it clears the width threshold. This is the one
     * input where `<` and `<=` disagree, and it is the only place that can tell them
     * apart -- every other row in this file differs in at least two numbers.
     */
    @Test
    fun aSquareWindowIsNotWideBecauseTheHeightClauseIsStrict() {
        assertFalse(isWideScreenLayout(screenWidthDp = 800, screenHeightDp = 800))
    }

    /**
     * A zero or negative size is not a device, and it must not read as wide by
     * falling out of the first clause. `LocalConfiguration` cannot report one, so
     * this is a robustness case rather than a reachable one -- but it is the answer a
     * `screenWidthDp >= 768` check gives for free and the reason the conjunction is
     * written the way it is.
     */
    @Test
    fun aNonPositiveSizeIsNotWide() {
        assertFalse(isWideScreenLayout(screenWidthDp = 0, screenHeightDp = 0))
        assertFalse(isWideScreenLayout(screenWidthDp = -1000, screenHeightDp = -2000))
    }

    /**
     * The boundary stated as a rule rather than as two rows: across the whole
     * threshold neighbourhood the answer is `false` everywhere below 768 and `true`
     * everywhere at or above it, with the change happening at exactly one width. A
     * predicate that answered `true` for everything above 600 -- the other threshold
     * in this file -- passes every case above and fails this sweep.
     *
     * The first draft of this case asserted the answer flips *exactly once*, i.e.
     * `answers.count { it } == 1`. That is a claim about a predicate that is true at a
     * single point, not about a threshold: every width from 768 to 800 in this window
     * is wide, so the honest assertion is that the window splits into a false run and
     * then a true run. The draft would have been satisfied by a predicate answering
     * `true` only at 768 and `false` at 769, which is a different and wrong rule.
     */
    @Test
    fun theWideAnswerIsFalseBelowTheThresholdAndTrueAtOrAboveIt() {
        val window = (700..800).toList()
        val answers = window.map { isWideScreenLayout(screenWidthDp = it, screenHeightDp = 400) }
        val firstWide = answers.indexOfFirst { it }
        assertTrue(answers.take(firstWide).none { it }, "every width below the threshold must be narrow, not $answers")
        assertTrue(answers.drop(firstWide).all { it }, "every width at or above it must be wide, not $answers")
        assertEquals(768, window[firstWide], "and the change must happen at 768, not at ${window[firstWide]}")
    }

    // ---- isCompactWidth ---------------------------------------------------------

    /**
     * 599 vs 600, the inclusive/exclusive pair again. `compact = screenWidthDp <=
     * 600` passes every narrow case in the app and fails exactly this one.
     */
    @Test
    fun theCompactThresholdIsExclusiveAtSixHundred() {
        assertTrue(isCompactWidth(screenWidthDp = 599))
        assertFalse(isCompactWidth(screenWidthDp = 600))
    }

    /**
     * The two thresholds do not partition the width axis, and that is the point worth
     * pinning: 600..767dp is neither compact nor wide, and a rewrite that made
     * compact the complement of wide would call the middle band one of the two. Both
     * answers are asserted on the same width so the case cannot pass by either
     * predicate alone.
     */
    @Test
    fun theMiddleBandIsNeitherCompactNorWide() {
        val middle = 700
        assertFalse(isCompactWidth(screenWidthDp = middle))
        assertFalse(isWideScreenLayout(screenWidthDp = middle, screenHeightDp = 400))
    }

    /**
     * A sweep rather than a hand-picked row, because a hand-picked list cannot tell a
     * `>=` from a `>` at the boundary -- it just has to include the boundary, which is
     * one row out of however many were picked. Every width under the threshold is
     * compact and every width at or above it is not, with no exception in between.
     */
    @Test
    fun everyWidthBelowTheThresholdIsCompactAndEveryWidthAtItIsNot() {
        val below = (0 until 600).map { isCompactWidth(it) }
        val atOrAbove = (600 until 700).map { isCompactWidth(it) }
        assertTrue(below.all { it }, "every width in 0..599 must be compact")
        assertTrue(atOrAbove.none { it }, "no width in 600..699 may be compact")
    }
}