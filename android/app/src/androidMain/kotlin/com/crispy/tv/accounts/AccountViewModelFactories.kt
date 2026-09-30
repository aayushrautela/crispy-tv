package com.crispy.tv.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * The `androidMain` half of the three account viewmodels, and the only reason
 * [AuthViewModel], [ProfileListViewModel] and [AccountSettingsViewModel] can live in
 * `commonMain`: every collaborator they need is already a KMP type, and the only platform
 * reach was a `Context`.
 *
 * Each function below reproduces a `companion object { fun factory(context: Context) }`
 * block that used to live on its class, wiring unchanged. The routes are in `commonMain`
 * and cannot name a `Context`, so the nav graph calls these and passes the result to the
 * route as a plain [ViewModelProvider.Factory] value — see the KDoc on
 * [AccountSettingsRoute] for why that is a value and not a composable slot.
 */

/** The auth screen's viewmodel: sign in, register, sign out. */
fun authViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(
                    supabase = SupabaseServicesProvider.accountClient(appContext),
                    bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/** Backs both [ProfileSelectorRoute] and [ProfileManagementRoute]. */
fun profileListViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ProfileListViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ProfileListViewModel(
                    bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                    profileRepository = SupabaseServicesProvider.profileRepository(appContext),
                    activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/** The account settings screen: profile editing, provider sync, import and sign-out. */
fun accountSettingsViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AccountSettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AccountSettingsViewModel(
                    openUrl = { url -> launchUrl(appContext, url) },
                    bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                    accountSettingsRepository = SupabaseServicesProvider.accountSettingsRepository(appContext),
                    syncProviderRepository = SupabaseServicesProvider.syncProviderRepository(appContext),
                    pendingProviderAuthStore = SupabaseServicesProvider.pendingProviderAuthStore(appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/**
 * Opens [url] in a browser, on its own task.
 *
 * This is the body that used to be `AccountSettingsViewModel.launchBrowser`, and it is the
 * whole reason that class needed a `Context` at all. `Intent` and `Uri` cannot appear in a
 * `commonMain` signature, so the viewmodel takes an `(String) -> Unit` slot and the
 * construction stays here. `FLAG_ACTIVITY_NEW_TASK` is kept because the receiver is the
 * application context, not an Activity — without it the framework throws.
 */
private fun launchUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
