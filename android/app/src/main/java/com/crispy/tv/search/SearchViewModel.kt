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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchCategory {
    ALL,
    MOVIES,
    SERIES,
    ANIME;

    val label: String
        get() =
            when (this) {
                ALL -> "All"
                MOVIES -> "Movies"
                SERIES -> "Series"
                ANIME -> "Anime"
            }
}

enum class SearchMode {
    STANDARD,
    AI,
}

@Immutable
data class SearchUiState(
    val query: String = "",
    val executedQuery: String = "",
    val selectedGenre: SearchGenreSuggestion? = null,
    val category: SearchCategory = SearchCategory.ALL,
    val searchMode: SearchMode = SearchMode.STANDARD,
    val isLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val resultBuckets: SearchResultBuckets = SearchResultBuckets(),
    val statusMessage: String? = null,
) {
    val hasActiveResults: Boolean
        get() = isLoading || executedQuery.isNotBlank() || selectedGenre != null

    val availableCategories: List<SearchCategory>
        get() = SearchCategory.entries

    val visibleResults: List<CatalogItem>
        get() = resultBuckets.itemsFor(category)
}

class SearchViewModel(
    private val searchRepository: BackendSearchRepository,
    private val aiSearchRepository: AiSearchRepository,
    private val searchHistoryStore: SearchHistoryStore,
    private val localeProvider: () -> Locale = { Locale.getDefault() },
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = searchHistoryStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null
    private var debouncedSearchJob: Job? = null
    private var searchToken: Long = 0L

    fun updateQuery(query: String) {
        val snapshot = _uiState.value
        val trimmedQuery = query.trim()
        val clearsGenreSelection = snapshot.selectedGenre != null && query != snapshot.selectedGenre.label

        _uiState.value =
            snapshot.copy(
                query = query,
                selectedGenre = if (clearsGenreSelection) null else snapshot.selectedGenre,
                executedQuery = if (clearsGenreSelection) "" else snapshot.executedQuery,
                resultBuckets = if (clearsGenreSelection) SearchResultBuckets() else snapshot.resultBuckets,
                statusMessage = if (clearsGenreSelection) null else snapshot.statusMessage,
                isLoading = if (clearsGenreSelection) false else snapshot.isLoading,
            )

        if (trimmedQuery.isBlank()) {
            resetToBrowse(query = query)
            return
        }

        scheduleDebouncedQuerySearch(mode = _uiState.value.searchMode)
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
        resetToBrowse(query = "")
    }

    fun selectGenre(genreSuggestion: SearchGenreSuggestion) {
        if (_uiState.value.selectedGenre == genreSuggestion) {
            return
        }
        cancelPendingQuerySearch()
        launchSearch(
            updateState = {
                copy(
                    query = genreSuggestion.label,
                    executedQuery = "",
                    selectedGenre = genreSuggestion,
                    category = SearchCategory.ALL,
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

    fun setCategory(category: SearchCategory) {
        if (_uiState.value.category == category) {
            return
        }

        _uiState.value = _uiState.value.copy(category = category)
    }

    private fun resetToBrowse(query: String) {
        searchToken += 1
        cancelActiveSearch()
        cancelPendingQuerySearch()
        _uiState.value =
            _uiState.value.copy(
                query = query,
                executedQuery = "",
                selectedGenre = null,
                category = SearchCategory.ALL,
                searchMode = SearchMode.STANDARD,
                isLoading = false,
                resultBuckets = SearchResultBuckets(),
                statusMessage = null,
            )
    }

    private fun scheduleDebouncedQuerySearch(mode: SearchMode) {
        cancelPendingQuerySearch()
        debouncedSearchJob =
            viewModelScope.launch {
                delay(searchDebounceMs)
                executeQuerySearch(
                    rawQuery = _uiState.value.query,
                    mode = mode,
                    recordInHistory = false,
                    immediate = false,
                )
            }
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
                resetToBrowse(query = rawQuery)
            }
            return
        }

        if (immediate) {
            cancelPendingQuerySearch()
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
                    category = SearchCategory.ALL,
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

    private fun cancelPendingQuerySearch() {
        debouncedSearchJob?.cancel()
        debouncedSearchJob = null
    }

    private fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 350L

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
