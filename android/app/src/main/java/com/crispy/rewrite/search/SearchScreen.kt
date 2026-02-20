package com.crispy.rewrite.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SearchCatalogOption(
    val key: String,
    val label: String,
    val section: CatalogSectionRef
)

private data class SearchUiState(
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

private class SearchViewModel(
    private val appContext: Context,
    private val homeCatalogService: HomeCatalogService,
    private val catalogSearchService: CatalogSearchLabService
) : ViewModel() {

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(SearchUiState())
    val uiState = _uiState

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
                val mediaType = _uiState.value.mediaType
                _uiState.value =
                    _uiState.value.copy(
                        isLoadingCatalogs = true,
                        catalogsStatusMessage = "Loading catalogs...",
                        catalogs = emptyList()
                    )

                val typeString = mediaType.toCatalogTypeString()

                val (discoverCatalogs, discoverMessage) =
                    withContext(Dispatchers.IO) {
                        homeCatalogService.listDiscoverCatalogs(mediaType = typeString, limit = 200)
                    }

                val labResult =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            catalogSearchService.fetchCatalogPage(
                                CatalogPageRequest(
                                    mediaType = mediaType,
                                    catalogId = ""
                                )
                            )
                        }
                    }.getOrElse { error ->
                        CatalogLabResult(statusMessage = error.message ?: "Failed to load catalogs.")
                    }

                val optionByKey = buildOptions(discoverCatalogs = discoverCatalogs, labResult = labResult)
                val options = optionByKey.values.toList()
                val previousSelection = _uiState.value.selectedCatalogKeys
                val nextSelection =
                    previousSelection.filter { optionByKey.containsKey(it) }.toSet().ifEmpty {
                        options.firstOrNull()?.let { setOf(it.key) }.orEmpty()
                    }

                val message =
                    listOf(
                        discoverMessage.trim(),
                        labResult.statusMessage.trim()
                    ).filter { it.isNotBlank() }.distinct().joinToString("\n")

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
                val reduced = current - key
                if (reduced.isEmpty()) current else reduced
            } else {
                current + key
            }
        _uiState.value = _uiState.value.copy(selectedCatalogKeys = next)
        debounceSearch()
    }

    fun clearQuery() {
        searchJob?.cancel()
        debounceJob?.cancel()
        _uiState.value = _uiState.value.copy(query = "", isSearching = false, results = emptyList(), statusMessage = "")
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
            _uiState.value = _uiState.value.copy(isSearching = false, results = emptyList(), statusMessage = "")
            return
        }

        val selected =
            _uiState.value.catalogs.filter { _uiState.value.selectedCatalogKeys.contains(it.key) }
        if (selected.isEmpty()) {
            _uiState.value = _uiState.value.copy(isSearching = false, results = emptyList(), statusMessage = "No catalogs selected.")
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
                                }
                                .awaitAll()
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

        val options = LinkedHashMap<String, SearchCatalogOption>()
        labResult.catalogs
            .asSequence()
            .filter { it.supportsSearch }
            .forEach { catalog ->
                val key = catalogKey(addonId = catalog.addonId, mediaType = catalog.catalogType, catalogId = catalog.catalogId)
                val discover = discoverByKey[key] ?: return@forEach

                val label =
                    buildString {
                        append(discover.addonName.trim())
                        append(": ")
                        append(discover.section.title.trim().ifEmpty { discover.section.catalogId })
                    }
                options[key] =
                    SearchCatalogOption(
                        key = key,
                        label = label,
                        section = discover.section
                    )
            }

        return options
    }

    private fun mergeItems(items: List<CatalogItem>): List<CatalogItem> {
        val seen = HashSet<String>()
        val merged = ArrayList<CatalogItem>(items.size)
        items.forEach { item ->
            val id = item.id.trim()
            if (id.isEmpty()) return@forEach
            val key = "${item.type.trim().lowercase(Locale.US)}:${id.lowercase(Locale.US)}"
            if (!seen.add(key)) return@forEach
            merged += item
        }
        return merged
    }

    private fun MetadataLabMediaType.toCatalogTypeString(): String {
        return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
    }

    companion object {
        private fun catalogKey(addonId: String, mediaType: String, catalogId: String): String {
            return "${addonId.trim().lowercase(Locale.US)}:${mediaType.trim().lowercase(Locale.US)}:${catalogId.trim().lowercase(Locale.US)}"
        }

        fun factory(appContext: Context): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val homeCatalogService = HomeCatalogService(context, BuildConfig.METADATA_ADDON_URLS)
                    val catalogSearchService = PlaybackLabDependencies.catalogSearchServiceFactory(context)
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

@Composable
fun SearchRoute(
    onItemClick: (CatalogItem) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: SearchViewModel =
        viewModel(
            factory = remember(appContext) { SearchViewModel.factory(appContext) }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onClearQuery = viewModel::clearQuery,
        onMediaTypeChange = viewModel::setMediaType,
        onCatalogToggle = viewModel::toggleCatalog,
        onRefreshCatalogs = viewModel::refreshCatalogs,
        onItemClick = onItemClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onMediaTypeChange: (MetadataLabMediaType) -> Unit,
    onCatalogToggle: (String) -> Unit,
    onRefreshCatalogs: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                actions = {
                    IconButton(onClick = onRefreshCatalogs) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.query.isNotBlank()) {
                            IconButton(onClick = onClearQuery) {
                                Icon(imageVector = Icons.Outlined.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.mediaType == MetadataLabMediaType.MOVIE,
                            onClick = { onMediaTypeChange(MetadataLabMediaType.MOVIE) },
                            label = { Text("Movies") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = uiState.mediaType == MetadataLabMediaType.SERIES,
                            onClick = { onMediaTypeChange(MetadataLabMediaType.SERIES) },
                            label = { Text("Series") }
                        )
                    }
                }
            }

            if (uiState.catalogs.isNotEmpty()) {
                item {
                    Text(
                        text = "Catalogs",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = uiState.catalogs,
                            key = { it.key }
                        ) { option ->
                            FilterChip(
                                selected = uiState.selectedCatalogKeys.contains(option.key),
                                onClick = { onCatalogToggle(option.key) },
                                label = {
                                    Text(
                                        text = option.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (uiState.isLoadingCatalogs || uiState.isSearching) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (uiState.catalogsStatusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.catalogsStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(
                items = uiState.results,
                key = { "${it.type}:${it.id}:${it.addonId}" }
            ) { item ->
                SearchResultRow(item = item, onClick = { onItemClick(item) })
            }

            if (uiState.query.isBlank() && uiState.results.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Type to search and use chips to filter catalogs.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: CatalogItem,
    onClick: () -> Unit
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .padding(vertical = 2.dp)
                .background(Color.Transparent)
                .clip(MaterialTheme.shapes.medium),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val posterUrl = item.posterUrl ?: item.backdropUrl
            Box(
                modifier =
                    Modifier
                        .size(width = 56.dp, height = 76.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = item.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val subtitle =
                        buildString {
                            append(item.type.ifBlank { "unknown" }.uppercase(Locale.US))
                            if (item.addonId.isNotBlank()) {
                                append(" • ")
                                append(item.addonId)
                            }
                        }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!item.rating.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = item.rating,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
