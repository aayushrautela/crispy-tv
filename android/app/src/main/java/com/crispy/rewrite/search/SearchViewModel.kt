package com.crispy.rewrite.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.catalog.DiscoverCatalogRef
import com.crispy.rewrite.domain.catalog.CatalogFilter
import com.crispy.rewrite.home.HomeCatalogService
import com.crispy.rewrite.player.CatalogLabResult
import com.crispy.rewrite.player.CatalogPageRequest
import com.crispy.rewrite.player.CatalogSearchLabService
import com.crispy.rewrite.player.MetadataLabMediaType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchCatalogOption(
    val key: String,
    val label: String,
    val section: CatalogSectionRef
)

data class SearchUiState(
    val query: String = "",
    val mediaType: MetadataLabMediaType = MetadataLabMediaType.MOVIE,
    val isLoadingCatalogs: Boolean = false,
    val catalogsStatusMessage: String = "",
    val catalogs: List<SearchCatalogOption> = emptyList(),
    val selectedCatalogKeys: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val results: List<CatalogItem> = emptyList(),
    val statusMessage: String = ""
)

class SearchViewModel(
    private val appContext: Context,
    private val homeCatalogService: HomeCatalogService,
    private val catalogSearchService: CatalogSearchLabService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    init {
        refreshCatalogs()
    }

    fun setMediaType(mediaType: MetadataLabMediaType) {
        if (_uiState.value.mediaType == mediaType) {
            return
        }
        _uiState.value =
            _uiState.value.copy(
                mediaType = mediaType,
                results = emptyList(),
                statusMessage = "",
                catalogsStatusMessage = "",
                catalogs = emptyList(),
                selectedCatalogKeys = emptySet()
            )
        refreshCatalogs()
    }

    fun refreshCatalogs() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        isLoadingCatalogs = true,
                        catalogsStatusMessage = "Loading catalogs...",
                        catalogs = emptyList()
                    )

                val typeString = _uiState.value.mediaType.toCatalogTypeString()
                val discoverResult =
                    withContext(Dispatchers.IO) {
                        homeCatalogService.listDiscoverCatalogs(mediaType = typeString, limit = 200)
                    }
                val labResult =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            catalogSearchService.fetchCatalogPage(
                                request =
                                    CatalogPageRequest(
                                        mediaType = _uiState.value.mediaType,
                                        catalogId = ""
                                    )
                            )
                        }.getOrElse { error ->
                            CatalogLabResult(
                                statusMessage = error.message ?: "Failed to load catalogs."
                            )
                        }
                    }

                val optionByKey = buildOptions(discoverResult.catalogs, labResult)
                val options = optionByKey.values.toList()
                val previousSelection = _uiState.value.selectedCatalogKeys
                val nextSelection =
                    previousSelection
                        .filter { optionByKey.containsKey(it) }
                        .toSet()
                        .ifEmpty {
                            options.firstOrNull()?.let { option -> setOf(option.key) }.orEmpty()
                        }

                val message =
                    listOf(
                        discoverResult.statusMessage.trim(),
                        labResult.statusMessage.trim()
                    ).filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n")

                _uiState.value =
                    _uiState.value.copy(
                        isLoadingCatalogs = false,
                        catalogsStatusMessage = message,
                        catalogs = options,
                        selectedCatalogKeys = nextSelection
                    )
                debounceSearch()
            }
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        debounceSearch()
    }

    fun toggleCatalog(key: String) {
        val current = _uiState.value.selectedCatalogKeys
        val next =
            if (current.contains(key)) {
                val filtered = current - key
                if (filtered.isEmpty()) {
                    current
                } else {
                    filtered
                }
            } else {
                current + key
            }
        _uiState.value = _uiState.value.copy(selectedCatalogKeys = next)
        debounceSearch()
    }

    fun clearQuery() {
        searchJob?.cancel()
        debounceJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                query = "",
                isSearching = false,
                results = emptyList(),
                statusMessage = ""
            )
    }

    private fun debounceSearch() {
        debounceJob?.cancel()
        debounceJob =
            viewModelScope.launch {
                delay(250)
                runSearchNow()
            }
    }

    private fun runSearchNow() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) {
            searchJob?.cancel()
            _uiState.value =
                _uiState.value.copy(
                    isSearching = false,
                    results = emptyList(),
                    statusMessage = ""
                )
            return
        }

        val selected =
            _uiState.value.catalogs.filter { _uiState.value.selectedCatalogKeys.contains(it.key) }
        if (selected.isEmpty()) {
            _uiState.value =
                _uiState.value.copy(
                    isSearching = false,
                    results = emptyList(),
                    statusMessage = "No catalogs selected."
                )
            return
        }

        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        isSearching = true,
                        results = emptyList(),
                        statusMessage = "Searching ${selected.size} catalogs..."
                    )

                val items =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            selected
                                .map { option ->
                                    async {
                                        runCatching {
                                            homeCatalogService.fetchCatalogPage(
                                                section = option.section,
                                                page = 1,
                                                pageSize = 60,
                                                filters = listOf(CatalogFilter(key = "search", value = query))
                                            )
                                        }.getOrNull()?.items.orEmpty()
                                    }
                                }.awaitAll()
                                .flatten()
                        }
                    }
                val merged = mergeItems(items)
                val message =
                    if (merged.isEmpty()) {
                        "No results for '$query'."
                    } else {
                        "Found ${merged.size} results."
                    }

                _uiState.value =
                    _uiState.value.copy(
                        isSearching = false,
                        results = merged,
                        statusMessage = message
                    )
            }
    }

    private fun buildOptions(
        discoverCatalogs: List<DiscoverCatalogRef>,
        labResult: CatalogLabResult
    ): LinkedHashMap<String, SearchCatalogOption> {
        val discoverByKey =
            discoverCatalogs.associateBy {
                catalogKey(
                    addonId = it.section.addonId,
                    mediaType = it.section.mediaType,
                    catalogId = it.section.catalogId
                )
            }
        val output = LinkedHashMap<String, SearchCatalogOption>()
        labResult.catalogs
            .filter { it.supportsSearch }
            .forEach { catalog ->
                val key =
                    catalogKey(
                        addonId = catalog.addonId,
                        mediaType = catalog.catalogType,
                        catalogId = catalog.catalogId
                    )
                val discover = discoverByKey[key] ?: return@forEach
                val title = discover.section.title.trim().ifEmpty { discover.section.catalogId }
                val label = "${discover.addonName.trim()}: $title"
                output[key] = SearchCatalogOption(key = key, label = label, section = discover.section)
            }
        return output
    }

    private fun mergeItems(items: List<CatalogItem>): List<CatalogItem> {
        val seen = HashSet<String>(items.size)
        val output = ArrayList<CatalogItem>(items.size)
        items.forEach { item ->
            val id = item.id.trim()
            if (id.isEmpty()) {
                return@forEach
            }
            val key = "${item.type.lowercase(Locale.US)}:${id.lowercase(Locale.US)}"
            if (seen.add(key)) {
                output += item
            }
        }
        return output
    }

    private fun MetadataLabMediaType.toCatalogTypeString(): String {
        return if (this == MetadataLabMediaType.SERIES) {
            "series"
        } else {
            "movie"
        }
    }

    companion object {
        private fun catalogKey(addonId: String, mediaType: String, catalogId: String): String {
            return "${addonId.trim().lowercase(Locale.US)}:${mediaType.trim().lowercase(Locale.US)}:${catalogId.trim().lowercase(Locale.US)}"
        }

        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return ViewModelProvider.Factory {
                val homeCatalogService = HomeCatalogService(context, BuildConfig.METADATA_ADDON_URLS)
                val catalogSearchService = PlaybackLabDependencies.catalogSearchServiceFactory(context)
                SearchViewModel(
                    appContext = context,
                    homeCatalogService = homeCatalogService,
                    catalogSearchService = catalogSearchService
                )
            }
        }
    }
}
