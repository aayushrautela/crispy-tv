package com.crispy.tv.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.BuildConfig
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.settings.AccountsProfilesRoute
import com.crispy.tv.settings.AddonsSettingsRoute
import com.crispy.tv.settings.AiInsightsSettingsRoute
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.settings.PlaybackSettingsScreen
import com.crispy.tv.settings.ProviderAuthPortalRoute
import com.crispy.tv.settings.SettingsScreen
import com.crispy.tv.sync.ProfileDataCloudSync
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.addSettingsNavGraph(navController: NavHostController) {
    composable(AppRoutes.SettingsRoute) {
        SettingsScreen(
            onNavigateToAddonsSettings = {
                navController.navigate(AppRoutes.AddonsSettingsRoute)
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
            }
        )
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

    composable(AppRoutes.AccountsProfilesRoute) {
        AccountsProfilesRoute(onBack = { navController.popBackStack() })
    }

    composable(AppRoutes.PlaybackSettingsRoute) {
        val context = LocalContext.current
        val appContext = remember(context) { context.applicationContext }
        val coroutineScope = rememberCoroutineScope()

        val httpClient = remember(appContext) { AppHttp.client(appContext) }
        val supabase = remember(appContext, httpClient) {
            SupabaseAccountClient(
                appContext = appContext,
                httpClient = httpClient,
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY
            )
        }
        val cloudSync = remember(appContext, supabase) { ProfileDataCloudSync(appContext, supabase) }

        val playbackSettingsRepository = remember(appContext) {
            PlaybackSettingsRepositoryProvider.get(appContext)
        }
        val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()

        PlaybackSettingsScreen(
            settings = playbackSettings,
            onSkipIntroChanged = { enabled ->
                playbackSettingsRepository.setSkipIntroEnabled(enabled)
                coroutineScope.launch { cloudSync.pushForActiveProfile() }
            },
            onBack = { navController.popBackStack() }
        )
    }
}
