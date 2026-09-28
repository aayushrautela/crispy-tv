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
import com.crispy.tv.accounts.ProfileManagementRoute
import com.crispy.tv.settings.AddonsSettingsRoute
import com.crispy.tv.settings.ImageQuality
import com.crispy.tv.settings.ImageSettingsRepositoryProvider
import com.crispy.tv.settings.ImageSettingsScreen
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.settings.PlaybackSettingsScreen
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.settings.SettingsScreen
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.addSettingsNavGraph(navController: NavHostController) {
    // Read once here rather than per-destination: this function runs while the
    // graph is being built, and the components are installed in
    // CrispyApplication.onCreate, well before any NavHost exists.
    val distribution = AppDistribution.current

    composable(AppRoutes.SettingsRoute) { entry ->
        SettingsScreen(
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
        AddonsSettingsRoute(onBack = { navController.popBackStack() })
    }

    // The plugins destination is registered only when the build has a plugins
    // screen. On store there is no screen to show, and an always-registered
    // destination would let a deep link to it land on a blank page.
    distribution.pluginsSettingsScreen?.let { pluginsScreen ->
        composable(AppRoutes.PluginsSettingsRoute) {
            pluginsScreen { navController.popBackStack() }
        }
    }

    composable(AppRoutes.AccountsProfilesRoute) {
        ProfileManagementRoute(
            onBack = { navController.popBackStack() },
            onOpenAccountSettings = { navController.navigate(AppRoutes.AccountSettingsRoute) },
        )
    }

    composable(AppRoutes.ImageSettingsRoute) {
        val context = LocalContext.current
        val appContext = remember(context) { context.applicationContext }
        val imageSettingsRepository = remember(appContext) {
            ImageSettingsRepositoryProvider.get(appContext)
        }
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
