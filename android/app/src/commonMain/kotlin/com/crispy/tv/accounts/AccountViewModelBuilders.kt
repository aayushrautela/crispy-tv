package com.crispy.tv.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.crispy.tv.app.AppGraph
import kotlin.reflect.KClass

/**
 * The construction of the four account ViewModels, and the generic `KClass` factory that
 * consumes it.
 *
 * ## Why the construction is here and the factories are not
 *
 * There are two `ViewModelProvider.Factory.create` shapes and no one implementation of
 * both, and that was measured rather than assumed:
 *
 * - `javap` on `lifecycle-viewmodel-android` 2.11.0 shows three `default` methods on
 *   `ViewModelProvider.Factory`. `create(Class<T>)` **throws**
 *   `unsupportedCreateViewModel`; `create(Class<T>, extras)` calls it; and
 *   `create(KClass<T>, extras)` converts *into* it. So Android's `ViewModelProvider`
 *   only ever reaches a factory through the `Class` overload.
 * - The common metadata declares exactly one overridable signature,
 *   `create(modelClass: KClass<T>, extras: CreationExtras)`, so a `commonMain` file
 *   cannot name `java.lang.Class` at all -- `compileCommonMainKotlinMetadata` rejects it.
 *
 * **And the class check cannot be shared either**: `KClass.isAssignableFrom` does not
 * exist in Kotlin 2.4.10's common `KClass` (javap lists `isInstance` and no assignability
 * member), so a portable factory can only compare for equality.
 *
 * What *is* portable is therefore everything except those two details: which collaborators
 * each ViewModel is built from. Each `build…ViewModel` below is the single copy of that
 * wiring, called from the `Class`-based factory in `androidMain` -- unchanged, still
 * `isAssignableFrom` -- and from the `KClass`-based one in `desktopMain`.
 */

/** The collaborators of [AppBootstrapViewModel], which is in `commonMain` and portable. */
internal fun buildAppBootstrapViewModel(graph: AppGraph): AppBootstrapViewModel =
    AppBootstrapViewModel(bootstrapRepository = graph.bootstrapRepository)

/** The collaborators of [AuthViewModel]: sign in, register, sign out. */
internal fun buildAuthViewModel(graph: AppGraph): AuthViewModel =
    AuthViewModel(
        supabase = graph.accountClient,
        bootstrapRepository = graph.bootstrapRepository,
    )

/** The collaborators of [ProfileListViewModel], which backs both profile routes. */
internal fun buildProfileListViewModel(graph: AppGraph): ProfileListViewModel =
    ProfileListViewModel(
        bootstrapRepository = graph.bootstrapRepository,
        profileRepository = graph.profileRepository,
        activeProfileStore = graph.activeProfileStore,
    )

/**
 * The collaborators of [AccountSettingsViewModel].
 *
 * [openUrl] is a slot rather than a `Context` for the reason [AccountSettingsViewModel]'s own
 * parameter records: a `Context` used for a *call* is a capability, and the slot carries the
 * data rather than the platform type. Android supplies `launchUrl`; a desktop supplies
 * `Desktop.browse`.
 */
internal fun buildAccountSettingsViewModel(
    graph: AppGraph,
    openUrl: (String) -> Unit,
): AccountSettingsViewModel =
    AccountSettingsViewModel(
        openUrl = openUrl,
        bootstrapRepository = graph.bootstrapRepository,
        accountSettingsRepository = graph.accountSettingsRepository,
        syncProviderRepository = graph.syncProviderRepository,
        pendingProviderAuthStore = graph.pendingProviderAuthStore,
    )

/**
 * The portable half of a factory: a `KClass`-keyed `ViewModelProvider.Factory` over [build].
 *
 * [extras] is accepted and **deliberately ignored**, because none of these four ViewModels
 * reads a `CreationExtras`. An `inline fun` with a `reified` type parameter is what lets the
 * class check be an exact comparison against `VM::class` in a function that has no `KClass`
 * parameter of its own.
 */
internal inline fun <reified VM : ViewModel> viewModelFactoryOf(
    crossinline build: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        if (modelClass != VM::class) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.simpleName}")
        }
        return build() as T
    }
}