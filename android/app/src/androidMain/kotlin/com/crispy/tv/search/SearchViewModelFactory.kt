package com.crispy.tv.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
 * - it constructs `SharedPreferencesSearchHistoryStore`, the renamed
 *   [SearchHistoryStore] implementation, because the port took the type's name.
 * - it no longer passes a locale to the repositories: `BackendSearchRepository`
 *   never used the one it was given and its parameter has been deleted, and
 *   `AiSearchRepository` now takes the tag directly.
 */
fun searchViewModelFactory(appContext: Context): ViewModelProvider.Factory {
    val context = appContext.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

            val viewModel = SearchViewModel(
                searchRepository = backendSearchRepository(context),
                aiSearchRepository = aiSearchRepository(context),
                searchHistoryStore = SharedPreferencesSearchHistoryStore(context),
                languageTagProvider = { Locale.getDefault().toLanguageTag() },
                ioDispatcher = Dispatchers.IO,
            )
            @Suppress("UNCHECKED_CAST")
            return viewModel as T
        }
    }
}
