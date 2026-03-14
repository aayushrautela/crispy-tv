package com.crispy.tv.ui.navigation

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.tv.search.SearchResultsRoute
import com.crispy.tv.search.SearchRoute

private const val SearchNavigationDurationMillis = 200

internal fun NavGraphBuilder.addSearchNavGraph(navController: NavHostController) {
    composable(AppRoutes.SearchRoute) { entry ->
        SearchRoute(
            onBack = { navController.navigateUp() },
            onSearchRequested = { query ->
                navController.navigate(AppRoutes.searchResultsRoute(query)) {
                    launchSingleTop = true
                }
            },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
        )
    }

    composable(
        route = AppRoutes.SearchResultsRoutePattern,
        arguments = listOf(navArgument(AppRoutes.SearchQueryArg) { type = NavType.StringType }),
        enterTransition = {
            fadeIn(animationSpec = tween(durationMillis = 250))
        },
        exitTransition = {
            val targetRoute = targetState.destination.route
            if (targetRoute == AppRoutes.SearchRoute) {
                fadeOut(animationSpec = tween(durationMillis = SearchNavigationDurationMillis))
            } else {
                fadeOut(animationSpec = tween(durationMillis = SearchNavigationDurationMillis)) +
                    slideOutHorizontally(animationSpec = tween(durationMillis = SearchNavigationDurationMillis)) { fullWidth ->
                        -fullWidth / 8
                    }
            }
        },
        popEnterTransition = {
            val initialRoute = initialState.destination.route
            if (initialRoute == AppRoutes.SearchRoute) {
                fadeIn(animationSpec = tween(durationMillis = 250))
            } else {
                fadeIn(animationSpec = tween(durationMillis = SearchNavigationDurationMillis)) +
                    slideInHorizontally(animationSpec = tween(durationMillis = SearchNavigationDurationMillis)) { fullWidth ->
                        fullWidth / 8
                    }
            }
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(durationMillis = SearchNavigationDurationMillis))
        },
    ) { entry ->
        SearchResultsRoute(
            initialQuery = Uri.decode(entry.arguments?.getString(AppRoutes.SearchQueryArg).orEmpty()),
            onBack = { navController.navigateUp() },
            onSearchRequested = { query ->
                navController.navigate(AppRoutes.searchResultsRoute(query)) {
                    popUpTo(AppRoutes.SearchRoute)
                    launchSingleTop = true
                }
            },
            onItemClick = { item ->
                if (item.type.equals("person", ignoreCase = true)) {
                    navController.navigate(AppRoutes.personDetailsRoute(item.id))
                } else {
                    navController.navigate(AppRoutes.homeDetailsRoute(item.id, item.type))
                }
            },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
        )
    }
}
