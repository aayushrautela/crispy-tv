package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.discover.DiscoverRoute

internal fun NavGraphBuilder.addDiscoverNavGraph(navController: NavHostController) {
    composable(AppRoutes.DiscoverRoute) { entry ->
        DiscoverRoute(
            onOpenSearch = {
                navController.navigate(AppRoutes.SearchRoute) {
                    launchSingleTop = true
                }
            },
            onOpenAccountsProfiles = {
                navController.navigate(AppRoutes.AccountsProfilesRoute)
            },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
            onItemClick = { item -> navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type)) }
        )
    }
}
