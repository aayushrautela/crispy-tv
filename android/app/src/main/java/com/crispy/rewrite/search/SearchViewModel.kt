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
import com.crispy.rewrite.network.AppHttp
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
    val sections: List<CatalogSectionRef>
)

data class SearchUiState(
    val query: String = "",
    val mediaType: MetadataLabMediaType? = null,
    val isLoadingCatalogs: Boolean = false,
    val catalogs: List<SearchCatalogOption> = emptyList(),
    val selectedCatalogKeys: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val results: List<CatalogItem> = emptyList()
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

    fun setMediaType(mediaType: MetadataLabMediaType?) {
        if (_uiState.value.mediaType == mediaType) {
            return
        }
        _uiState.value =
            _uiState.value.copy(
                mediaType = mediaType,
                results = emptyList(),
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
                        catalogs = emptyList()
                    )

                val mediaTypes = when (val mt = _uiState.value.mediaType) {
                    null -> listOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
                    else -> listOf(mt)
                }
                val allOptions = LinkedHashMap<String, SearchCatalogOption>()
                for (type in mediaTypes) {
                    val typeString = type.toCatalogTypeString()
                    val (discoverCatalogs, _) =
                        withContext(Dispatchers.IO) {
                            homeCatalogService.listDiscoverCatalogs(mediaType = typeString, limit = 200)
                        }
                    val labResult =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                catalogSearchService.fetchCatalogPage(
                                    request =
                                        CatalogPageRequest(
                                            mediaType = type,
                                            catalogId = ""
                                        )
                                )
                            }.getOrElse {
                                CatalogLabResult()
                            }
                        }
                    val options = buildOptions(discoverCatalogs, labResult)
                    allOptions.putAll(options)
                }

                val options = allOptions.values.toList()
                val previousSelection = _uiState.value.selectedCatalogKeys
                val nextSelection =
                    previousSelection
                        .filter { allOptions.containsKey(it) }
                        .toSet()
                        .ifEmpty { options.map { it.key }.toSet() }

                _uiState.value =
                    _uiState.value.copy(
                        isLoadingCatalogs = false,
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
        val allKeys = _uiState.value.catalogs.map { it.key }.toSet()
        val next =
            if (key == "__all__") {
                if (current.size == allKeys.size) emptySet() else allKeys
            } else if (current.contains(key)) {
                val filtered = current - key
                if (filtered.isEmpty()) current else filtered
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
                results = emptyList()
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
                    results = emptyList()
                )
            return
        }

        val selected =
            _uiState.value.catalogs.filter { _uiState.value.selectedCatalogKeys.contains(it.key) }
        if (selected.isEmpty()) {
            _uiState.value =
                _uiState.value.copy(
                    isSearching = false,
                    results = emptyList()
                )
            return
        }

        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        isSearching = true,
                        results = emptyList()
                    )

                val items =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            selected
                                .flatMap { it.sections }
                                .map { section ->
                                    async {
                                        runCatching {
                                            homeCatalogService.fetchCatalogPage(
                                                section = section,
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

                _uiState.value =
                    _uiState.value.copy(
                        isSearching = false,
                        results = merged
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
        val sectionsByAddonId = LinkedHashMap<String, MutableList<CatalogSectionRef>>()
        val addonNameById = HashMap<String, String>()
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
                val addonId = catalog.addonId
                sectionsByAddonId.getOrPut(addonId) { mutableListOf() }.add(discover.section)
                addonNameById[addonId] = discover.addonName.trim()
            }
        val output = LinkedHashMap<String, SearchCatalogOption>()
        sectionsByAddonId.forEach { (addonId, sections) ->
            output[addonId] = SearchCatalogOption(
                key = addonId,
                label = addonNameById[addonId] ?: addonId,
                sections = sections
            )
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
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SearchViewModel::class.java).not()) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val homeCatalogService =
                        HomeCatalogService(
                            context = context,
                            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                            httpClient = AppHttp.client(context),
                        )
                    val catalogSearchService = PlaybackLabDependencies.catalogSearchServiceFactory(context)
                    @Suppress("UNCHECKED_CAST")
                    return SearchViewModel(
                        appContext = context,
                        homeCatalogService = homeCatalogService,
                        catalogSearchService = catalogSearchService
                    ) as T
                }
            }
        }
    }
}
