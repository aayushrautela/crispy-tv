package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import com.crispy.tv.search.SearchRoute

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            SearchRoute(
            onItemClick = { item, sharedElementKey ->
                if (item.type.equals("person", ignoreCase = true)) {
                    navController.navigate(AppRoutes.personDetailsRoute(item.id, item.artworkUrl))
                } else {
                    navController.navigate(
                        AppRoutes.homeDetailsRoute(
                            itemId = item.itemId,
                            itemType = item.type,
                            artworkUrl = item.artworkUrl,
                            logoUrl = item.logoUrl,
                            sharedElementKey = sharedElementKey,
                        )
                    )
                }
            },
                onOpenAccountsProfiles = {
                    navController.navigate(AppRoutes.ProfileMenuRoute) {
                        launchSingleTop = true
                    }
                },
                scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
                onScrollToTopConsumed = {
                    entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
                },
            )
        }
    }
}
