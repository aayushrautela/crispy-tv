package com.crispy.tv.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The Android half of [SearchViewModel]'s construction.
 *
 * The wiring itself is **not** here — it is `buildSearchViewModel` in `jvmMain`, which both
 * the Android and the desktop target compile. Only the class check is Android-shaped, and it is
 * kept verbatim rather than tidied:
 *
 * - The `isAssignableFrom` check comes before the throw, so a caller asking for the wrong
 *   ViewModel class gets the same message it always did. The text is unchanged, because it
 *   is a diagnostic string and changing it is a behaviour change dressed as a cleanup.
 * - `Class<T>` cannot move: the common metadata declares only `create(KClass<T>, extras)`, and
 *   `KClass.isAssignableFrom` does not exist in Kotlin 2.4.10's common `KClass`. The desktop's
 *   copy of this factory goes through `viewModelFactoryOf` instead and therefore compares for
 *   equality — a recorded difference, and the reason this file still exists in `androidMain` at
 *   all.
 *
 * The `Context` parameter is gone. The one collaborator was the search history store, which now
 * comes off the `commonMain` `AppGraph` through `services.keyValueStores`; nothing here reaches
 * a platform any more.
 */
fun searchViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            @Suppress("UNCHECKED_CAST")
            return buildSearchViewModel(graph) as T
        }
    }
}