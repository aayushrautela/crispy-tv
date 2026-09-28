package com.crispy.tv.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.crispy.tv.ui.theme.Dimensions

/**
 * The desktop entry point.
 *
 * One module serves Windows, macOS and Linux, because all three run on the JVM;
 * only the packaging differs. Run it with `./gradlew :desktopApp:run`.
 *
 * What is rendered, and from where, is described on [SeedData] and
 * [ContinueWatchingScreen]. The short version: this is the seam proof, and it
 * renders the real design system over real domain logic rather than a mock of
 * either. See the comment at the top of this module's build file for what it
 * deliberately does *not* render yet, and why.
 */
fun main() {
    val seed = SeedData.load()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Crispy",
            state = rememberWindowState(width = 1100.dp, height = 800.dp),
        ) {
            ContinueWatchingScreen(
                items = seed.items,
                contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding),
            )
        }
    }
}
