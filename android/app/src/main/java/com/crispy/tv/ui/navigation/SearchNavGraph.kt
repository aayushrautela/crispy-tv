package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.search.SearchRoute

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) {
        SearchRoute(
            onBack = { navController.popBackStack() },
            onItemClick = { item ->
                if (item.type.equals("person", ignoreCase = true)) {
                    navController.navigate(AppRoutes.personDetailsRoute(item.id))
                } else {
                    navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type))
                }
            }
        )
    }
}
