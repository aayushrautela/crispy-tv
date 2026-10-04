package com.crispy.tv.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The `androidMain` half of [AppBootstrapViewModel]: construction, nothing else.
 *
 * This is the shape every viewmodel in this repo is heading for. The class itself is
 * in `commonMain` and names only [AccountBootstrapRepository], which is itself an
 * interface, so the class is portable; what is left here is the one thing that cannot
 * be — a reach into the app's composition root. `androidx.lifecycle:lifecycle-viewmodel` is a
 * genuine KMP artifact, so [ViewModelProvider] itself was never the obstacle.
 *
 * **The `Context` parameter is gone.** The graph is in `commonMain` and takes one
 * `AppServices`, so there was nothing left here that a `Context` could reach.
 *
 * ## Why this file is still `androidMain` while its collaborators are not
 *
 * The *construction* moved to [buildAppBootstrapViewModel] in `commonMain`, because that
 * part is portable and the desktop factory needs the identical wiring. This declaration
 * stayed because `java.lang.Class` cannot be written in `commonMain` and
 * `ViewModelProvider.Factory`'s common metadata declares only
 * `create(KClass<T>, extras)` — while Android's `ViewModelProvider` calls
 * `create(Class<T>)`, whose default body throws. `viewModelFactoryOf` in `commonMain` is
 * the desktop answer to the same problem; the measurement behind both is written there.
 */
fun appBootstrapViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppBootstrapViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildAppBootstrapViewModel(graph) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}