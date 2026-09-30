package com.crispy.tv.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.crispy.tv.settings.ImageSettingsScreen
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.watchhistory.ContinueWatchingRail

/**
 * The desktop entry point.
 *
 * One module serves Windows, macOS and Linux, because all three run on the JVM;
 * only the packaging differs. Run it with `./gradlew :desktopApp:run`.
 *
 * What is rendered, and from where, is described on [SeedData] and on
 * `ContinueWatchingRail` in `:android:app`. The short version: this is the seam
 * proof, and it renders the real design system over real domain logic rather than a
 * mock of either. See the comment at the top of this module's build file for what
 * it deliberately does *not* render yet, and why.
 *
 * Both screens are `:app` code reached through `:app`'s `desktop` JVM variant. This
 * module holds the window, the fixture seed, the [DesktopEnvironment] that supplies
 * the platform ports, and the two-entry-point switch between them.
 *
 * ## Why the switch is here and not in `:app`
 *
 * Because `:app` has no navigation seam yet, and inventing one for two screens
 * would be a design decision made in a landing whose point is something else. The
 * two screens live in `commonMain` and are reachable; what does not exist is a
 * portable `NavHost`, and `androidx.navigation` is not on the `commonMain`
 * classpath at all. So the switch is four lines of `remember`ed state, and it is
 * written to be replaced rather than extended: when `:app` grows a real shell,
 * this file loses the `when` and keeps the two calls.
 */
private enum class DesktopScreen { WATCHING, IMAGE_SETTINGS }

fun main() {
    val environment = DesktopEnvironment()
    val seed = SeedData.load()

    // The window's size is read back out of the settings store and written on the
    // way out, which is the smallest honest use of the seam: it drives an injected
    // `KeyValueStore` through a real round trip, so a store that cannot persist
    // shows up as a window that forgets its size rather than as a test nobody
    // wrote. A first run has no stored value and takes the defaults.
    val storedWidth = environment.settings.getFloat(WINDOW_WIDTH_KEY, DEFAULT_WINDOW_WIDTH_DP)
    val storedHeight = environment.settings.getFloat(WINDOW_HEIGHT_KEY, DEFAULT_WINDOW_HEIGHT_DP)
    environment.logger.info("Main", "Seeded ${seed.items.size} continue-watching item(s)")

    application {
        val windowState = rememberWindowState(width = storedWidth.dp, height = storedHeight.dp)

        Window(
            // Written here rather than from a `SideEffect` on the size, which
            // would rewrite the file on every frame of a drag -- hundreds of full
            // read-modify-writes -- to record a value that only matters at launch.
            onCloseRequest = {
                environment.settings.putFloat(WINDOW_WIDTH_KEY, windowState.size.width.value.toFloat())
                environment.settings.putFloat(WINDOW_HEIGHT_KEY, windowState.size.height.value.toFloat())
                exitApplication()
            },
            title = "Crispy",
            state = windowState,
        ) {
            var screen by remember { mutableStateOf(DesktopScreen.WATCHING) }

            when (screen) {
                DesktopScreen.WATCHING -> Column {
                    DesktopAffordance("Image quality") { screen = DesktopScreen.IMAGE_SETTINGS }
                    ContinueWatchingRail(
                        items = seed.items,
                        contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding),
                    )
                }

                DesktopScreen.IMAGE_SETTINGS -> {
                    val settings by environment.imageSettings.settings.collectAsState()
                    ImageSettingsScreen(
                        settings = settings,
                        // The repository's other half: a real `:app` screen writing
                        // through a real `KeyValueStore`, so a quality chosen here
                        // is still there on the next launch. Nothing invalidates an
                        // image cache because nothing decodes one.
                        onQualityChanged = environment.imageSettings::setQuality,
                        onBack = { screen = DesktopScreen.WATCHING },
                    )
                }
            }
        }
    }
}

/**
 * A label that opens the other screen.
 *
 * `BasicText` and `Modifier.clickable` rather than a Material3 `TextButton`, and
 * the reason is the dependency shape: `:app` renders Material3 internally, but
 * naming a Material3 symbol *here* would need it on this module's own compile
 * classpath, and the affordance is a placeholder until `:app` has a shell to put
 * a real one in. It is a `Column` child so it costs a slot rather than a
 * `Box` overlay the rail's own background would have to be told about.
 */
@Composable
private fun DesktopAffordance(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(Dimensions.PageBottomPadding),
    )
}
