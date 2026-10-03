package com.crispy.tv.catalog

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.SupabaseServicesProvider
import kotlinx.coroutines.Dispatchers

/**
 * The `androidMain` construction site for [CatalogViewModel], lifted out of the
 * companion object it used to be.
 *
 * **The split is an extraction rather than a move, and the reason is the same one
 * [CalendarScreenFactory] records.** The factory needs a `Context` to reach
 * [SupabaseServicesProvider], and the view model needs none, so one of the two
 * halves has to stay behind; keeping the factory on the class would have meant
 * the portable half inherited the `Context` import.
 *
 * `ioDispatcher = Dispatchers.IO` is the platform edge for the view model's
 * required no-default slot, and it is written here once rather than defaulted in
 * the class: `Dispatchers.IO` is `internal` on Kotlin/Native, so a `commonMain`
 * default would not compile there at all, and `Dispatchers.Default` would compile
 * everywhere and silently put a blocking add-on fetch on a CPU-sized pool.
 */
fun catalogViewModelFactory(
    context: Context,
    section: CatalogSectionRef,
): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CatalogViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CatalogViewModel(
                    homeCatalogService = SupabaseServicesProvider.homeCatalogService(appContext),
                    section = section,
                    ioDispatcher = Dispatchers.IO,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}