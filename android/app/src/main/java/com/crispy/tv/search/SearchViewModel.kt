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
import kotlinx.coroutines.delay
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

    val supportsGenreSuggestions: Boolean
        get() = this != PEOPLE
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val filter: SearchTypeFilter = SearchTypeFilter.ALL,
    val activeGenreSuggestion: SearchGenreSuggestion? = null,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val results: List<CatalogItem> = emptyList()
) {
    val trimmedQuery: String
        get() = query.trim()
}

class SearchViewModel(
    private val searchRepository: TmdbSearchRepository,
    private val searchHistoryStore: SearchHistoryStore,
    private val localeProvider: () -> Locale = { Locale.getDefault() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = searchHistoryStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null
    private var searchToken: Long = 0

    fun updateQuery(query: String) {
        val currentState = _uiState.value
        val visibleResults =
            currentState.results.takeUnless { currentState.activeGenreSuggestion != null } ?: emptyList()

        _uiState.value =
            currentState.copy(
                query = query,
                activeGenreSuggestion = null,
                results = visibleResults
            )

        if (query.trim().isBlank()) {
            clearResults()
            return
        }

        loadResults(debounceMs = QUERY_DEBOUNCE_MS)
    }

    fun clearQuery() {
        _uiState.value = _uiState.value.copy(query = "", activeGenreSuggestion = null)
        clearResults()
    }

    fun submitQuery() {
        saveCurrentQueryToHistory()
        if (_uiState.value.trimmedQuery.isNotBlank()) {
            loadResults()
        }
    }

    fun rememberCurrentQuery() {
        saveCurrentQueryToHistory()
    }

    fun selectGenreSuggestion(genreSuggestion: SearchGenreSuggestion) {
        val supportedFilter =
            _uiState.value.filter.takeIf(SearchTypeFilter::supportsGenreSuggestions)
                ?: SearchTypeFilter.ALL

        _uiState.value =
            _uiState.value.copy(
                query = "",
                filter = supportedFilter,
                activeGenreSuggestion = genreSuggestion
            )
        loadResults()
    }

    fun clearGenreSuggestion() {
        _uiState.value = _uiState.value.copy(activeGenreSuggestion = null)
        clearResults()
    }

    fun selectRecentSearch(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return
        }

        _uiState.value =
            _uiState.value.copy(
                query = normalizedQuery,
                activeGenreSuggestion = null,
                recentSearches = searchHistoryStore.record(normalizedQuery)
            )
        loadResults()
    }

    fun removeRecentSearch(query: String) {
        updateRecentSearches(searchHistoryStore.remove(query))
    }

    fun clearRecentSearches() {
        updateRecentSearches(searchHistoryStore.clear())
    }

    fun setFilter(filter: SearchTypeFilter) {
        val currentState = _uiState.value
        if (currentState.filter == filter) {
            return
        }

        _uiState.value =
            currentState.copy(
                filter = filter,
                activeGenreSuggestion = currentState.activeGenreSuggestion.takeIf { filter.supportsGenreSuggestions }
            )

        when {
            _uiState.value.trimmedQuery.isNotBlank() -> loadResults()
            _uiState.value.activeGenreSuggestion != null -> loadResults()
            else -> clearResults()
        }
    }

    private fun clearResults() {
        searchToken += 1
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = false, results = emptyList())
    }

    private fun updateRecentSearches(recentSearches: List<String>) {
        _uiState.value = _uiState.value.copy(recentSearches = recentSearches)
    }

    private fun saveCurrentQueryToHistory() {
        updateRecentSearches(searchHistoryStore.record(_uiState.value.trimmedQuery))
    }

    private fun loadResults(debounceMs: Long = 0L) {
        searchToken += 1
        val token = searchToken
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }

                val state = _uiState.value
                val request = state.toSearchRequest()
                if (request is SearchRequest.Idle) {
                    _uiState.value = state.copy(isLoading = false, results = emptyList())
                    return@launch
                }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = state.copy(isLoading = true)

                val locale = localeProvider()
                val results =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            when (request) {
                                is SearchRequest.Text -> {
                                    searchRepository.search(
                                        query = request.query,
                                        filter = state.filter,
                                        locale = locale,
                                    )
                                }
                                is SearchRequest.Genre -> {
                                    searchRepository.discoverByGenre(
                                        genreSuggestion = request.genreSuggestion,
                                        filter = state.filter,
                                        locale = locale,
                                    )
                                }
                                SearchRequest.Idle -> emptyList()
                            }
                        }.getOrElse { emptyList() }
                    }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoading = false, results = results)
            }
    }

    private fun SearchUiState.toSearchRequest(): SearchRequest {
        return when {
            trimmedQuery.isNotBlank() -> SearchRequest.Text(trimmedQuery)
            activeGenreSuggestion != null && filter.supportsGenreSuggestions -> SearchRequest.Genre(activeGenreSuggestion)
            else -> SearchRequest.Idle
        }
    }

    private sealed interface SearchRequest {
        data object Idle : SearchRequest

        data class Text(val query: String) : SearchRequest

        data class Genre(val genreSuggestion: SearchGenreSuggestion) : SearchRequest
    }

    companion object {
        private const val QUERY_DEBOUNCE_MS = 250L

        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val repository = TmdbServicesProvider.searchRepository(context)
                    val historyStore = SearchHistoryStore(context)

                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(
                        searchRepository = repository,
                        searchHistoryStore = historyStore
                    ) as T
                }
            }
        }
    }
}
