package com.crispy.tv.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.settings.AddonsSettingsRoute
import com.crispy.tv.settings.AiInsightsSettingsRoute
import com.crispy.tv.settings.HomeScreenSettingsRoute
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.settings.PlaybackSettingsScreen
import com.crispy.tv.settings.ProviderAuthPortalRoute
import com.crispy.tv.settings.SettingsScreen
import com.crispy.tv.ui.labs.LabsScreen

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
            onNavigateToAiInsightsSettings = {
                navController.navigate(AppRoutes.AiInsightsSettingsRoute)
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

    composable(AppRoutes.AiInsightsSettingsRoute) {
        AiInsightsSettingsRoute(onBack = { navController.popBackStack() })
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
