package com.crispy.tv.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

// `DiscoverViewModel` is in this package, so it is not imported: an import from the file's
// own package is legal and reads as a claim that the name came from somewhere else.

/**
 * The Android half of [DiscoverViewModel]'s construction.
 *
 * This is the same split as `catalogViewModelFactory`: the wiring is **not** here, it is
 * `buildDiscoverViewModel` in `jvmMain`, which both the Android and the desktop target compile.
 * Only the class check is Android-shaped, and it is kept verbatim rather than tidied:
 *
 * - The `isAssignableFrom` check comes before the throw, so a caller asking for the wrong
 *   ViewModel class gets the same message it always did. The text is unchanged, because it
 *   is a diagnostic string and changing it is a behaviour change dressed as a cleanup.
 * - `Class<T>` cannot move: the common metadata declares only `create(KClass<T>, extras)`,
 *   and `KClass.isAssignableFrom` does not exist in Kotlin 2.4.10's common `KClass`. The
 *   desktop's copy of this factory goes through `viewModelFactoryOf` instead and therefore
 *   compares for equality — a recorded difference, and the reason this file still exists in
 *   `androidMain` at all.
 *
 * The `Context` parameter is gone. The one collaborator was the browse repository, which
 * [AppGraph] builds from its two clients; nothing here reaches a platform any more.
 */
fun discoverViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildDiscoverViewModel(graph) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
