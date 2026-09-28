package com.crispy.tv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private const val TopLevelNavigationDurationMillis = 200
private const val TopLevelNavigationOffsetDivisor = 8
private const val OverlayNavigationDurationMillis = 220

private val topLevelRouteIndices = TopLevelDestination.entries.mapIndexed { index, destination -> destination.route to index }.toMap()

private enum class NavigationRole { TopLevel, Overlay, Detail }

private fun roleOf(route: String?): NavigationRole {
    return when {
        topLevelRouteIndices.containsKey(route) -> NavigationRole.TopLevel
        route == AppRoutes.SearchRoute -> NavigationRole.Overlay
        else -> NavigationRole.Detail
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onSignedOut: () -> Unit = {},
) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Home.route,
                modifier = modifier,
                enterTransition = {
                    when {
                        roleOf(targetState.destination.route) == NavigationRole.Overlay -> overlayEnterFromRight()
                        roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                            roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                            if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                                tabEnterFromRight()
                            } else {
                                tabEnterFromLeft()
                            }
                        }
                        else -> EnterTransition.None
                    }
                },
                exitTransition = {
                    when {
                        roleOf(targetState.destination.route) == NavigationRole.Overlay -> ExitTransition.None
                        roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                            roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                            if (topLevelRouteIndex(targetState.destination.route) > topLevelRouteIndex(initialState.destination.route)) {
                                tabExitToLeft()
                            } else {
                                tabExitToRight()
                            }
                        }
                        else -> ExitTransition.None
                    }
                },
                popEnterTransition = {
                    when {
                        roleOf(initialState.destination.route) == NavigationRole.Overlay -> EnterTransition.None
                        roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                            roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                            if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                                tabEnterFromRight()
                            } else {
                                tabEnterFromLeft()
                            }
                        }
                        else -> EnterTransition.None
                    }
                },
                popExitTransition = {
                    when {
                        roleOf(initialState.destination.route) == NavigationRole.Overlay -> overlayExitToRight()
                        roleOf(initialState.destination.route) == NavigationRole.TopLevel &&
                            roleOf(targetState.destination.route) == NavigationRole.TopLevel -> {
                            if (topLevelRouteIndex(initialState.destination.route) < topLevelRouteIndex(targetState.destination.route)) {
                                tabExitToLeft()
                            } else {
                                tabExitToRight()
                            }
                        }
                        else -> ExitTransition.None
                    }
                },
            ) {
                addHomeNavGraph(navController)
                addSearchNavGraph(navController)
                addDiscoverNavGraph(navController)
                addLibraryNavGraph(navController)
                addSettingsNavGraph(navController)
                addAccountNavGraph(
                    navController = navController,
                    onSignedOut = onSignedOut,
                )
                addPlayerDestination(navController)
            }
        }
    }
}

private fun tabEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabEnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        initialOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeIn(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> -fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun tabExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TopLevelNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth / TopLevelNavigationOffsetDivisor },
    ) + fadeOut(animationSpec = tween(TopLevelNavigationDurationMillis))

private fun overlayEnterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        initialOffsetX = { fullWidth -> fullWidth },
    ) + fadeIn(animationSpec = tween(OverlayNavigationDurationMillis))

private fun overlayExitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(OverlayNavigationDurationMillis),
        targetOffsetX = { fullWidth -> fullWidth },
    ) + fadeOut(animationSpec = tween(OverlayNavigationDurationMillis))

private fun topLevelRouteIndex(route: String?): Int {
    return topLevelRouteIndices[route] ?: -1
}
