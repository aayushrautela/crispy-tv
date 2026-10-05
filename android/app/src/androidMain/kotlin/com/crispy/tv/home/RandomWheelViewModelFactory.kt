package com.crispy.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The Android half of [RandomWheelViewModel]'s construction.
 *
 * The wiring itself is **not** here — it is `buildRandomWheelViewModel` in `jvmMain`, which both
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
 * The `Context` parameter is gone. The collaborators come off the `commonMain` [AppGraph] —
 * the catalog service the wheel reads, and the platform `AppLogger` it reports through — so
 * nothing here reaches a platform any more.
 */
fun randomWheelViewModelFactory(graph: AppGraph): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(RandomWheelViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return buildRandomWheelViewModel(graph) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}