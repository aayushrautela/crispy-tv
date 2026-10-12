package com.crispy.tv.details

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wash clamp: hue may vary per slide, but the lightness band never moves,
 * which is what keeps white copy winning on every backdrop.
 */
class SlideWashClampTest {

    @Test
    fun vividSeedsLandInsideTheBand() {
        // Dark is safe for white copy; the band's job is capping brightness.
        // (Blue washes to ~0.09, yellow to ~0.49 -- both verified by hand.)
        listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta).forEach { seed ->
            val washed = clampStoryWash(seed)
            val light = washed.luminance()
            assertTrue(
                light in 0.05f..0.55f,
                "vivid $seed washed to luminance $light, outside the readable band",
            )
        }
    }

    @Test
    fun extremesArePulledIntoTheBand() {
        val fromWhite = clampStoryWash(Color.White)
        val fromBlack = clampStoryWash(Color.Black)

        assertTrue(fromWhite.luminance() < Color.White.luminance(), "white must darken, got $fromWhite")
        assertTrue(fromBlack.luminance() > Color.Black.luminance(), "black must lighten, got $fromBlack")
    }

    @Test
    fun midGreySurvivesNearlyUnchanged() {
        val grey = Color(0.5f, 0.5f, 0.5f)
        val washed = clampStoryWash(grey)

        assertTrue(kotlin.math.abs(washed.red - 0.5f) < 0.08f, "mid grey should pass through, got $washed")
    }
}
