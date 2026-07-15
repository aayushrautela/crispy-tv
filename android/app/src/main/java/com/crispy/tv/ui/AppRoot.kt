package com.crispy.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.FloatingBottomBar
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.TopLevelDestination

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val topLevelDestinations = remember { TopLevelDestination.entries }
    val topLevelRoutes = remember(topLevelDestinations) { topLevelDestinations.map { it.route }.toSet() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val shouldShowNavigationBar = currentRoute == null || topLevelRoutes.contains(currentRoute)

    val onTopLevelDestinationClick: (TopLevelDestination, Boolean) -> Unit = remember(navController) {
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
                Unit
            } else {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
                Unit
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
            if (shouldShowNavigationBar) {
                FloatingBottomBar(
                    items = topLevelDestinations,
                    currentRoute = currentRoute,
                    onItemClick = onTopLevelDestinationClick,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
