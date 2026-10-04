package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.platform.android.AndroidAppLogger
import kotlinx.coroutines.Dispatchers

/**
 * The `androidMain` construction site for [RandomWheelViewModel].
 *
 * **The split is an extraction rather than a move, for the reason [CatalogViewModelFactory] gives
 * for its own identical shape:** the factory needs a `Context` and the view model needs none, so
 * one of the two halves has to stay behind; keeping the factory on the class would have made the
 * portable half inherit the `Context` import.
 *
 * The service comes off the `commonMain` [com.crispy.tv.app.AppGraph], which is a single cached
 * instance per process -- `AppGraphCachingTest` asserts exactly that -- so the wheel and the home
 * screen agree about which snapshot they are reading.
 *
 * `Dispatchers.IO` is written here rather than defaulted on the class because `Dispatchers.IO` is
 * `internal` on Kotlin/Native and would not compile from `commonMain` at all, while
 * `Dispatchers.Default` compiles everywhere and silently puts a blocking add-on fetch on a
 * CPU-sized pool.
 */
fun randomWheelViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val graph = appContext.appGraph().graph
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(RandomWheelViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return RandomWheelViewModel(
                    homeCatalogService = graph.homeCatalogService,
                    ioDispatcher = Dispatchers.IO,
                    logger = AndroidAppLogger(appContext),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
