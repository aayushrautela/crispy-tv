package com.crispy.tv.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private const val TopLevelNavigationDurationMillis = 200
private const val TopLevelNavigationOffsetDivisor = 8

private val topLevelRouteIndices = TopLevelDestination.entries.mapIndexed { index, destination -> destination.route to index }.toMap()

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Home.route,
        modifier = modifier,
        enterTransition = {
            val targetRouteIndex = topLevelRouteIndex(targetState.destination.route)
            val initialRouteIndex = topLevelRouteIndex(initialState.destination.route)
            if (targetRouteIndex == -1 || initialRouteIndex == -1) {
                EnterTransition.None
            } else if (targetRouteIndex > initialRouteIndex) {
                slideInHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    initialOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))
            } else {
                slideInHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    initialOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))
            }
        },
        exitTransition = {
            val initialRouteIndex = topLevelRouteIndex(initialState.destination.route)
            val targetRouteIndex = topLevelRouteIndex(targetState.destination.route)
            if (targetRouteIndex == -1 || initialRouteIndex == -1) {
                ExitTransition.None
            } else if (targetRouteIndex > initialRouteIndex) {
                slideOutHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    targetOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))
            } else {
                slideOutHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    targetOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))
            }
        },
        popEnterTransition = {
            val targetRouteIndex = topLevelRouteIndex(targetState.destination.route)
            val initialRouteIndex = topLevelRouteIndex(initialState.destination.route)
            if (targetRouteIndex == -1 || initialRouteIndex == -1) {
                EnterTransition.None
            } else if (initialRouteIndex < targetRouteIndex) {
                slideInHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    initialOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))
            } else {
                slideInHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    initialOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))
            }
        },
        popExitTransition = {
            val initialRouteIndex = topLevelRouteIndex(initialState.destination.route)
            val targetRouteIndex = topLevelRouteIndex(targetState.destination.route)
            if (targetRouteIndex == -1 || initialRouteIndex == -1) {
                ExitTransition.None
            } else if (initialRouteIndex < targetRouteIndex) {
                slideOutHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    targetOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))
            } else {
                slideOutHorizontally(
                    animationSpec = tween(TopLevelNavigationDurationMillis),
                    targetOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
                ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))
            }
        },
    ) {
        addHomeNavGraph(navController)
        addSearchNavGraph(navController)
        addDiscoverNavGraph(navController)
        addLibraryNavGraph(navController)
        addSettingsNavGraph(navController)
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.topLevelRouteIndex(route: String?): Int {
    return topLevelRouteIndices[route] ?: -1
}
