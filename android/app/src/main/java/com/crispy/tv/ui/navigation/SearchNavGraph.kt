package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.search.SearchRoute

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) {
        SearchRoute(
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id)) }
        )
    }
}
