package com.crispy.tv.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchTypeFilter {
    ALL,
    MOVIES,
    SERIES,
    PEOPLE;

    val label: String
        get() =
            when (this) {
                ALL -> "All"
                MOVIES -> "Movies"
                SERIES -> "Series"
                PEOPLE -> "People"
            }
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val filter: SearchTypeFilter = SearchTypeFilter.ALL,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val results: List<CatalogItem> = emptyList(),
) {
    val trimmedQuery: String
        get() = query.trim()
}

class SearchViewModel(
    private val searchRepository: TmdbSearchRepository,
    private val searchHistoryStore: SearchHistoryStore,
    private val localeProvider: () -> Locale = { Locale.getDefault() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = searchHistoryStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null
    private var searchToken: Long = 0L

    fun showQuery(query: String, saveToHistory: Boolean = false) {
        val normalizedQuery = query.trim()
        val recentSearches =
            if (saveToHistory) {
                searchHistoryStore.record(normalizedQuery)
            } else {
                _uiState.value.recentSearches
            }

        _uiState.value =
            _uiState.value.copy(
                query = normalizedQuery,
                recentSearches = recentSearches,
            )

        if (normalizedQuery.isBlank()) {
            clearResults()
            return
        }

        loadResults(query = normalizedQuery, filter = _uiState.value.filter)
    }

    fun recordQuery(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return
        }
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.record(normalizedQuery))
    }

    fun removeRecentSearch(query: String) {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.remove(query))
    }

    fun clearRecentSearches() {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.clear())
    }

    fun setFilter(filter: SearchTypeFilter) {
        if (_uiState.value.filter == filter) {
            return
        }

        _uiState.value = _uiState.value.copy(filter = filter)
        val query = _uiState.value.trimmedQuery
        if (query.isBlank()) {
            clearResults()
            return
        }

        loadResults(query = query, filter = filter)
    }

    private fun clearResults() {
        searchToken += 1
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = false, results = emptyList())
    }

    private fun loadResults(query: String, filter: SearchTypeFilter) {
        searchToken += 1
        val token = searchToken
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(query = query, filter = filter, isLoading = true, results = emptyList())

        searchJob =
            viewModelScope.launch {
                val locale = localeProvider()
                val results =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            searchRepository.search(
                                query = query,
                                filter = filter,
                                locale = locale,
                            )
                        }.getOrElse { emptyList() }
                    }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoading = false, results = results)
            }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(
                        searchRepository = TmdbServicesProvider.searchRepository(context),
                        searchHistoryStore = SearchHistoryStore(context),
                    ) as T
                }
            }
        }
    }
}
