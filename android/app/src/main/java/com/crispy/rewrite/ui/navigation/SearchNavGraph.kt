package com.crispy.rewrite.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.rewrite.search.SearchRoute

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) {
        SearchRoute(
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id)) }
        )
    }
}
