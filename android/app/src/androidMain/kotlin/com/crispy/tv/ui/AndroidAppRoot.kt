package com.crispy.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.crispy.tv.accounts.appBootstrapViewModelFactory
import com.crispy.tv.accounts.authViewModelFactory
import com.crispy.tv.accounts.profileListViewModelFactory
import com.crispy.tv.ui.navigation.appNavHostDependencies

/**
 * The Android entry point, which is [AppRoot] plus the wiring it can no longer do itself.
 *
 * This file exists so `MainActivity` keeps a one-argument call. The alternative -- having
 * `MainActivity` build the three factories and the navigation bundle -- would put four
 * `remember`s and a composition-local read into an `Activity`, which is the composition root
 * for the *activity*, not for the app's dependency graph. [AppRoot] is the thing that knows
 * what a bootstrap ViewModel factory is for; this is the thing that knows how to make one on
 * Android. That split is the whole shape of the landing, expressed in two declarations.
 *
 * Nothing here decides anything, and the three `remember`s are the same ones that used to be
 * in [AppRoot]: keyed on the application context, so each factory is built once per process
 * rather than once per recomposition. Moving them across a file boundary is not a lifetime
 * change -- [AppRoot] and this function occupy the same composition scope at the same point
 * in the tree.
 */
@Composable
fun AndroidAppRoot() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    AppRoot(
        bootstrapViewModelFactory = remember(appContext) { appBootstrapViewModelFactory(appContext) },
        authViewModelFactory = remember(appContext) { authViewModelFactory(appContext) },
        profileListViewModelFactory = remember(appContext) { profileListViewModelFactory(appContext) },
        navHostDependencies = { appNavHostDependencies() },
    )
}