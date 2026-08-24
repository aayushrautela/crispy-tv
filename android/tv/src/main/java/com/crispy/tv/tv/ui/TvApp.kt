package com.crispy.tv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import com.crispy.tv.tv.ui.components.SidebarNavigation
import com.crispy.tv.tv.ui.navigation.TvDestination
import com.crispy.tv.tv.ui.screens.HomeScreen
import com.crispy.tv.tv.ui.screens.LibraryScreen
import com.crispy.tv.tv.ui.screens.SearchScreen
import com.crispy.tv.tv.ui.screens.SettingsScreen

@Composable
fun TvApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = TvDestination.fromRoute(backStackEntry?.destination?.route)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
    ) {
        SidebarNavigation(
            selected = selected,
            onSelect = { destination ->
                if (destination != selected) {
                    navController.navigate(destination.route) {
                        popUpTo(TvDestination.default.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        )
        NavHost(
            navController = navController,
            startDestination = TvDestination.default.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TvDestination.Home.route) { HomeScreen() }
            composable(TvDestination.Search.route) { SearchScreen() }
            composable(TvDestination.Library.route) { LibraryScreen() }
            composable(TvDestination.Settings.route) { SettingsScreen() }
        }
    }
}
