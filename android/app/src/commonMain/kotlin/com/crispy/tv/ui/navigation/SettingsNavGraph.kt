package com.crispy.tv.ui.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.ProfileManagementRoute
import com.crispy.tv.settings.AddonsSettingsRoute
import com.crispy.tv.settings.ImageQuality
import com.crispy.tv.settings.ImageSettingsScreen
import com.crispy.tv.settings.PlaybackSettingsScreen
import com.crispy.tv.settings.SettingsScreen
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.addSettingsNavGraph(
    navController: NavHostController,
    dependencies: SettingsNavDependencies,
) {
    // `AppDistribution` used to be read here, once, and its two halves are now two
    // members of [SettingsNavDependencies]: `pluginsUiSupported` as the plain
    // `Boolean` it always was, and `pluginsSettingsScreen` as the nullable composable
    // slot it also always was. The `?.let` below is why that second one is a *slot*
    // rather than a screen -- see the comment on the plugins destination, which keeps
    // the rule in the graph because deciding whether a destination exists is a
    // navigation decision and not a wiring one.

    composable(AppRoutes.SettingsRoute) { entry ->
        SettingsScreen(
            pluginsUiSupported = dependencies.pluginsUiSupported,
            onNavigateToAddonsSettings = {
                navController.navigate(AppRoutes.AddonsSettingsRoute)
            },
            onNavigateToPluginsSettings = {
                navController.navigate(AppRoutes.PluginsSettingsRoute)
            },
            onNavigateToPlaybackSettings = {
                navController.navigate(AppRoutes.PlaybackSettingsRoute)
            },
            onNavigateToImageSettings = {
                navController.navigate(AppRoutes.ImageSettingsRoute)
            },
            onNavigateToAccountsProfiles = {
                navController.navigate(AppRoutes.AccountsProfilesRoute)
            },
            onBack = { navController.popBackStack() },
        )
    }

    composable(AppRoutes.AddonsSettingsRoute) {
        AddonsSettingsRoute(
            onBack = { navController.popBackStack() },
            viewModelFactory = dependencies.addonsSettingsViewModelFactory,
        )
    }

    // The plugins destination is registered only when the build has a plugins
    // screen. On store there is no screen to show, and an always-registered
    // destination would let a deep link to it land on a blank page.
    dependencies.pluginsSettingsScreen?.let { pluginsScreen ->
        composable(AppRoutes.PluginsSettingsRoute) {
            pluginsScreen { navController.popBackStack() }
        }
    }

    composable(AppRoutes.AccountsProfilesRoute) {
        ProfileManagementRoute(
            onBack = { navController.popBackStack() },
            onOpenAccountSettings = { navController.navigate(AppRoutes.AccountSettingsRoute) },
            viewModelFactory = dependencies.profileListViewModelFactory,
        )
    }

    composable(AppRoutes.ImageSettingsRoute) {
        val imageSettingsRepository = dependencies.imageSettingsRepository
        val imageSettings by imageSettingsRepository.settings.collectAsStateWithLifecycle()

        ImageSettingsScreen(
            settings = imageSettings,
            onQualityChanged = { quality: ImageQuality ->
                imageSettingsRepository.setQuality(quality)
            },
            onBack = { navController.popBackStack() }
        )
    }

    composable(AppRoutes.PlaybackSettingsRoute) {
        val coroutineScope = rememberCoroutineScope()

        val cloudSync = dependencies.profileDataCloudSync
        val playbackSettingsRepository = dependencies.playbackSettingsRepository
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
            onAutoSelectStreamChanged = { enabled ->
                playbackSettingsRepository.setAutoSelectStream(enabled)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onUseLibassChanged = { enabled ->
                playbackSettingsRepository.setUseLibass(enabled)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onLibassRenderTypeChanged = { renderType ->
                playbackSettingsRepository.setLibassRenderType(renderType)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            playbackEnginePreference = playbackSettings.playbackEnginePreference,
            onPlaybackEnginePreferenceChanged = { preference ->
                playbackSettingsRepository.setPlaybackEnginePreference(preference)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onBack = { navController.popBackStack() }
        )
    }
}
