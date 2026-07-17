package com.crispy.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.accounts.AppBootstrapViewModel
import com.crispy.tv.accounts.AuthRoute
import com.crispy.tv.accounts.BootstrapState
import com.crispy.tv.accounts.OnboardingRoute
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.FloatingBottomBar
import com.crispy.tv.ui.navigation.TopLevelDestination

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val bootstrapViewModel: AppBootstrapViewModel =
        viewModel(factory = remember(appContext) { AppBootstrapViewModel.factory(appContext) })
    val state by bootstrapViewModel.state.collectAsStateWithLifecycle()

    when (state) {
        BootstrapState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        BootstrapState.NeedsAuth -> {
            AuthRoute(onSignedIn = { bootstrapViewModel.refresh() })
        }
        BootstrapState.NeedsOnboarding -> {
            OnboardingRoute(
                onComplete = { bootstrapViewModel.refresh() },
                onBack = { bootstrapViewModel.refresh() },
            )
        }
        BootstrapState.Ready -> {
            MainAppShell(onSignedOut = { bootstrapViewModel.onSignedOut() })
        }
    }
}

@Composable
private fun MainAppShell(onSignedOut: () -> Unit) {
    val navController = rememberNavController()
    val destinations = remember { TopLevelDestination.entries }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val topLevelRoutes = remember(destinations) { destinations.map { it.route }.toSet() }
    val showBar = currentRoute == null || topLevelRoutes.contains(currentRoute)

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
            Unit
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
