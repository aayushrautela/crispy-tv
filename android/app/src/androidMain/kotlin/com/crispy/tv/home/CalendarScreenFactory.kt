package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph

/**
 * The Android half of [CalendarViewModel]'s construction.
 *
 * The wiring itself is **not** here — it is `buildCalendarViewModel` in `jvmMain`, which both
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
 * This one keeps a `Context` where the others do not, and it is worth naming why. The wiring
 * moved, but the *graph* did not: on Android the graph is reached off the `Application` through
 * `Context.appGraph()`, and this is the only factory that is built per-route rather than per-
 * screen, so it is the one place the platform edge still has to resolve the graph itself. The
 * desktop twin takes the graph as a parameter instead, which is the same decision recorded in
 * `AppNavHostDependencies`' KDoc about which side of the line the `remember` lives.
 */
fun calendarViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val graph = appContext.appGraph().graph
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return buildCalendarViewModel(graph) as T
        }
    }
}