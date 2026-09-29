package com.crispy.tv.screenshot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.brand.CrispyMark
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.genreIcon
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.jetbrains.compose.resources.painterResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots for static brand artwork and the genre glyph set.
 *
 * These are the assets most likely to be replaced by accident -- a new
 * `brand_mark.xml`, a drawable re-export with different padding, a genre key
 * that silently stops resolving to its icon. None of that shows up in a logic
 * test, and `genreIcon` falls back to a generic glyph rather than failing, so
 * nothing downstream would notice either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = RobolectricDeviceQualifiers.Pixel5,
)
class BrandAndGenreIconsScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val genres = listOf(
        "action", "comedy", "documentary", "drama", "fantasy",
        "horror", "music", "romance", "scifi", "thriller", "war", "western",
    )

    @Test
    fun brandMarkAndWordmark() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CrispyRewriteTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CrispyMark(modifier = Modifier.size(120.dp))
                    CrispyWordmark(modifier = Modifier.width(240.dp))
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onRoot()
            .captureRoboImage("src/test/screenshots/brand_mark_and_wordmark.png")
    }

    @Test
    fun genreGlyphs() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CrispyRewriteTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    genres.chunked(4).forEach { rowGenres ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowGenres.forEach { genre ->
                                // painterResource rather than Icon: an Icon
                                // tints its content, which would hide a broken
                                // or recoloured drawable behind a solid block.
                                Image(
                                    painter = painterResource(genreIcon(genre)),
                                    contentDescription = genre,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule
            .onRoot()
            .captureRoboImage("src/test/screenshots/genre_glyphs.png")
    }
}
