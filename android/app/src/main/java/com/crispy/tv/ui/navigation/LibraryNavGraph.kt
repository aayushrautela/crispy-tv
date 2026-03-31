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
                    AppRoutes.homeDetailsRoute(
                        itemId = item.detailsTitleId,
                        mediaType = item.detailsTitleMediaType,
                        initialSeason = item.seasonNumber,
                        initialEpisode = item.episodeNumber,
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
