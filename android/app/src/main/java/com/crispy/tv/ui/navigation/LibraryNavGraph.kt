package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) { entry ->
        LibraryRoute(
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.runtimeDetailsRoute(
                        provider = item.provider,
                        providerId = item.providerId,
                        mediaType = item.mediaType,
                    )
                )
            },
            onOpenCalendar = {
                navController.navigate(AppRoutes.CalendarRoute) {
                    launchSingleTop = true
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
