package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.search.SearchRoute

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) { entry ->
        SearchRoute(
            onBack = { navController.navigateUp() },
            onItemClick = { item ->
                if (item.type.equals("person", ignoreCase = true)) {
                    navController.navigate(AppRoutes.personDetailsRoute(item.id))
                } else {
                    navController.navigate(
                        AppRoutes.runtimeDetailsRoute(
                            provider = item.provider,
                            providerId = item.providerId,
                            mediaType = item.type,
                        )
                    )
                }
            },
            onOpenAccountsProfiles = {
                navController.navigate(AppRoutes.AccountsProfilesRoute) {
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
