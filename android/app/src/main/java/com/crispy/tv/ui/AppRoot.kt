package com.crispy.tv.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.TopLevelDestination

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute by remember(navBackStackEntry) {
        derivedStateOf { navBackStackEntry?.destination?.route }
    }
    val currentDestination by remember(currentRoute) {
        derivedStateOf { TopLevelDestination.entries.firstOrNull { it.route == currentRoute } }
    }
    val bottomBarDestinations = remember { TopLevelDestination.entries.filter { it.showInBottomBar } }
    val showBottomBar = currentDestination?.showInBottomBar == true
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    bottomBarDestinations.forEach { destination ->
                        val selected =
                            currentRoute == destination.route ||
                                currentRoute?.startsWith("${destination.route}/") == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    val currentEntry = navController.currentBackStackEntry
                                    val currentRequest =
                                        currentEntry?.savedStateHandle?.get<Int>(AppRoutes.TopLevelScrollToTopRequestKey) ?: 0
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
                            },
                            icon = {
                                androidx.compose.material3.Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues),
        )
    }
}
