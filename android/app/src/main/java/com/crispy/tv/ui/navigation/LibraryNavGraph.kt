package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.runtime.CompositionLocalProvider
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) { entry ->
        CompositionLocalProvider(LocalNavAnimatedContentScope provides this@composable) {
            LibraryRoute(
            onItemClick = { item ->
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        itemId = item.itemId,
                        itemType = item.type,
                        backdropUrl = item.backdropUrl,
                        logoUrl = item.logoUrl,
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
}
