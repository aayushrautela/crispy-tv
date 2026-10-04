package com.crispy.tv.accounts

import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The desktop factories for the account ViewModels: the same names as their `androidMain`
 * twins, one declaration per source set.
 *
 * Two identical top-level names in sibling source sets of one module is not a redeclaration --
 * `androidMain` and `desktopMain` are separate compilations, and the common metadata sees
 * neither. It is also what makes `DesktopAppRoot` and `AndroidAppRoot` read the same, and it
 * means a caller needs no import edit to switch platforms.
 *
 * Each body is one line because the wiring lives in [buildAppBootstrapViewModel] and its
 * siblings, and the `KClass`/`Class` split is the framework's, not ours. The one behavioural
 * difference is recorded in `AccountViewModelBuilders.kt`: this side compares the requested
 * class for **equality** because `KClass.isAssignableFrom` does not exist in the common
 * stdlib, so a subclass of one of these ViewModels is not served here the way it is on
 * Android. Nothing in this repository subclasses them, and `viewModel<T>()` asks for `T`
 * itself.
 */

/** The auth screen's viewmodel: sign in, register, sign out. */
fun authViewModelFactory(graph: AppGraph): ViewModelProvider.Factory =
    viewModelFactoryOf { buildAuthViewModel(graph) }

/** Backs both [ProfileSelectorRoute] and [ProfileManagementRoute]. */
fun profileListViewModelFactory(graph: AppGraph): ViewModelProvider.Factory =
    viewModelFactoryOf { buildProfileListViewModel(graph) }

/**
 * The account settings screen. [openUrl] is the browser capability slot; Android fills it with
 * `launchUrl`, and the desktop entry point fills it with whatever it wants -- nothing on this
 * platform calls it yet, because the settings screen is reachable only from the shell.
 */
fun accountSettingsViewModelFactory(
    graph: AppGraph,
    openUrl: (String) -> Unit,
): ViewModelProvider.Factory =
    viewModelFactoryOf { buildAccountSettingsViewModel(graph, openUrl) }