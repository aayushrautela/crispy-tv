package com.crispy.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.accounts.AppBootstrapViewModel
import com.crispy.tv.accounts.AuthRoute
import com.crispy.tv.accounts.BootstrapState
import com.crispy.tv.accounts.ProfileSelectorRoute
import com.crispy.tv.ui.brand.CrispyIntroSplash
import com.crispy.tv.ui.edge_to_edge.LocalBottomBarOverlayPadding
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppNavHostDependencies
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.FloatingBarBottomMargin
import com.crispy.tv.ui.navigation.FloatingBarHeight
import com.crispy.tv.ui.navigation.FloatingBottomBar
import com.crispy.tv.ui.navigation.TopLevelDestination
import kotlinx.coroutines.delay

private const val IntroTimeoutMs = 3_000L

/**
 * The app's root composable: intro, then auth, then profile selection, then the shell.
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
 */
@Composable
internal fun AppRoot(
    bootstrapViewModelFactory: ViewModelProvider.Factory,
    authViewModelFactory: ViewModelProvider.Factory,
    profileListViewModelFactory: ViewModelProvider.Factory,
    navHostDependencies: @Composable () -> AppNavHostDependencies,
) {
    val bootstrapViewModel: AppBootstrapViewModel = viewModel(factory = bootstrapViewModelFactory)
    val state by bootstrapViewModel.state.collectAsStateWithLifecycle()
    var introDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(IntroTimeoutMs)
        introDone = true
    }

    // Hold the splash until the intro has played through even if bootstrap
    // resolves first; afterwards the finished frame holds until data arrives.
    when {
        state == BootstrapState.Loading || !introDone -> {
            CrispyIntroSplash(
                playIntro = !introDone,
                onFinished = { introDone = true },
            )
        }
        state == BootstrapState.NeedsAuth -> {
            AuthRoute(
                onSignedIn = { bootstrapViewModel.refresh() },
                viewModelFactory = authViewModelFactory,
            )
        }
        state == BootstrapState.NeedsProfileSelection -> {
            ProfileSelectorRoute(
                onComplete = { bootstrapViewModel.refresh() },
                onBack = { bootstrapViewModel.onSignedOut() },
                viewModelFactory = profileListViewModelFactory,
            )
        }
        state == BootstrapState.Ready -> {
            MainAppShell(
                onSignedOut = { bootstrapViewModel.onSignedOut() },
                navHostDependencies = navHostDependencies,
            )
        }
    }
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
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}