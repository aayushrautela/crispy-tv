package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.compose.runtime.remember
import androidx.navigation.compose.composable
import coil3.compose.LocalPlatformContext
import com.crispy.tv.accounts.AccountSettingsRoute
import com.crispy.tv.accounts.accountSettingsViewModelFactory
import com.crispy.tv.accounts.ProfileManagementRoute
import com.crispy.tv.accounts.profileListViewModelFactory
import com.crispy.tv.accounts.ProfileMenuRoute
import com.crispy.tv.accounts.activeProfileLoader

internal fun NavGraphBuilder.addAccountNavGraph(
    navController: NavHostController,
    onSignedOut: () -> Unit,
) {
    composable(AppRoutes.ProfileManagementRoute) {
        val context = LocalPlatformContext.current
        val appContext = remember(context) { context.applicationContext }
        ProfileManagementRoute(
            onBack = { navController.popBackStack() },
            onOpenAccountSettings = { navController.navigate(AppRoutes.AccountSettingsRoute) },
            viewModelFactory = remember(appContext) { profileListViewModelFactory(appContext) },
        )
    }

    composable(AppRoutes.AccountSettingsRoute) {
        val context = LocalPlatformContext.current
        val appContext = remember(context) { context.applicationContext }
        AccountSettingsRoute(
            onBack = { navController.popBackStack() },
            onSignedOut = onSignedOut,
            viewModelFactory = remember(appContext) { accountSettingsViewModelFactory(appContext) },
        )
    }

    composable(AppRoutes.ProfileMenuRoute) {
        val context = LocalPlatformContext.current
        val appContext = remember(context) { context.applicationContext }
        ProfileMenuRoute(
            onOpenSettings = { navController.navigate(AppRoutes.SettingsRoute) },
            onManageProfiles = { navController.navigate(AppRoutes.AccountsProfilesRoute) },
            onSignOut = onSignedOut,
            onBack = { navController.popBackStack() },
            loadProfile = remember(appContext) { activeProfileLoader(appContext) },
        )
    }
}
