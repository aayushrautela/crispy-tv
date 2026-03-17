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
    val appliedQuery: String = "",
    val selectedGenre: SearchGenreSuggestion? = null,
    val filter: SearchTypeFilter = SearchTypeFilter.ALL,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val results: List<CatalogItem> = emptyList(),
) {
    val hasActiveResults: Boolean
        get() = appliedQuery.isNotBlank() || selectedGenre != null

    val availableFilters: List<SearchTypeFilter>
        get() =
            if (selectedGenre == null) {
                SearchTypeFilter.entries
            } else {
                listOf(SearchTypeFilter.ALL, SearchTypeFilter.MOVIES, SearchTypeFilter.SERIES)
            }
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

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun submitSearch(query: String = _uiState.value.query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return
        }

        val snapshot = _uiState.value
        if (snapshot.selectedGenre != null && normalizedQuery == snapshot.selectedGenre.label) {
            return
        }

        val updatedRecentSearches = searchHistoryStore.record(normalizedQuery)
        launchSearch(
            updateState = {
                copy(
                    query = normalizedQuery,
                    appliedQuery = normalizedQuery,
                    selectedGenre = null,
                    recentSearches = updatedRecentSearches,
                    filter = snapshot.filter,
                    isLoading = true,
                    results = emptyList(),
                )
            },
        ) { locale ->
            searchRepository.search(
                query = normalizedQuery,
                filter = snapshot.filter,
                locale = locale,
            )
        }
    }

    fun clearSearch() {
        searchToken += 1
        cancelActiveSearch()
        _uiState.value =
            _uiState.value.copy(
                query = "",
                appliedQuery = "",
                selectedGenre = null,
                isLoading = false,
                results = emptyList(),
            )
    }

    fun selectGenre(genreSuggestion: SearchGenreSuggestion) {
        val resolvedFilter = normalizeFilterForGenre(_uiState.value.filter)
        loadGenreResults(genreSuggestion = genreSuggestion, filter = resolvedFilter)
    }

    fun removeRecentSearch(query: String) {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.remove(query))
    }

    fun clearRecentSearches() {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.clear())
    }

    fun setFilter(filter: SearchTypeFilter) {
        val resolvedFilter =
            _uiState.value.selectedGenre?.let { normalizeFilterForGenre(filter) } ?: filter
        if (_uiState.value.filter == resolvedFilter) {
            return
        }

        _uiState.value = _uiState.value.copy(filter = resolvedFilter)
        val snapshot = _uiState.value
        when {
            snapshot.selectedGenre != null -> {
                loadGenreResults(
                    genreSuggestion = snapshot.selectedGenre,
                    filter = resolvedFilter,
                )
            }

            snapshot.appliedQuery.isNotBlank() -> {
                loadQueryResults(
                    query = snapshot.appliedQuery,
                    filter = resolvedFilter,
                )
            }

            else -> clearResultsOnly()
        }
    }

    private fun clearResultsOnly() {
        searchToken += 1
        cancelActiveSearch()
        _uiState.value = _uiState.value.copy(isLoading = false, results = emptyList())
    }

    private fun loadQueryResults(query: String, filter: SearchTypeFilter) {
        launchSearch(
            updateState = {
                copy(
                    query = query,
                    appliedQuery = query,
                    selectedGenre = null,
                    filter = filter,
                    isLoading = true,
                    results = emptyList(),
                )
            },
        ) { locale ->
            searchRepository.search(
                query = query,
                filter = filter,
                locale = locale,
            )
        }
    }

    private fun loadGenreResults(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
    ) {
        launchSearch(
            updateState = {
                copy(
                    query = genreSuggestion.label,
                    selectedGenre = genreSuggestion,
                    appliedQuery = "",
                    filter = filter,
                    isLoading = true,
                    results = emptyList(),
                )
            },
        ) { locale ->
            searchRepository.discoverByGenre(
                genreSuggestion = genreSuggestion,
                filter = filter,
                locale = locale,
            )
        }
    }

    private fun launchSearch(
        updateState: SearchUiState.() -> SearchUiState,
        request: suspend (Locale) -> List<CatalogItem>,
    ) {
        searchToken += 1
        val token = searchToken
        cancelActiveSearch()
        _uiState.value = _uiState.value.updateState()

        searchJob =
            viewModelScope.launch {
                val locale = localeProvider()
                val results =
                    withContext(Dispatchers.IO) {
                        runCatching { request(locale) }.getOrElse { emptyList() }
                    }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoading = false, results = results)
            }
    }

    private fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    private fun normalizeFilterForGenre(filter: SearchTypeFilter): SearchTypeFilter {
        return if (filter == SearchTypeFilter.PEOPLE) SearchTypeFilter.ALL else filter
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
