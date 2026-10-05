package com.crispy.tv.person

import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.viewModelFactoryOf
import com.crispy.tv.app.AppGraph

/**
 * The desktop half of [PersonDetailsViewModel]'s construction: the same name as its `androidMain`
 * twin, one declaration per source set.
 *
 * Two identical top-level names in sibling source sets of one module is not a redeclaration —
 * `androidMain` and `desktopMain` are separate compilations, and the common metadata sees
 * neither. It is also what makes `AppNavHostDependenciesDesktop` and `AppNavHostDependenciesAndroid`
 * read the same, and it means a caller needs no import edit to switch platforms.
 *
 * The body is one line because the wiring lives in [buildPersonDetailsViewModel], and the
 * `KClass`/`Class` split is the framework's, not ours. The one behavioural difference is
 * recorded in `AccountViewModelBuilders.kt`: this side compares the requested class for
 * **equality** because `KClass.isAssignableFrom` does not exist in the common stdlib, so a
 * subclass of one of these ViewModels is not served here the way it is on Android. Nothing in
 * this repository subclasses them, and `viewModel<T>()` asks for `T` itself.
 *
 * `personId` is a parameter rather than a closure capture because the desktop caller holds the
 * graph but not the route argument: the factory is built once per route, and the id arrives with
 * the call, exactly as the android side passes it through.
 */
fun personDetailsViewModelFactory(
    graph: AppGraph,
    personId: String,
): ViewModelProvider.Factory =
    viewModelFactoryOf { buildPersonDetailsViewModel(graph, personId) }