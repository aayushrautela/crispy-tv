package com.crispy.tv.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.AccountSettingsRoute
import com.crispy.tv.accounts.AuthRoute
import com.crispy.tv.accounts.OnboardingRoute
import com.crispy.tv.accounts.ProfileManagementRoute

internal fun NavGraphBuilder.addAuthNavGraph(
    navController: NavHostController,
    onAuthedAndOnboarded: () -> Unit,
    onSignedOut: () -> Unit,
) {
    composable(AppRoutes.AuthRoute) {
        AuthRoute(onSignedIn = { navController.navigate(AppRoutes.OnboardingRoute) })
    }

    composable(AppRoutes.OnboardingRoute) {
        OnboardingRoute(
            onComplete = onAuthedAndOnboarded,
            onBack = { navController.popBackStack() },
        )
    }

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
