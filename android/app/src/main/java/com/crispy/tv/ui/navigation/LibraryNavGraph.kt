package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.library.LibraryRoute

internal fun NavGraphBuilder.addLibraryNavGraph(navController: NavHostController) {
    composable(AppRoutes.LibraryRoute) {
        LibraryRoute(
            onItemClick = { entry ->
                val type = when (entry.contentType) {
                    com.crispy.tv.player.MetadataLabMediaType.MOVIE -> "movie"
                    com.crispy.tv.player.MetadataLabMediaType.SERIES -> "series"
                }
                navController.navigate(AppRoutes.homeDetailsRoute(entry.contentId, type))
            },
            onNavigateToDiscover = { navController.navigate(AppRoutes.DiscoverRoute) }
        )
    }
}
