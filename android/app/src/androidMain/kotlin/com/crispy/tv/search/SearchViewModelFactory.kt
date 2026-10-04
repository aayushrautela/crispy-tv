package com.crispy.tv.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * Builds the [ViewModelProvider.Factory] for [SearchViewModel].
 *
 * This is the whole Android half of the search viewmodel, and it is one function
 * rather than a `companion object` member because the viewmodel itself now lives
 * in `commonMain` and cannot name `Context`. The body is the one the companion
 * used to hold, with one addition and two removals:
 *
 * - it supplies `languageTagProvider`, which is new. The viewmodel no longer
 *   decides the language; the platform does, here, and the value crosses into it
 *   as the BCP-47 tag the backend already speaks.
 * - it constructs `KeyValueSearchHistoryStore`, the [SearchHistoryStore]
 *   implementation. The port took the implementation's original name, and when the
 *   store itself moved to `commonMain` it took the *port's* name instead: it had
 *   stopped naming `SharedPreferences`, which is still the name of the file on
 *   disk and the one thing the class no longer knows.
 * - it no longer passes a locale to the repositories: `BackendSearchRepository`
 *   never used the one it was given and its parameter has been deleted, and
 *   `AiSearchRepository` now takes the tag directly.
 *
 * The two repositories used to be built by `search/SearchRepositories.kt`, which existed
 * only to hold the provider lookups. They are [com.crispy.tv.app.AppGraph] members now, and
 * they are still built fresh per `create` rather than cached — see the graph for why.
 */
fun searchViewModelFactory(appContext: Context): ViewModelProvider.Factory {
    val context = appContext.applicationContext
    val graph = context.appGraph().graph
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            val viewModel = SearchViewModel(
                searchRepository = graph.backendSearchRepository(),
                aiSearchRepository = graph.aiSearchRepository(),
                searchHistoryStore =
                    KeyValueSearchHistoryStore(
                        SharedPreferencesKeyValueStore(context, "search_preferences"),
                    ),
                languageTagProvider = { Locale.getDefault().toLanguageTag() },
                ioDispatcher = Dispatchers.IO,
            )
            @Suppress("UNCHECKED_CAST")
            return viewModel as T
        }
    }
}
