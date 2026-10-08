package com.crispy.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.ui.edge_to_edge.LocalBottomBarOverlayPadding
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppNavHostDependencies
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.FloatingBarBottomMargin
import com.crispy.tv.ui.navigation.FloatingBarHeight
import com.crispy.tv.ui.navigation.FloatingBottomBar
import com.crispy.tv.ui.navigation.PredictiveSettleDurationMillis
import com.crispy.tv.ui.navigation.TopLevelDestination
import com.crispy.tv.ui.navigation.rememberPredictivePeel

/**
 * The app's root composable: the shared bootstrap gate, then the shell.
 *
 * This is [AppBootstrapGate] with `MainAppShell` supplied as its ready content.
 * The split is a landing of its own, and the reason it was needed is written on
 * the gate: `MainAppShell`'s navigation bundle cannot be built off Android, so
 * a platform that can run the intro/auth/profile prefix supplies its own ready
 * branch instead. Desktop does exactly that.
 *
 * ## What crossed out
 *
 * Exactly two things, and neither of them is a decision.
 *
 * `LocalContext` was read for one purpose -- deriving an application `Context` to hand to
 * three factories -- so **the three factories crossed and the read died with them.** That is
 * the same shape as `AddonsSettingsViewModel`'s factory extraction: a `Context` read only
 * to reach a factory disappears without needing a slot of its own, and so does the
 * `remember` that existed only for it.
 *
 * The screen-size of the app is not read here at all; it crossed into
 * [AppNavHostDependencies] as two `Int`s, because `LocalConfiguration` has no `commonMain`
 * counterpart by that name and the shared file needs the numbers rather than the local.
 *
 * ## Why `navHostDependencies` is a producer slot
 *
 * `AppNavHost` needs roughly forty platform products, and two of the inputs to building
 * them are composition locals, so the object cannot be built by a caller that has no
 * context -- which is what this file now is. The slot is invoked inside `AppNavHost`'s own
 * composable scope, so the `remember`s it uses are still *someone's* remembers: the ones in
 * the Android file, keyed on the Android values. Nothing here reads a platform type.
 *
 * ## `findStartDestination` is not a pin
 *
 * Worth recording because the census filed this file under `navigation` on this import.
 * `NavGraph.Companion.findStartDestination` is declared in `navigation-common`'s **`commonMain`**
 * metadata, so it is available on every target and this call site compiles everywhere as it
 * stands. **A token in an import line says nothing about whether its declaration is common** --
 * that is the import audit's version of "a census bucket is a floor, not a description".
 *
 * ## Visibility
 *
 * `public`, not `internal`: `:android:desktopApp` is a separate Gradle module and a module
 * cannot see another module's `internal`. The desktop entry point calls this with a bundle
 * built by its own producer, so widening this exposes no wiring -- it makes the shared shell
 * callable off Android, which is the whole point of the file living in `commonMain`.
 */
@Composable
fun AppRoot(
    bootstrapViewModelFactory: ViewModelProvider.Factory,
    authViewModelFactory: ViewModelProvider.Factory,
    profileListViewModelFactory: ViewModelProvider.Factory,
    navHostDependencies: @Composable () -> AppNavHostDependencies,
) {
    AppBootstrapGate(
        bootstrapViewModelFactory = bootstrapViewModelFactory,
        authViewModelFactory = authViewModelFactory,
        profileListViewModelFactory = profileListViewModelFactory,
        ready = { onSignedOut ->
            MainAppShell(
                onSignedOut = onSignedOut,
                navHostDependencies = navHostDependencies,
            )
        },
    )
}

@Composable
private fun MainAppShell(
    onSignedOut: () -> Unit,
    navHostDependencies: @Composable () -> AppNavHostDependencies,
) {
    val navController = rememberNavController()
    val destinations = remember { TopLevelDestination.entries }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val topLevelRoutes = remember(destinations) { destinations.map { it.route }.toSet() }
    val showBar = currentRoute == null || topLevelRoutes.contains(currentRoute)

    val bottomBarOverlayPadding = remember(showBar) {
        if (showBar) FloatingBarHeight + FloatingBarBottomMargin else 0.dp
    }

    val onDestinationClick: (TopLevelDestination, Boolean) -> Unit = remember(navController) {
        { destination: TopLevelDestination, isSelected: Boolean ->
            if (isSelected) {
                val entry = navController.currentBackStackEntry
                val current = entry?.savedStateHandle?.get<Int>(AppRoutes.TopLevelScrollToTopRequestKey) ?: 0
                entry?.savedStateHandle?.set(AppRoutes.TopLevelScrollToTopRequestKey, current + 1)
            } else {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val onSearchClick: () -> Unit = remember(navController) {
        {
            navController.navigate(AppRoutes.SearchRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // The bar sits outside `NavHost`, so a peeling page slides under a bar that
    // would otherwise stay put. It fades with the same gesture the pages read
    // -- and over the same settle constant, so bar and pages finish together.
    // Snapping it on `active` would be one frame early on release, the same
    // reason the corner uses a chaser rather than the raw boolean.
    val peel = rememberPredictivePeel()
    val bottomBarAlpha by animateFloatAsState(
        targetValue = if (peel.active) 0f else 1f,
        animationSpec = tween(PredictiveSettleDurationMillis),
        label = "predictivePeelBar",
    )

    CompositionLocalProvider(LocalBottomBarOverlayPadding provides bottomBarOverlayPadding) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues),
            ) {
                AppNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                    onSignedOut = onSignedOut,
                    dependencies = navHostDependencies,
                )
                if (showBar) {
                    FloatingBottomBar(
                        destinations = destinations,
                        currentRoute = currentRoute,
                        onDestinationClick = onDestinationClick,
                        onSearchClick = onSearchClick,
                        modifier = Modifier.align(Alignment.BottomCenter).alpha(bottomBarAlpha),
                    )
                }
            }
        }
    }
}