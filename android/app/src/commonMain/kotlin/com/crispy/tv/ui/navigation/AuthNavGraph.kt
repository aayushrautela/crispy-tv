package com.crispy.tv.ui.navigation

import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.crispy.tv.accounts.AccountSettingsRoute
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.accounts.ProfileManagementRoute
import com.crispy.tv.accounts.ProfileMenuRoute

/**
 * The three account destinations, and the second `commonMain` file in this layer.
 *
 * **It is here because the two pins that held it are both below its own imports,
 * and one of them is a comment that named the wrong file as the only home.**
 * `coil3.compose.LocalPlatformContext` was never a pin — `coil3` declares it in
 * its `commonMain`. What was a pin is `.applicationContext` on the value it
 * reads, and the three `Context`-taking factory functions this file *calls*.
 * `AccountSettingsRoute`'s own KDoc described the fix for years — *"the platform
 * hoists the whole `LocalContext -> applicationContext -> remember -> factory`
 * block into the nav graph and passes the result here"* — and it was right about
 * the mechanism and wrong about the consequence: the block has to be hoisted
 * into a `commonMain` file, so it could not stay in a nav graph, so it could
 * not stay in `androidMain`, and the file that names the mechanism was the file
 * that had to move.
 *
 * **The two factory slots are products and `loadProfile` is a lambda, and the
 * difference is not a style choice.** `ProfileManagementRoute:798` and
 * `AccountSettingsRoute:1062` both do `viewModel(factory = viewModelFactory)`
 * and hand it straight on, so the slot is the remembered `ViewModelProvider.Factory`
 * itself — and `AccountSettingsRoute`'s KDoc says why in as many words:
 * *"A plain value, not a composable slot: `viewModel(factory = ...)` keys on
 * factory identity, so a composable slot would be re-invoked every recomposition
 * and defeat the `remember` that keeps the store alive."* `loadProfile` is the
 * opposite case and must stay a lambda: `ProfileMenuRoute:98` is
 * `produceState<ActiveProfileInfo?>(initialValue = null, loadProfile)`, so the
 * lambda **is a `produceState` key** and its identity is load-bearing. A fresh
 * lambda each recomposition would restart the profile load. *Read what the
 * consumer does with the value before choosing the slot's type — a value it
 * stores must stay a lambda, and a value it merely passes on may be the product.*
 *
 * `onSignedOut` is a plain capability and crosses unchanged.
 */
fun NavGraphBuilder.addAccountNavGraph(
    navController: NavHostController,
    onSignedOut: () -> Unit,
    profileListFactory: ViewModelProvider.Factory,
    accountSettingsFactory: ViewModelProvider.Factory,
    loadProfile: suspend () -> ActiveProfileInfo?,
) {
    composable(AppRoutes.ProfileManagementRoute) {
        PredictivePeelContainer {
        ProfileManagementRoute(
            onBack = { navController.popBackStack() },
            onOpenAccountSettings = { navController.navigate(AppRoutes.AccountSettingsRoute) },
            viewModelFactory = profileListFactory,
        )
        }
    }

    composable(AppRoutes.AccountSettingsRoute) {
        PredictivePeelContainer {
        AccountSettingsRoute(
            onBack = { navController.popBackStack() },
            onSignedOut = onSignedOut,
            viewModelFactory = accountSettingsFactory,
        )
        }
    }

    composable(AppRoutes.ProfileMenuRoute) {
        PredictivePeelContainer {
        ProfileMenuRoute(
            onOpenSettings = { navController.navigate(AppRoutes.SettingsRoute) },
            onManageProfiles = { navController.navigate(AppRoutes.AccountsProfilesRoute) },
            onSignOut = onSignedOut,
            onBack = { navController.popBackStack() },
            loadProfile = loadProfile,
        )
        }
    }
}
