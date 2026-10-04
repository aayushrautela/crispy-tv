package com.crispy.tv.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The `androidMain` half of the three account viewmodels, and one of the reasons
 * [AuthViewModel], [ProfileListViewModel] and [AccountSettingsViewModel] can live in
 * `commonMain`: every collaborator they need is already a KMP type, and the only platform
 * reach was a `Context`.
 *
 * Each function below reproduces a `companion object { fun factory(context: Context) }`
 * block that used to live on its class, wiring unchanged. The routes are in `commonMain`
 * and cannot name a `Context`, so the nav graph calls these and passes the result to the
 * route as a plain [ViewModelProvider.Factory] value — see the KDoc on
 * [AccountSettingsRoute] for why that is a value and not a composable slot.
 *
 * **None of the three takes a `Context` any more.** They read an [AppGraph], which is in
 * `commonMain` and holds one `AppServices` per process, and each delegates to a
 * `build…ViewModel` in `commonMain` that holds its collaborators. The one collaborator that
 * did need the platform — opening a browser — is the capability slot `openUrl`, which was a
 * `Context` used for a *call* and never for wiring.
 *
 * The desktop half of these three is in `desktopMain`, because
 * `ViewModelProvider.Factory`'s common metadata declares only
 * `create(KClass<T>, extras)` and `java.lang.Class` cannot be written in `commonMain` —
 * see `AccountViewModelBuilders.kt` for the measurement.
 */

/** The auth screen's viewmodel: sign in, register, sign out. */
fun authViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildAuthViewModel(graph) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/** Backs both [ProfileSelectorRoute] and [ProfileManagementRoute]. */
fun profileListViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ProfileListViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildProfileListViewModel(graph) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/**
 * The account settings screen: profile editing, provider sync, import and sign-out.
 *
 * [openUrl] is a slot rather than a `Context` for the reason [AccountSettingsViewModel]'s own
 * parameter records: a `Context` used for a *call* is a capability, and the slot carries the
 * data rather than the platform type. The composition root builds it with the same
 * `launchUrl` this file owns.
 */
fun accountSettingsViewModelFactory(
    graph: AppGraph,
    openUrl: (String) -> Unit,
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AccountSettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildAccountSettingsViewModel(graph, openUrl) as T
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
 *
 * Now `internal` rather than `private`, because the composition root is the caller: a
 * `private` member cannot be named by the factory meant to supply the slot.
 */
internal fun launchUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}