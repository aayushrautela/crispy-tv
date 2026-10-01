package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.platform.android.AndroidAppLogger
import kotlinx.coroutines.Dispatchers

/**
 * The `androidMain` construction site for [CalendarViewModel] — the exact role
 * this used to play as `CalendarViewModel.Companion.factory`, lifted out so
 * [CalendarScreen] could move to `commonMain` with the class it defines.
 *
 * The split is one line of separation and it is worth naming why it is an
 * *extraction* rather than a move: `CalendarViewModel` was `private`, and a
 * `private` member cannot be named by anything outside its own file — including
 * the factory that exists to construct it. So the class was widened to `internal`
 * and the factory became a sibling file, which is the only way both halves can
 * live in different source sets.
 *
 * **Every line here is a `Context` read or a provider lookup, which is why this
 * file stays in `androidMain` and the class does not.** A `Context` used for
 * *wiring* belongs in the factory; the view model itself never names one.
 *
 * `ioDispatcher = Dispatchers.IO` is the platform edge for the ViewModel's
 * required no-default slot. It is written here, once, rather than defaulted in
 * the class: `Dispatchers.IO` is `internal` on Kotlin/Native, so a `commonMain`
 * default would not compile there at all, and defaulting it to `Dispatchers.Default`
 * would compile everywhere and put a blocking service call on a CPU-sized pool.
 */
fun calendarViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val calendarService = CalendarService(
        backendClient = BackendServicesProvider.backendClient(appContext),
        backendContextResolver = BackendContextResolverProvider.get(appContext),
        logger = AndroidAppLogger(appContext),
    )
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CalendarViewModel(
                calendarService = calendarService,
                ioDispatcher = Dispatchers.IO,
                clock = { System.currentTimeMillis() },
            ) as T
        }
    }
}
