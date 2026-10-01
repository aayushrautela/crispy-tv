package com.crispy.tv.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val suggestions: List<String> = emptyList(),
    val resultBuckets: SearchResultBuckets = SearchResultBuckets(),
    val statusMessage: String? = null,
) {
    val hasActiveResults: Boolean
        get() = isLoading || executedQuery.isNotBlank() || selectedGenre != null
}

/**
 * @param languageTagProvider supplies the BCP-47 language tag the AI search sends
 *   to the backend. It is a required parameter with no default on purpose. The
 *   viewmodel used to hold `private val localeProvider: () -> Locale = {
 *   Locale.getDefault() }`, which put a JVM type and a platform-locale decision in
 *   the middle of the UI layer; a tag is a `String` the backend already speaks, so
 *   the platform's answer belongs at the composition root, in
 *   `searchViewModelFactory`, and a test can hand this any tag it likes.
 *
 * @param ioDispatcher runs the backend calls. The repositories are suspend
 *   functions that do **not** dispatch themselves, so this hop is what keeps a
 *   network call off the main dispatcher, and it is therefore load-bearing rather
 *   than tidiness. It is injected only so a test can substitute an eager
 *   dispatcher: `Dispatchers.IO` is a real thread pool, so `withContext` on it
 *   suspends past an unconfined main dispatcher and the request would still be in
 *   flight when the assertion ran. The default keeps every production call site
 *   unchanged, which is what makes this a testability change and not a refactor.
 */
class SearchViewModel(
    private val searchRepository: BackendSearchRepository,
    private val aiSearchRepository: AiSearchRepository,
    private val searchHistoryStore: SearchHistoryStore,
    private val languageTagProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = searchHistoryStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null
    private var searchToken: Long = 0L
    private var suggestionJob: Job? = null
    private var suggestionToken: Long = 0L

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(
            query = query,
            executedQuery = "",
            selectedGenre = null,
            searchMode = SearchMode.STANDARD,
            isLoading = false,
            resultBuckets = SearchResultBuckets(),
            suggestions = emptyList(),
            statusMessage = null,
        )
        scheduleSuggestions(query)
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
        cancelActiveSuggestions()
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
        ) { _ ->
            searchRepository.discoverByGenre(genreSuggestion = genreSuggestion)
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
        cancelActiveSuggestions()
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
                    suggestions = emptyList(),
                    statusMessage = null,
                )
            },
        ) { languageTag ->
            if (mode == SearchMode.AI) {
                aiSearchRepository.search(
                    query = normalizedQuery,
                    languageTag = languageTag,
                )
            } else {
                searchRepository.search(query = normalizedQuery)
            }
        }
    }

    private fun launchSearch(
        updateState: SearchUiState.() -> SearchUiState,
        request: suspend (String) -> SearchResultsPayload,
    ) {
        searchToken += 1
        val token = searchToken
        cancelActiveSearch()
        _uiState.value = _uiState.value.updateState()

        searchJob =
            viewModelScope.launch {
                val languageTag = languageTagProvider()
                val payload =
                    withContext(ioDispatcher) {
                        runCatching { request(languageTag) }
                            .getOrElse {
                                SearchResultsPayload(message = it.message ?: "Search is unavailable right now.")
                            }
                    }


                // The token check below this line is what stops a superseded
                // response from overwriting a newer one, and it is kept
                // deliberately even though no test can observe it being
                // removed. Measured, not assumed: deleting it and printing the
                // whole state produced a byte-identical `SearchUiState`. The
                // reason is that `withContext` re-checks cancellation when the
                // block completes, so the `CancellationException` from
                // `searchJob?.cancel()` escapes *past* the `runCatching` that
                // wraps the request and ends the coroutine here -- the guard is
                // never reached by a cancelled job. That makes the guard
                // redundant as the code stands, because bumping the token and
                // cancelling the job happen together and a job whose token moved
                // on was always cancelled. It is one integer comparison, and it
                // is the only thing standing between a future change that bumps
                // the token without cancelling (a force-refresh, or a second
                // caller) and a stale overwrite. Record it here so the next
                // person does not read a surviving mutation as a missing test.
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

    /**
     * Debounced so a burst of keystrokes issues one suggestion request. The
     * token guards the ordering: a slow response for an older query is dropped
     * instead of overwriting suggestions for the text now in the box.
     */
    private fun scheduleSuggestions(rawQuery: String) {
        suggestionToken += 1
        val token = suggestionToken
        suggestionJob?.cancel()

        val normalizedQuery = rawQuery.trim()
        if (normalizedQuery.isBlank()) {
            return
        }

        suggestionJob =
            viewModelScope.launch {
                delay(SUGGESTION_DEBOUNCE_MS)
                val names =
                    withContext(ioDispatcher) {
                        runCatching { searchRepository.suggestions(normalizedQuery) }
                            .getOrElse { emptyList() }
                    }
                if (token != suggestionToken) {
                    return@launch
                }
                _uiState.value = _uiState.value.copy(suggestions = names)
            }
    }

    private fun cancelActiveSuggestions() {
        suggestionToken += 1
        suggestionJob?.cancel()
        suggestionJob = null
    }

    companion object {
        private const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
