package com.crispy.tv.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
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
    PEOPLE
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchTypeFilter = SearchTypeFilter.ALL,
    val isSearching: Boolean = false,
    val results: List<CatalogItem> = emptyList()
)

class SearchViewModel(
    private val searchRepository: TmdbSearchRepository,
    private val localeProvider: () -> Locale = { Locale.getDefault() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null
    private var searchToken: Long = 0

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        scheduleSearch()
    }

    fun clearQuery() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(query = "", isSearching = false, results = emptyList())
    }

    fun setFilter(filter: SearchTypeFilter) {
        if (_uiState.value.filter == filter) {
            return
        }
        _uiState.value = _uiState.value.copy(filter = filter)
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchToken += 1
        val token = searchToken
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                delay(250)

                val query = _uiState.value.query.trim()
                if (query.isBlank()) {
                    _uiState.value = _uiState.value.copy(isSearching = false, results = emptyList())
                    return@launch
                }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isSearching = true)

                val languageTag = localeProvider().toTmdbLanguageTag()
                val filter = _uiState.value.filter
                val results =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            searchRepository.search(
                                query = query,
                                filter = filter,
                                languageTag = languageTag
                            )
                        }.getOrElse { emptyList() }
                    }

                if (token != searchToken) {
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isSearching = false, results = results)
            }
    }

    private fun Locale.toTmdbLanguageTag(): String? {
        val language = language.trim().takeIf { it.isNotBlank() } ?: return null
        val country = country.trim().takeIf { it.isNotBlank() }
        return if (country == null) language else "$language-$country"
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val repository = TmdbSearchRepositoryProvider.get(context)

                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(searchRepository = repository) as T
                }
            }
        }
    }
}
