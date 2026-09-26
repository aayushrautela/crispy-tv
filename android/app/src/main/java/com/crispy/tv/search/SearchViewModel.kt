package com.crispy.tv.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchMode {
    STANDARD,
    AI,
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val executedQuery: String = "",
    val selectedGenre: SearchGenreSuggestion? = null,
    val searchMode: SearchMode = SearchMode.STANDARD,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val resultBuckets: SearchResultBuckets = SearchResultBuckets(),
    val statusMessage: String? = null,
) {
    val hasActiveResults: Boolean
        get() = isLoading || executedQuery.isNotBlank() || selectedGenre != null
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
        _uiState.value = _uiState.value.copy(
            query = query,
            executedQuery = "",
            selectedGenre = null,
            searchMode = SearchMode.STANDARD,
            isLoading = false,
            resultBuckets = SearchResultBuckets(),
            statusMessage = null,
        )
    }

    fun submitSearch(query: String = _uiState.value.query) {
        executeQuerySearch(
            rawQuery = query,
            mode = SearchMode.STANDARD,
            recordInHistory = true,
            immediate = true,
        )
    }

    fun submitAiSearch(query: String = _uiState.value.query) {
        executeQuerySearch(
            rawQuery = query,
            mode = SearchMode.AI,
            recordInHistory = true,
            immediate = true,
        )
    }

    fun clearSearch() {
        searchToken += 1
        cancelActiveSearch()
        _uiState.value = SearchUiState(recentSearches = searchHistoryStore.load())
    }

    fun selectGenre(genreSuggestion: SearchGenreSuggestion) {
        if (_uiState.value.selectedGenre == genreSuggestion) {
            return
        }
        launchSearch(
            updateState = {
                copy(
                    query = genreSuggestion.label,
                    executedQuery = "",
                    selectedGenre = genreSuggestion,
                    searchMode = SearchMode.STANDARD,
                    isLoading = true,
                    statusMessage = null,
                )
            },
        ) { locale ->
            searchRepository.discoverByGenre(
                genreSuggestion = genreSuggestion,
                locale = locale,
            )
        }
    }

    fun removeRecentSearch(query: String) {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.remove(query))
    }

    fun clearRecentSearches() {
        _uiState.value = _uiState.value.copy(recentSearches = searchHistoryStore.clear())
    }

    private fun executeQuerySearch(
        rawQuery: String,
        mode: SearchMode,
        recordInHistory: Boolean,
        immediate: Boolean,
    ) {
        val normalizedQuery = rawQuery.trim()
        if (normalizedQuery.isBlank()) {
            if (immediate) {
                clearSearch()
            }
            return
        }

        val snapshot = _uiState.value
        val updatedRecentSearches =
            if (recordInHistory) {
                searchHistoryStore.record(normalizedQuery)
            } else {
                snapshot.recentSearches
            }

        if (
            snapshot.selectedGenre == null &&
            snapshot.searchMode == mode &&
            snapshot.executedQuery.equals(normalizedQuery, ignoreCase = true)
        ) {
            _uiState.value = snapshot.copy(query = rawQuery, recentSearches = updatedRecentSearches)
            return
        }

        launchSearch(
            updateState = {
                copy(
                    query = normalizedQuery,
                    executedQuery = normalizedQuery,
                    selectedGenre = null,
                    recentSearches = updatedRecentSearches,
                    searchMode = mode,
                    isLoading = true,
                    statusMessage = null,
                )
            },
        ) { locale ->
            if (mode == SearchMode.AI) {
                aiSearchRepository.search(
                    query = normalizedQuery,
                    locale = locale,
                )
            } else {
                searchRepository.search(
                    query = normalizedQuery,
                    locale = locale,
                )
            }
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
                        resultBuckets = payload.buckets,
                        statusMessage = payload.message,
                    )
            }
    }

    private fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val viewModel = SearchViewModel(
                        searchRepository = BackendSearchRepository.create(context),
                        aiSearchRepository = AiSearchRepository.create(context),
                        searchHistoryStore = SearchHistoryStore(context),
                    )
                    @Suppress("UNCHECKED_CAST")
                    return viewModel as T
                }
            }
        }
    }
}
