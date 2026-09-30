package com.crispy.tv.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
 * The rail is `:app` code reached through `:app`'s `desktop` JVM variant -- this
 * module holds no presentation of its own, only the window, the fixture seed and
 * the [DesktopEnvironment] that supplies the platform ports.
 */
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
            ContinueWatchingRail(
                items = seed.items,
                contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding),
            )
        }
    }
}
