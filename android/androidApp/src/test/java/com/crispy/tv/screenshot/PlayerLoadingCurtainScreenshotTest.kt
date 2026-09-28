package com.crispy.tv.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.crispy.tv.playerui.PlayerLoadingCurtain
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots for the player loading curtain.
 *
 * Runs on a plain JVM via Robolectric, so it needs no emulator and no KVM --
 * which is the point, because this repository has no rendering coverage
 * otherwise. Record with `recordRoborazziPlayDebug`, verify with
 * `verifyRoborazziPlayDebug`.
 *
 * Determinism notes, all of which matter or the diffs become noise:
 *  - `sdk` is pinned to 35, the highest Robolectric ships. compileSdk is 37.
 *  - The device qualifier is pinned so layout does not depend on the host.
 *  - `GraphicsMode.NATIVE` gives real Skia output rather than a no-op canvas.
 *  - The Compose clock is frozen, otherwise the indeterminate spinner and the
 *    fade-in animate and every run differs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class PlayerLoadingCurtainScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun visible() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CrispyRewriteTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    PlayerLoadingCurtain(visible = true)
                }
            }
        }
        // Past the 200ms fade-in, so the curtain is fully painted.
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.waitForIdle()

        composeTestRule
            .onRoot()
            .captureRoboImage("src/test/screenshots/player_loading_curtain_visible.png")
    }

    @Test
    fun hidden() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CrispyRewriteTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    PlayerLoadingCurtain(visible = false)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onRoot()
            .captureRoboImage("src/test/screenshots/player_loading_curtain_hidden.png")
    }
}
