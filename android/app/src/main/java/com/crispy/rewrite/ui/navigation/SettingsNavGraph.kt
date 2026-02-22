package com.crispy.rewrite.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.rewrite.settings.AddonsSettingsRoute
import com.crispy.rewrite.settings.HomeScreenSettingsRoute
import com.crispy.rewrite.settings.PlaybackSettingsRepositoryProvider
import com.crispy.rewrite.settings.PlaybackSettingsScreen
import com.crispy.rewrite.settings.ProviderAuthPortalRoute
import com.crispy.rewrite.settings.SettingsScreen
import com.crispy.rewrite.ui.labs.LabsScreen

internal fun NavGraphBuilder.addSettingsNavGraph(navController: NavHostController) {
    composable(AppRoutes.SettingsRoute) {
        SettingsScreen(
            onNavigateToHomeScreenSettings = {
                navController.navigate(AppRoutes.HomeScreenSettingsRoute)
            },
            onNavigateToAddonsSettings = {
                navController.navigate(AppRoutes.AddonsSettingsRoute)
            },
            onNavigateToPlaybackSettings = {
                navController.navigate(AppRoutes.PlaybackSettingsRoute)
            },
            onNavigateToLabs = {
                navController.navigate(AppRoutes.LabsRoute)
            },
            onNavigateToProviderPortal = {
                navController.navigate(AppRoutes.ProviderPortalRoute)
            }
        )
    }

    composable(AppRoutes.HomeScreenSettingsRoute) {
        HomeScreenSettingsRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.AddonsSettingsRoute) {
        AddonsSettingsRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.ProviderPortalRoute) {
        ProviderAuthPortalRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.PlaybackSettingsRoute) {
        val context = LocalContext.current
        val appContext = remember(context) { context.applicationContext }
        val playbackSettingsRepository = remember(appContext) {
            PlaybackSettingsRepositoryProvider.get(appContext)
        }
        val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()

        PlaybackSettingsScreen(
            settings = playbackSettings,
            onSkipIntroChanged = playbackSettingsRepository::setSkipIntroEnabled,
            onBack = { navController.popBackStack() }
        )
    }

    composable(AppRoutes.LabsRoute) {
        LabsScreen()
    }
}
