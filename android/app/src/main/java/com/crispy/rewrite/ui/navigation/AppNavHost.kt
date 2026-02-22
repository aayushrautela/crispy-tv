package com.crispy.rewrite.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Home.route,
        modifier = modifier
    ) {
        addHomeNavGraph(navController)
        addSearchNavGraph(navController)
        addDiscoverNavGraph(navController)
        addLibraryNavGraph(navController)
        addSettingsNavGraph(navController)
    }
}
