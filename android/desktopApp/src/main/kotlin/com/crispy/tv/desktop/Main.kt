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
import com.crispy.tv.app.AppGraph
import com.crispy.tv.services.DesktopAppServices
import com.crispy.tv.settings.ImageSettingsRepository
import com.crispy.tv.settings.ImageSettingsScreen
import com.crispy.tv.ui.DesktopAppRoot
import com.crispy.tv.ui.navigation.CrispySharedTransitionLayout
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.watchhistory.ContinueWatchingRail

/**
 * The desktop entry point.
 *
 * One module serves Windows, macOS and Linux, because all three run on the JVM;
 * only the packaging differs. Run it with `./gradlew :desktopApp:run`.
 *
 * ## What this composes, and what it still does not render
 *
 * [DesktopAppServices] is the desktop `AppServices` -- every `:platform-core` port,
 * answered on the JVM -- and [AppGraph] is `:app`'s service graph over it. One of
 * each, built here and handed down, which is the whole of the wiring this module
 * owns. What [DesktopAppRoot] renders is `:app`'s *real* bootstrap prefix: the
 * splash, and then whichever of sign-in or profile-selection the graph says the
 * session calls for. With no Supabase credentials configured that is sign-in,
 * which is the honest first desktop screen rather than a placeholder.
 *
 * What is **not** rendered is the main shell. `AppRoot` takes its whole wiring
 * bundle as `navHostDependencies: @Composable () -> AppNavHostDependencies`, and
 * that 40-member bundle cannot be built off Android: the player graph, the Media3
 * hero trailer layer, the provider-logo badges (their drawables live in a plain
 * Android library) and the factories naming `StreamResolverProvider` /
 * `PlayerStreamHandoff`. So `ready` is still the seeded [DesktopSeedShell] rather
 * than `MainAppShell`, and the landing after this one is what removes it.
 *
 * An earlier version of this paragraph also blamed three `NavBackStackEntry`-reading
 * route-argument accessors, on the grounds that `arguments` is an `android.os.Bundle`
 * on every target. It is not: it is a common `SavedState?`, and `:app` already reads
 * the same values off `savedStateHandle`. That premise cost a landing; `HomeRouteArguments`
 * is where the correction is recorded.
 *
 * ## Why the window's size is read here and written on close
 *
 * Written on close rather than from a `SideEffect` on the size, which would
 * rewrite the file on every frame of a drag -- hundreds of full read-modify-writes
 * -- to record a value that only matters at launch. It goes through
 * `AppServices.keyValueStores` rather than a second store built over the same
 * directory, so there is one place in this module that decides where desktop data
 * lives.
 */
fun main() {
    val services = DesktopAppServices()
    val graph = AppGraph(services)
    val seed = SeedData.load()

    val windowSettings = services.keyValueStores.store(SETTINGS_STORE_NAME)
    val storedWidth = windowSettings.getFloat(WINDOW_WIDTH_KEY, DEFAULT_WINDOW_WIDTH_DP)
    val storedHeight = windowSettings.getFloat(WINDOW_HEIGHT_KEY, DEFAULT_WINDOW_HEIGHT_DP)
    services.logger.info("Main", "Seeded ${seed.items.size} continue-watching item(s)")

    application {
        val windowState = rememberWindowState(width = storedWidth.dp, height = storedHeight.dp)

        Window(
            onCloseRequest = {
                windowSettings.putFloat(WINDOW_WIDTH_KEY, windowState.size.width.value.toFloat())
                windowSettings.putFloat(WINDOW_HEIGHT_KEY, windowState.size.height.value.toFloat())
                exitApplication()
            },
            title = "Crispy",
            state = windowState,
        ) {
            // The `onSignedOut` slot is ignored rather than declined: it is the
            // real capability the shared shell needs, and this shell has no
            // signed-in state to leave. The next landing's shell takes it.
            DesktopAppRoot(graph) { _ ->
                DesktopSeedShell(seed = seed, imageSettings = graph.imageSettingsRepository)
            }
        }
    }
}

/**
 * The two screens that existed before there was a shell, kept as the `ready`
 * content until there is one.
 *
 * Written to be deleted, not extended: both screens are reachable only because
 * `DesktopAppRoot`'s bootstrap gate answers `Ready`, and once `:desktopApp` can
 * build an `AppNavHostDependencies` — `MainAppShell` is already callable off
 * Android — the `when` and the enum go with them.
 */
private enum class DesktopScreen { WATCHING, IMAGE_SETTINGS }

/**
 * What `:app`'s shell renders once the gate is through: real design system, real
 * domain logic, real repository over a real desktop store.
 *
 * [imageSettings] is `AppGraph`'s own repository, not a second one built here.
 * That was the defect this replaced: `DesktopEnvironment` named its store
 * `"image-settings"` while the graph names its own `"image_settings"`, so the
 * build carried two files and two answers to "what quality is this profile using"
 * -- and the one the real screens read was not the one this screen wrote.
 */
@Composable
private fun DesktopSeedShell(seed: SeedData, imageSettings: ImageSettingsRepository) {
    var screen by remember { mutableStateOf(DesktopScreen.WATCHING) }

    CrispySharedTransitionLayout {
        when (screen) {
            DesktopScreen.WATCHING -> Column {
                DesktopAffordance("Image quality") { screen = DesktopScreen.IMAGE_SETTINGS }
                ContinueWatchingRail(
                    items = seed.items,
                    contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding),
                )
            }

            DesktopScreen.IMAGE_SETTINGS -> {
                val settings by imageSettings.settings.collectAsState()
                ImageSettingsScreen(
                    settings = settings,
                    // The repository's other half: a real `:app` screen writing
                    // through a real `KeyValueStore`, so a quality chosen here is
                    // still there on the next launch. Unlike before this landing,
                    // the change now reaches `AppServices.invalidateImageCache`,
                    // so the cache and the screen agree on what "current" means.
                    onQualityChanged = imageSettings::setQuality,
                    onBack = { screen = DesktopScreen.WATCHING },
                )
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