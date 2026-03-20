package com.crispy.tv.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.settings.AccountsProfilesRoute
import com.crispy.tv.settings.AddonsSettingsRoute
import com.crispy.tv.settings.AiInsightsSettingsRoute
import com.crispy.tv.settings.MetadataSettingsRoute
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.settings.PlaybackSettingsScreen
import com.crispy.tv.settings.ProviderAuthPortalRoute
import com.crispy.tv.settings.SettingsScreen
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.addSettingsNavGraph(navController: NavHostController) {
    composable(AppRoutes.SettingsRoute) { entry ->
        SettingsScreen(
            onNavigateToAddonsSettings = {
                navController.navigate(AppRoutes.AddonsSettingsRoute)
            },
            onNavigateToMetadataSettings = {
                navController.navigate(AppRoutes.MetadataSettingsRoute)
            },
            onNavigateToPlaybackSettings = {
                navController.navigate(AppRoutes.PlaybackSettingsRoute)
            },
            onNavigateToAiInsightsSettings = {
                navController.navigate(AppRoutes.AiInsightsSettingsRoute)
            },
            onNavigateToProviderPortal = {
                navController.navigate(AppRoutes.ProviderPortalRoute)
            },
            onNavigateToAccountsProfiles = {
                navController.navigate(AppRoutes.AccountsProfilesRoute)
            },
            scrollToTopRequests = entry.savedStateHandle.getStateFlow(AppRoutes.TopLevelScrollToTopRequestKey, 0),
            onScrollToTopConsumed = {
                entry.savedStateHandle[AppRoutes.TopLevelScrollToTopRequestKey] = 0
            },
        )
    }

    composable(AppRoutes.AddonsSettingsRoute) {
        AddonsSettingsRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.AiInsightsSettingsRoute) {
        AiInsightsSettingsRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.MetadataSettingsRoute) {
        MetadataSettingsRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.ProviderPortalRoute) {
        ProviderAuthPortalRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.AccountsProfilesRoute) {
        AccountsProfilesRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.PlaybackSettingsRoute) {
        val context = LocalContext.current
        val appContext = remember(context) { context.applicationContext }
        val coroutineScope = rememberCoroutineScope()

        val cloudSync = remember(appContext) { SupabaseServicesProvider.createProfileDataCloudSync(appContext) }

        val playbackSettingsRepository = remember(appContext) {
            PlaybackSettingsRepositoryProvider.get(appContext)
        }
        val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()

        PlaybackSettingsScreen(
            settings = playbackSettings,
            onTrailerAutoplayChanged = { enabled ->
                playbackSettingsRepository.setTrailerAutoplayEnabled(enabled)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onSkipIntroChanged = { enabled ->
                playbackSettingsRepository.setSkipIntroEnabled(enabled)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onBack = { navController.popBackStack() }
        )
    }
}
