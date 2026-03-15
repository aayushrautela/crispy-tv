package com.crispy.tv.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppNavigationBar
import com.crispy.tv.ui.navigation.AppNavigationRail
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.TopLevelDestination

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val configuration = LocalConfiguration.current
    val topLevelDestinations = remember { TopLevelDestination.entries }
    val topLevelRoutes = remember(topLevelDestinations) { topLevelDestinations.map { it.route }.toSet() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val shouldShowNavigationBar = currentRoute == null || topLevelRoutes.contains(currentRoute)
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val showRail = shouldShowNavigationBar && isLandscape
    val bottomSystemInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val onTopLevelDestinationClick = remember(navController) {
        { destination: TopLevelDestination, isSelected: Boolean ->
            if (isSelected) {
                val currentEntry = navController.currentBackStackEntry
                val currentRequest =
                    currentEntry
                        ?.savedStateHandle
                        ?.get<Int>(AppRoutes.TopLevelScrollToTopRequestKey)
                        ?: 0
                currentEntry?.savedStateHandle?.set(
                    AppRoutes.TopLevelScrollToTopRequestKey,
                    currentRequest + 1,
                )
            } else {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = if (showRail) bottomSystemInset else 0.dp),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (shouldShowNavigationBar && !showRail) {
                AppNavigationBar(
                    navigationItems = topLevelDestinations,
                    currentRoute = currentRoute,
                    onItemClick = onTopLevelDestinationClick,
                )
            }
        },
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            if (showRail) {
                AppNavigationRail(
                    navigationItems = topLevelDestinations,
                    currentRoute = currentRoute,
                    onItemClick = onTopLevelDestinationClick,
                )
            }
            AppNavHost(
                navController = navController,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}
