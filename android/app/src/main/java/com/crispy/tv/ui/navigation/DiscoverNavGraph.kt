package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import com.crispy.tv.discover.DiscoverRoute

internal fun NavGraphBuilder.addDiscoverNavGraph(navController: NavHostController) {
    composable(AppRoutes.DiscoverRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            DiscoverRoute(
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.AccountsProfilesRoute) {
                        launchSingleTop = true
                    }
                },
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        itemId = item.itemId,
                        itemType = item.type,
                        backdropUrl = item.backdropUrl,
                        logoUrl = item.logoUrl,
                    )
                )
            }
            )
        }
    }
}
