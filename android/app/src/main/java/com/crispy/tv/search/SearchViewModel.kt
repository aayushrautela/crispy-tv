package com.crispy.tv.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.network.AppHttp
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
    SERIES;

    val label: String
        get() =
            when (this) {
                ALL -> "All"
                MOVIES -> "Movies"
                SERIES -> "Series"
            }
}

enum class SearchMode {
    STANDARD,
    AI,
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val appliedQuery: String = "",
    val selectedGenre: SearchGenreSuggestion? = null,
    val filter: SearchTypeFilter = SearchTypeFilter.ALL,
    val searchMode: SearchMode = SearchMode.STANDARD,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val results: List<CatalogItem> = emptyList(),
    val statusMessage: String? = null,
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
    private val searchRepository: BackendSearchRepository,
    private val aiSearchRepository: AiSearchRepository,
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

        val resolvedFilter = normalizeFilterForMode(snapshot.filter, SearchMode.STANDARD)
        val updatedRecentSearches = searchHistoryStore.record(normalizedQuery)
        launchSearch(
            updateState = {
                copy(
                    query = normalizedQuery,
                    appliedQuery = normalizedQuery,
                    selectedGenre = null,
                    recentSearches = updatedRecentSearches,
                    filter = resolvedFilter,
                    searchMode = SearchMode.STANDARD,
                    isLoading = true,
                    results = emptyList(),
                    statusMessage = null,
                )
            },
        ) { locale ->
            searchRepository.search(
                query = normalizedQuery,
                filter = resolvedFilter,
                locale = locale,
            )
        }
    }

    fun submitAiSearch(query: String = _uiState.value.query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return
        }

        val snapshot = _uiState.value
        val resolvedFilter = normalizeFilterForMode(snapshot.filter, SearchMode.AI)
        val updatedRecentSearches = searchHistoryStore.record(normalizedQuery)
        launchSearch(
            updateState = {
                copy(
                    query = normalizedQuery,
                    appliedQuery = normalizedQuery,
                    selectedGenre = null,
                    recentSearches = updatedRecentSearches,
                    filter = resolvedFilter,
                    searchMode = SearchMode.AI,
                    isLoading = true,
                    results = emptyList(),
                    statusMessage = null,
                )
            },
        ) { locale ->
            aiSearchRepository.search(
                query = normalizedQuery,
                filter = resolvedFilter,
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
                searchMode = SearchMode.STANDARD,
                isLoading = false,
                results = emptyList(),
                statusMessage = null,
            )
    }

    fun selectGenre(genreSuggestion: SearchGenreSuggestion) {
        val resolvedFilter = normalizeFilterForMode(_uiState.value.filter, SearchMode.STANDARD)
        loadGenreResults(genreSuggestion = genreSuggestion, filter = resolvedFilter)
    }

    fun removeRecentSearch(query: String) {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.remove(query))
    }

    fun clearRecentSearches() {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.clear())
    }

    fun setFilter(filter: SearchTypeFilter) {
        val mode = _uiState.value.searchMode
        val resolvedFilter =
            if (_uiState.value.selectedGenre != null) {
                normalizeFilterForMode(filter, SearchMode.STANDARD)
            } else {
                normalizeFilterForMode(filter, mode)
            }
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
                if (snapshot.searchMode == SearchMode.AI) {
                    loadAiResults(
                        query = snapshot.appliedQuery,
                        filter = resolvedFilter,
                    )
                } else {
                    loadQueryResults(
                        query = snapshot.appliedQuery,
                        filter = resolvedFilter,
                    )
                }
            }

            else -> clearResultsOnly()
        }
    }

    private fun clearResultsOnly() {
        searchToken += 1
        cancelActiveSearch()
        _uiState.value = _uiState.value.copy(isLoading = false, results = emptyList(), statusMessage = null)
    }

    private fun loadQueryResults(query: String, filter: SearchTypeFilter) {
        val resolvedFilter = normalizeFilterForMode(filter, SearchMode.STANDARD)
        launchSearch(
            updateState = {
                copy(
                    query = query,
                    appliedQuery = query,
                    selectedGenre = null,
                    filter = resolvedFilter,
                    searchMode = SearchMode.STANDARD,
                    isLoading = true,
                    results = emptyList(),
                    statusMessage = null,
                )
            },
        ) { locale ->
            searchRepository.search(
                query = query,
                filter = resolvedFilter,
                locale = locale,
            )
        }
    }

    private fun loadAiResults(query: String, filter: SearchTypeFilter) {
        val resolvedFilter = normalizeFilterForMode(filter, SearchMode.AI)
        launchSearch(
            updateState = {
                copy(
                    query = query,
                    appliedQuery = query,
                    selectedGenre = null,
                    filter = resolvedFilter,
                    searchMode = SearchMode.AI,
                    isLoading = true,
                    results = emptyList(),
                    statusMessage = null,
                )
            },
        ) { locale ->
            aiSearchRepository.search(
                query = query,
                filter = resolvedFilter,
                locale = locale,
            )
        }
    }

    private fun loadGenreResults(
        genreSuggestion: SearchGenreSuggestion,
        filter: SearchTypeFilter,
    ) {
        val resolvedFilter = normalizeFilterForMode(filter, SearchMode.STANDARD)
        launchSearch(
            updateState = {
                copy(
                    query = genreSuggestion.label,
                    selectedGenre = genreSuggestion,
                    appliedQuery = "",
                    filter = resolvedFilter,
                    searchMode = SearchMode.STANDARD,
                    isLoading = true,
                    results = emptyList(),
                    statusMessage = null,
                )
            },
        ) { locale ->
            searchRepository.discoverByGenre(
                genreSuggestion = genreSuggestion,
                filter = resolvedFilter,
                locale = locale,
            )
        }
    }

    private fun launchSearch(
        updateState: SearchUiState.() -> SearchUiState,
        request: suspend (Locale) -> SearchResultsPayload,
    ) {
        searchToken += 1
        val token = searchToken
        cancelActiveSearch()
        _uiState.value = _uiState.value.updateState()

        searchJob =
            viewModelScope.launch {
                val locale = localeProvider()
                val payload =
                    withContext(Dispatchers.IO) {
                        runCatching { request(locale) }
                            .getOrElse {
                                SearchResultsPayload(message = it.message ?: "Search is unavailable right now.")
                            }
                    }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        results = payload.items,
                        statusMessage = payload.message,
                    )
            }
    }

    private fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    private fun normalizeFilterForGenre(filter: SearchTypeFilter): SearchTypeFilter {
        return filter
    }

    private fun normalizeFilterForMode(filter: SearchTypeFilter, mode: SearchMode): SearchTypeFilter {
        return normalizeFilterForGenre(filter)
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val httpClient = AppHttp.client(context)
                    val viewModel = SearchViewModel(
                        searchRepository = BackendSearchRepository.create(context),
                        aiSearchRepository = AiSearchRepository.create(context, httpClient),
                        searchHistoryStore = SearchHistoryStore(context),
                    )
                    @Suppress("UNCHECKED_CAST")
                    return viewModel as T
                }
            }
        }
    }
}
