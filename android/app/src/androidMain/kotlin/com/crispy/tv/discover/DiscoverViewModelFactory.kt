package com.crispy.tv.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

// `DiscoverViewModel` and `backendBrowseRepository` are in this package, so neither is
// imported: an import from the file's own package is legal and reads as a claim that the
// name came from somewhere else.

/**
 * The Android half of [DiscoverViewModel]'s construction, and the only place in the
 * discover flow that knows a `Context` exists.
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
 * - `context.applicationContext` is captured **once, outside** the returned object. It is a
 *   lifetime normalisation, and inside the object it would be re-read on every `create`.
 * - The `isAssignableFrom` check comes before the throw, so a caller asking for the wrong
 *   ViewModel class gets the same message it always did. The text is unchanged, because it
 *   is a diagnostic string and changing it is a behaviour change dressed as a cleanup.
 * - The `@Suppress("UNCHECKED_CAST")` is the cast `ViewModelProvider`'s own API forces:
 *   `create` cannot know `T` from `modelClass` alone.
 */
fun discoverViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return DiscoverViewModel(
                    repository = backendBrowseRepository(appContext)
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
