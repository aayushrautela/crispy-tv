package com.crispy.tv.details

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `heroHeight` was `(configuration.screenHeightDp.dp * 0.40f).coerceIn(300.dp, 520.dp)`
 * written out inside `HeroSection`, where nothing could reach it: `LocalConfiguration`
 * is absent from Compose Multiplatform's common metadata, so no `commonMain` composable
 * could read the input, and a `val` inside a composable body is uncallable anyway.
 *
 * Naming it produced **three** assertable regimes where there had been none. That is
 * the argument for lifting a decision out of a composable rather than for moving the
 * composable: the move makes the *code* portable, the lift makes it *checked*.
 */
class HeroHeightTest {

    @Test
    fun aShortWindowIsHeldAtTheFloorRatherThanScaledDown() {
        // 400 * 0.40 = 160dp, which is under the floor.
        assertEquals(
            300.dp,
            heroHeight(400),
            "a 400dp-tall window scales to 160dp, so the 300dp floor is what should answer",
        )
    }

    @Test
    fun theFloorIsInclusiveBecauseItIsAClampNotAFilter() {
        // 750 * 0.40 is exactly 300dp: the first height whose 40% is the floor.
        assertEquals(300.dp, heroHeight(750), "exactly at the floor is the floor, not a hair under it")
        assertTrue(
            heroHeight(751) > 300.dp,
            "one dp taller is the first height that clears the floor, so the clamp is inclusive",
        )
    }

    @Test
    fun aTallWindowIsHeldAtTheCeilingRatherThanScalingUp() {
        // 2000 * 0.40 = 800dp, which is over the ceiling.
        assertEquals(
            520.dp,
            heroHeight(2000),
            "a 2000dp-tall window scales to 800dp, so the 520dp ceiling is what should answer",
        )
    }

    @Test
    fun theCeilingIsInclusiveBecauseItIsAClampNotAFilter() {
        // 1300 * 0.40 is exactly 520dp: the last height whose 40% is the ceiling.
        assertEquals(520.dp, heroHeight(1300), "exactly at the ceiling is the ceiling")
        assertTrue(
            heroHeight(1299) < 520.dp,
            "one dp shorter is the last height under the ceiling",
        )
    }

    @Test
    fun betweenTheBoundsItIsAPlainFortyPercent() {
        // 1000 * 0.40 = 400dp, which is between 300 and 520. Chosen because 0.40f
        // times 1000 is exactly representable, so this is an equality and not a
        // tolerance -- a tolerance wide enough to absorb the float error also
        // absorbs a wrong coefficient.
        assertEquals(400.dp, heroHeight(1000), "the unclamped regime is a linear 40%")
    }

    @Test
    fun everyHeightBetweenTheTwoBoundsIsMonotonicAndNeverOutsideThem() {
        // A sweep rather than a sample: a hand-picked list would not notice a body
        // that clamped only some heights, or one that inverted past a point.
        var previous: Dp = heroHeight(751)
        for (dp in 752..1299) {
            val current = heroHeight(dp)
            assertTrue(
                current >= previous,
                "hero height fell between ${dp - 1}dp and ${dp}dp: $previous then $current",
            )
            assertTrue(
                current >= 300.dp && current <= 520.dp,
                "heroHeight($dp) = $current escaped the 300..520 bounds",
            )
            previous = current
        }
    }

    @Test
    fun aZeroOrNegativeWindowIsHeldAtTheFloorRatherThanProducingANegativeHeight() {
        // 0 * 0.40 is 0dp and a negative height would throw in a layout pass, so
        // this is the case where the clamp is load-bearing rather than cosmetic.
        assertEquals(300.dp, heroHeight(0), "an unreported window must not produce a 0dp hero")
        assertEquals(300.dp, heroHeight(-100), "a negative window must not produce a negative hero")
    }
}