package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.AccountSettingsRoute
import com.crispy.tv.accounts.ProfileManagementRoute

internal fun NavGraphBuilder.addAccountNavGraph(
    navController: NavHostController,
    onSignedOut: () -> Unit,
) {
    composable(AppRoutes.ProfileManagementRoute) {
        ProfileManagementRoute(
            onBack = { navController.popBackStack() },
            onOpenAccountSettings = { navController.navigate(AppRoutes.AccountSettingsRoute) },
        )
    }

    composable(AppRoutes.AccountSettingsRoute) {
        AccountSettingsRoute(
            onBack = { navController.popBackStack() },
            onSignedOut = onSignedOut,
        )
    }
}
