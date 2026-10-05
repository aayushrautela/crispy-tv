package com.crispy.tv.home

import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.viewModelFactoryOf
import com.crispy.tv.app.AppGraph

/**
 * The desktop half of [CalendarViewModel]'s construction: the same name as its `androidMain`
 * twin, one declaration per source set.
 *
 * Two identical top-level names in sibling source sets of one module is not a redeclaration —
 * `androidMain` and `desktopMain` are separate compilations, and the common metadata sees
 * neither. It is also what makes `AppNavHostDependenciesDesktop` and `AppNavHostDependenciesAndroid`
 * read the same, and it means a caller needs no import edit to switch platforms.
 *
 * The body is one line because the wiring lives in [buildCalendarViewModel], and the
 * `KClass`/`Class` split is the framework's, not ours. The one behavioural difference is
 * recorded in `AccountViewModelBuilders.kt`: this side compares the requested class for
 * **equality** because `KClass.isAssignableFrom` does not exist in the common stdlib, so a
 * subclass of one of these ViewModels is not served here the way it is on Android. Nothing in
 * this repository subclasses them, and `viewModel<T>()` asks for `T` itself.
 */
fun calendarViewModelFactory(graph: AppGraph): ViewModelProvider.Factory =
    viewModelFactoryOf { buildCalendarViewModel(graph) }