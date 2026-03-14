package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) { entry ->
        LibraryRoute(
            onItemClick = { entry ->
                val type = when (entry.contentType) {
                    com.crispy.tv.player.MetadataLabMediaType.MOVIE -> "movie"
                    com.crispy.tv.player.MetadataLabMediaType.SERIES -> "series"
                }
                navController.navigate(
                    AppRoutes.homeDetailsRoute(
                        itemId = entry.contentId,
                        mediaType = type,
                        initialSeason = entry.season,
                        initialEpisode = entry.episode,
                    )
                )
            },
            onNavigateToDiscover = { navController.navigate(AppRoutes.DiscoverRoute) },
            onOpenSearch = {
                navController.navigate(AppRoutes.SearchRoute) {
                    launchSingleTop = true
                }
            },
            onOpenCalendar = { navController.navigate(AppRoutes.CalendarRoute) },
            onOpenAccountsProfiles = { navController.navigate(AppRoutes.AccountsProfilesRoute) },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
        )
    }
}
