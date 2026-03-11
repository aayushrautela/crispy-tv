package com.crispy.tv.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

private const val NavigationSlideDurationMillis = 320
private const val NavigationFadeDurationMillis = 120

private val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()

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
            if (isTopLevelSwitch()) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(
                        durationMillis = NavigationSlideDurationMillis,
                        easing = FastOutSlowInEasing
                    )
                ) +
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = NavigationFadeDurationMillis,
                            delayMillis = NavigationFadeDurationMillis / 3
                        )
                    )
            }
        },
        exitTransition = {
            if (isTopLevelSwitch()) {
                ExitTransition.None
            } else {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = NavigationFadeDurationMillis,
                        easing = LinearOutSlowInEasing
                    )
                )
            }
        },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            if (isTopLevelSwitch()) {
                ExitTransition.None
            } else {
                scaleOut(
                    targetScale = 0.9f,
                    transformOrigin = TransformOrigin(
                        pivotFractionX = 0.5f,
                        pivotFractionY = 0.5f
                    )
                )
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

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTopLevelSwitch(): Boolean {
    val initialRoute = initialState.destination.route
    val targetRoute = targetState.destination.route
    return initialRoute in topLevelRoutes && targetRoute in topLevelRoutes
}
