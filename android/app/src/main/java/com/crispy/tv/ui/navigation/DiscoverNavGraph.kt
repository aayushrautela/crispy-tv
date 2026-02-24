package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.discover.DiscoverRoute

internal fun NavGraphBuilder.addDiscoverNavGraph(navController: NavHostController) {
    composable(AppRoutes.DiscoverRoute) {
        DiscoverRoute(
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type)) }
        )
    }
}
