package com.crispy.tv.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

// `DiscoverViewModel` is in this package, so it is not imported: an import from the file's
// own package is legal and reads as a claim that the name came from somewhere else.

/**
 * The Android half of [DiscoverViewModel]'s construction.
 *
 * This is an **extraction, not a move**: the factory used to be a
 * `companion object { fun factory(context: Context) }` on the ViewModel, which is a
 * composition root wearing a class's clothes — the class held no platform type of its own
 * and was pinned only by a constructor-shaped argument on a nested object. The same
 * treatment already went to `catalogViewModelFactory` in this module, and
 * `discoverViewModelFactory` follows it exactly so the two read as a pair.
 *
 * Three details are preserved from the original rather than tidied:
 *
 * - The `isAssignableFrom` check comes before the throw, so a caller asking for the wrong
 *   ViewModel class gets the same message it always did. The text is unchanged, because it
 *   is a diagnostic string and changing it is a behaviour change dressed as a cleanup.
 * - The `@Suppress("UNCHECKED_CAST")` is the cast `ViewModelProvider`'s own API forces:
 *   `create` cannot know `T` from `modelClass` alone.
 *
 * **The `Context` parameter is gone.** The one collaborator was the browse repository, which
 * [AppGraph] builds from its two clients; nothing here reaches a platform any more.
 */
fun discoverViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return DiscoverViewModel(
                    repository = graph.backendBrowseRepository()
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
