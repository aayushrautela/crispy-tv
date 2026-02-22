package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) {
        LibraryRoute(
            onItemClick = { entry -> navController.navigate(AppRoutes.homeDetailsRoute(entry.contentId)) },
            onNavigateToDiscover = { navController.navigate(AppRoutes.DiscoverRoute) }
        )
    }
}
