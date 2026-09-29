package com.crispy.tv.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.components.SharedImageMemoryKeys
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/**
 * Golden screenshots for [LandscapeCard], and the reason the image layer has
 * rendering coverage at all.
 *
 * The other two golden tests render brand marks, genre icons and a player
 * curtain. None of them renders a card, so when `CrispyImage` and the ten
 * components that call it moved to `commonMain` they passed the golden gate
 * because the gate never looked at them. These two cases close that.
 *
 * There are two cases and they are not redundant, because
 * `crispyImageRequest` returns early -- *before* it reads
 * `LocalPlatformContext` -- when the URL is blank. A card with no artwork
 * therefore never touches the one line whose move to `commonMain` was the whole
 * point. Only `withLoadedArtwork` exercises it.
 *
 * Determinism notes, on top of the ones in
 * [PlayerLoadingCurtainScreenshotTest]:
 *  - The artwork is a committed PNG, not a network URL, and it is handed to
 *    Coil as a `data:` URI rather than a `file://` one. That is not a style
 *    choice: Robolectric's `ImageDecoder` shadow serves a `file://` model badly
 *    enough that the request fails with `ImageDecoder$DecodeException: Only
 *    supported on Android`, while the very same bytes as a `data:` URI decode
 *    to 240x135. The PNG is valid either way -- `BitmapFactory.decodeFile` and
 *    `ImageDecoder.createSource(ByteBuffer)` both read it -- so the difference
 *    is the source shape Coil hands the decoder, not the fixture. Measured, not
 *    guessed; see [CoilDataUriScreenshotTest] for the probe that distinguishes
 *    them, and do not "simplify" this back to a file path.
 *  - Loading is still asynchronous, so `withLoadedArtwork` waits on a signal
 *    the card itself emits -- `SharedImageMemoryKeys` is written from the
 *    `onSuccess` lambda -- rather than on a fixed delay. A delay would be a
 *    race that happens to pass on a fast host.
 *  - `SharedImageMemoryKeys` is a process-wide object with no removal API, so
 *    the key is unique to this test and nothing else reads it. See the comment
 *    in `withLoadedArtwork` for why there is no cleanup rather than a fake one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class LandscapeCardScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * The no-artwork branch: the initial letter, the bottom scrim, a badge, and
     * the metadata row. Deterministic with no waiting at all, because nothing
     * loads.
     */
    @Test
    fun fallbackWithoutArtwork() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CrispyRewriteTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    LandscapeCard(
                        title = "The Long Way Round",
                        artworkUrl = null,
                        onClick = {},
                        itemId = "fallback-case",
                        badge = "NEW",
                        year = "2024",
                        maturityRating = "TV-14",
                        genre = "Drama",
                        rating = "8.4",
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onRoot()
            .captureRoboImage("src/test/screenshots/landscape_card_fallback.png")
    }

    /**
     * The branch the Coil move actually changed. Waits for the card's own
     * success callback rather than for a duration, so a slow host cannot turn
     * this into a flaky golden.
     */
    @Test
    fun withLoadedArtwork() {
        val itemId = "loaded-artwork-case"
        val memoryKey = "backdrop-$itemId"
        val artworkFile = File(
            requireNotNull(javaClass.classLoader?.getResource("screenshots/landscape_card_artwork.png")) {
                "the committed artwork fixture must be on the test classpath"
            }.toURI(),
        )
        assert(artworkFile.isFile) { "artwork fixture is not a file: $artworkFile" }
        // A data: URI, not artworkFile.toURI(): see CoilDataUriScreenshotTest for
        // the measurement behind that, and do not swap it back.
        val artworkModel =
            "data:image/png;base64," +
                Base64.getEncoder().encodeToString(artworkFile.readBytes())

        composeTestRule.mainClock.autoAdvance = false
        try {
            composeTestRule.setContent {
                CrispyRewriteTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        LandscapeCard(
                            title = "Signal And Noise",
                            artworkUrl = artworkModel,
                            onClick = {},
                            itemId = itemId,
                            year = "2025",
                            rating = "7.9",
                        )
                    }
                }
            }
            // The card writes the key from AsyncImage's onSuccess, so a
            // non-null key means the bitmap is decoded and painted.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                SharedImageMemoryKeys.getCardKey(memoryKey) != null
            }
            composeTestRule.waitForIdle()

            composeTestRule
                .onRoot()
                .captureRoboImage("src/test/screenshots/landscape_card_loaded.png")
        } finally {
            // SharedImageMemoryKeys exposes put and get but no removal, so
            // there is nothing to clean up -- the entry is left in place. That
            // is only safe because the key is unique to this test and no other
            // test reads it; a second test that needed a *clean* map would have
            // to add an API rather than work around this.
            check(SharedImageMemoryKeys.getCardKey(memoryKey) != null) {
                "the card never reported a successful load, so the waitUntil above timed out"
            }
        }
    }
}
