package com.crispy.tv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Home.route,
        modifier = modifier,
        enterTransition = {
            if (isSearchInvolved()) {
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = SEARCH_FADE_IN_MS,
                            delayMillis = SEARCH_FADE_OUT_MS,
                            easing = LinearOutSlowInEasing
                        )
                ) +
                    scaleIn(
                        initialScale = SEARCH_ENTER_SCALE,
                        animationSpec = tween(durationMillis = SEARCH_DURATION_MS, easing = LinearOutSlowInEasing)
                    )
            } else {
                EnterTransition.None
            }
        },
        exitTransition = {
            if (isSearchInvolved()) {
                fadeOut(
                    animationSpec = tween(durationMillis = SEARCH_FADE_OUT_MS, easing = FastOutLinearInEasing)
                ) +
                    scaleOut(
                        targetScale = SEARCH_EXIT_SCALE_FORWARD,
                        animationSpec = tween(durationMillis = SEARCH_DURATION_MS, easing = FastOutLinearInEasing)
                    )
            } else {
                ExitTransition.None
            }
        },
        popEnterTransition = {
            if (isSearchInvolved()) {
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = SEARCH_FADE_IN_MS,
                            delayMillis = SEARCH_FADE_OUT_MS,
                            easing = LinearOutSlowInEasing
                        )
                ) +
                    scaleIn(
                        initialScale = SEARCH_ENTER_SCALE_POP,
                        animationSpec = tween(durationMillis = SEARCH_DURATION_MS, easing = LinearOutSlowInEasing)
                    )
            } else {
                EnterTransition.None
            }
        },
        popExitTransition = {
            if (isSearchInvolved()) {
                fadeOut(
                    animationSpec = tween(durationMillis = SEARCH_FADE_OUT_MS, easing = FastOutLinearInEasing)
                ) +
                    scaleOut(
                        targetScale = SEARCH_EXIT_SCALE_POP,
                        animationSpec = tween(durationMillis = SEARCH_DURATION_MS, easing = FastOutLinearInEasing)
                    )
            } else {
                ExitTransition.None
            }
        }
    ) {
        addHomeNavGraph(navController)
        addSearchNavGraph(navController)
        addDiscoverNavGraph(navController)
        addLibraryNavGraph(navController)
        addSettingsNavGraph(navController)
    }
}

private const val SEARCH_DURATION_MS = 300
private const val SEARCH_FADE_OUT_MS = 90
private const val SEARCH_FADE_IN_MS = 210

private const val SEARCH_ENTER_SCALE = 0.92f
private const val SEARCH_EXIT_SCALE_FORWARD = 1.05f

private const val SEARCH_ENTER_SCALE_POP = 1.05f
private const val SEARCH_EXIT_SCALE_POP = 0.92f

private fun androidx.compose.animation.AnimatedContentTransitionScope<NavBackStackEntry>.isSearchInvolved(): Boolean {
    val fromRoute = initialState.destination.route
    val toRoute = targetState.destination.route
    return fromRoute == AppRoutes.SearchRoute || toRoute == AppRoutes.SearchRoute
}
