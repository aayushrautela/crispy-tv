package com.crispy.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.FloatingBottomBar
import com.crispy.tv.ui.navigation.TopLevelDestination

private enum class StartupDestination {
    Loading,
    Auth,
    Onboarding,
    Home,
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val bootstrapRepository = remember(appContext) { SupabaseServicesProvider.bootstrapRepository(appContext) }

    var startupDestination by remember { mutableStateOf(StartupDestination.Loading) }

    LaunchedEffect(Unit) {
        val result = runCatching { bootstrapRepository.bootstrap() }.getOrNull()
        startupDestination = when {
            result == null || !result.signedIn -> StartupDestination.Auth
            !result.onboardingComplete -> StartupDestination.Onboarding
            else -> StartupDestination.Home
        }
    }

    when (startupDestination) {
        StartupDestination.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        StartupDestination.Auth -> {
            val navController = rememberNavController()
            AppNavHost(navController = navController, startDestinationOverride = AppRoutes.AuthRoute)
        }
        StartupDestination.Onboarding -> {
            val navController = rememberNavController()
            AppNavHost(navController = navController, startDestinationOverride = AppRoutes.OnboardingRoute)
        }
        StartupDestination.Home -> {
            MainAppShell()
        }
    }
}

@Composable
private fun MainAppShell() {
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
