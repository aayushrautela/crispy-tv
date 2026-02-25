package com.crispy.tv.discover

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.BuildConfig
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.domain.catalog.CatalogFilter
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.network.AppHttp
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.components.StandardTopAppBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class DiscoverTypeFilter(val label: String, val mediaType: String?) {
    All(label = "All", mediaType = null),
    Movies(label = "Movies", mediaType = "movie"),
    Series(label = "Series", mediaType = "series")
}

data class DiscoverUiState(
    val typeFilter: DiscoverTypeFilter = DiscoverTypeFilter.Movies,
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val catalogs: List<DiscoverCatalogRef> = emptyList(),
    val selectedCatalogKey: String? = null,
    val selectedGenre: String? = null,
    val results: List<CatalogItem> = emptyList(),
    val isLoadingResults: Boolean = false,
    val isLoadingMore: Boolean = false,
    val page: Int = 1,
    val hasMore: Boolean = false
) {
    val selectedCatalog: DiscoverCatalogRef?
        get() = catalogs.firstOrNull { it.key == selectedCatalogKey }
}

class DiscoverViewModel(
    private val homeCatalogService: HomeCatalogService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    private var refreshJob: Job? = null
    private var resultsJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        refresh()
    }

    fun setTypeFilter(filter: DiscoverTypeFilter) {
        _uiState.update { it.copy(typeFilter = filter) }
        refresh()
    }

    fun selectCatalog(catalog: DiscoverCatalogRef) {
        _uiState.update {
            it.copy(
                selectedCatalogKey = catalog.key,
                selectedGenre = null
            )
        }
        loadFirstPage()
    }

    fun selectGenre(genre: String?) {
        _uiState.update { it.copy(selectedGenre = genre) }
        loadFirstPage()
    }

    fun refresh() {
        refreshJob?.cancel()
        resultsJob?.cancel()
        loadMoreJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val filterSnapshot = uiState.value.typeFilter
                val priorSelected = uiState.value.selectedCatalogKey
                val priorGenre = uiState.value.selectedGenre

                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        statusMessage = "Loading discover catalogs...",
                        catalogs = emptyList(),
                        selectedCatalogKey = null,
                        selectedGenre = null,
                        results = emptyList(),
                        isLoadingResults = false,
                        isLoadingMore = false,
                        page = 1,
                        hasMore = false
                    )
                }

                val catalogsResult =
                    withContext(Dispatchers.IO) {
                        homeCatalogService.listDiscoverCatalogs(
                            mediaType = filterSnapshot.mediaType,
                            limit = 80
                        )
                    }
                val catalogs = catalogsResult.first
                val statusMessage = catalogsResult.second
                val selectedKey =
                    priorSelected?.takeIf { key -> catalogs.any { it.key == key } }
                        ?: catalogs.firstOrNull()?.key
                val selectedCatalog = catalogs.firstOrNull { it.key == selectedKey }
                val normalizedGenre = priorGenre?.trim()?.takeIf { it.isNotBlank() }
                val selectedGenre =
                    normalizedGenre?.takeIf { genre -> selectedCatalog?.genres?.any { it.equals(genre, ignoreCase = true) } == true }

                _uiState.update {
                    it.copy(
                        catalogs = catalogs,
                        selectedCatalogKey = selectedKey,
                        selectedGenre = selectedGenre,
                        statusMessage = statusMessage,
                        isRefreshing = false
                    )
                }

                loadFirstPage()
            }
    }

    fun loadMore() {
        val snapshot = uiState.value
        if (snapshot.isLoadingResults || snapshot.isLoadingMore || !snapshot.hasMore) {
            return
        }
        val catalog = snapshot.selectedCatalog ?: return

        loadMoreJob?.cancel()
        loadMoreJob =
            viewModelScope.launch {
                val pageSize = PAGE_SIZE
                val nextPage = (snapshot.page + 1).coerceAtLeast(2)
                _uiState.update { it.copy(isLoadingMore = true) }

                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            homeCatalogService.fetchCatalogPage(
                                section = catalog.section,
                                page = nextPage,
                                pageSize = pageSize,
                                filters = buildGenreFilters(snapshot.selectedGenre)
                            )
                        }
                    }

                val pageResult = result.getOrNull()
                val newItems = pageResult?.items.orEmpty()
                val merged = dedupItems(snapshot.results + newItems)
                val hasMore = newItems.size >= pageSize

                _uiState.update {
                    it.copy(
                        results = merged,
                        isLoadingMore = false,
                        page = nextPage,
                        hasMore = hasMore,
                        statusMessage = pageResult?.statusMessage ?: (result.exceptionOrNull()?.message ?: it.statusMessage)
                    )
                }
            }
    }

    private fun loadFirstPage() {
        resultsJob?.cancel()
        resultsJob =
            viewModelScope.launch {
                val snapshot = uiState.value
                val catalog = snapshot.selectedCatalog
                if (catalog == null) {
                    _uiState.update {
                        it.copy(
                            results = emptyList(),
                            isLoadingResults = false,
                            isLoadingMore = false,
                            hasMore = false,
                            page = 1
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoadingResults = true,
                        isLoadingMore = false,
                        results = emptyList(),
                        page = 1,
                        hasMore = false,
                        statusMessage = "Loading..."
                    )
                }

                val pageSize = PAGE_SIZE
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            homeCatalogService.fetchCatalogPage(
                                section = catalog.section,
                                page = 1,
                                pageSize = pageSize,
                                filters = buildGenreFilters(snapshot.selectedGenre)
                            )
                        }
                    }
                val pageResult = result.getOrNull()
                val items = dedupItems(pageResult?.items.orEmpty())
                val hasMore = (pageResult?.items?.size ?: 0) >= pageSize

                _uiState.update {
                    it.copy(
                        results = items,
                        isLoadingResults = false,
                        page = 1,
                        hasMore = hasMore,
                        statusMessage = pageResult?.statusMessage ?: (result.exceptionOrNull()?.message ?: "Failed to load")
                    )
                }
            }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return DiscoverViewModel(
                            homeCatalogService =
                                 HomeCatalogService(
                                     context = appContext,
                                     addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                                     httpClient = AppHttp.client(appContext),
                                 )
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private const val PAGE_SIZE = 60

private fun buildGenreFilters(selectedGenre: String?): List<CatalogFilter> {
    val normalized = selectedGenre?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(CatalogFilter(key = "genre", value = normalized))
}

private fun dedupItems(items: List<CatalogItem>): List<CatalogItem> {
    if (items.isEmpty()) {
        return emptyList()
    }
    val seen = HashSet<String>(items.size)
    val output = ArrayList<CatalogItem>(items.size)
    items.forEach { item ->
        val key = "${item.type.lowercase(Locale.US)}:${item.id}"
        if (seen.add(key)) {
            output += item
        }
    }
    return output
}

@Composable
fun DiscoverRoute(
    onItemClick: (CatalogItem) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: DiscoverViewModel =
        viewModel(
            factory = remember(appContext) {
                DiscoverViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DiscoverScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onTypeFilterClick = viewModel::setTypeFilter,
        onCatalogClick = viewModel::selectCatalog,
        onGenreClick = viewModel::selectGenre,
        onLoadMore = viewModel::loadMore,
        onItemClick = onItemClick
    )
}

private enum class DiscoverSheet {
    Type,
    Catalog,
    Genre
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscoverScreen(
    uiState: DiscoverUiState,
    onRefresh: () -> Unit,
    onTypeFilterClick: (DiscoverTypeFilter) -> Unit,
    onCatalogClick: (DiscoverCatalogRef) -> Unit,
    onGenreClick: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    var activeSheet by remember { mutableStateOf<DiscoverSheet?>(null) }
    val selectedCatalog = uiState.selectedCatalog
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Discover",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { activeSheet = DiscoverSheet.Type },
                                    label = { Text(uiState.typeFilter.label) },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            item {
                                FilterChip(
                                    selected = false,
                                    onClick = { activeSheet = DiscoverSheet.Catalog },
                                    label = {
                                        Text(
                                            text = selectedCatalog?.section?.title ?: "Select catalog",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }

                            if (selectedCatalog != null && selectedCatalog.genres.isNotEmpty()) {
                                item {
                                    FilterChip(
                                        selected = false,
                                        onClick = { activeSheet = DiscoverSheet.Genre },
                                        label = {
                                            Text(
                                                text = uiState.selectedGenre ?: "All genres",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                top = innerPadding.calculateTopPadding() + Dimensions.SmallSpacing,
                end = pageHorizontalPadding,
                bottom = Dimensions.PageBottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedCatalog != null || uiState.statusMessage.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                         if (selectedCatalog != null) {
                             val summaryGenre = uiState.selectedGenre ?: "All genres"
                             Text(
                                 text = "${selectedCatalog.section.title} | ${selectedCatalog.section.mediaType.uppercase(Locale.US)} | $summaryGenre",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                         }

                        if (uiState.statusMessage.isNotBlank()) {
                            Text(
                                text = uiState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.isLoadingResults && uiState.results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LoadingIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                text = "Discovering...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (uiState.results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedCatalog == null) "Select a catalog to start discovering" else "No content found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = uiState.results,
                    key = { "${it.type}:${it.id}" }
                ) { item ->
                    PosterCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = item.rating,
                        year = item.year,
                        genre = item.genre,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onItemClick(item) }
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(4.dp))
            }

            if (uiState.isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimensions.ListItemPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            } else if (uiState.hasMore && uiState.results.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FilledTonalButton(onClick = onLoadMore) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }

    if (activeSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null }
        ) {
            when (activeSheet) {
                DiscoverSheet.Type -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding)
                    ) {
                            item {
                                Text(
                                    text = "Type",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = Dimensions.SmallSpacing)
                                )
                            }
                            items(DiscoverTypeFilter.entries) { filter ->
                                ListItem(
                                    headlineContent = { Text(filter.label) },
                                    trailingContent =
                                        if (uiState.typeFilter == filter) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onTypeFilterClick(filter)
                                            activeSheet = null
                                        }
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }

                    DiscoverSheet.Catalog -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding)
                        ) {
                            item {
                                Text(
                                    text = "Catalog",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = Dimensions.SmallSpacing)
                                )
                            }
                            if (uiState.catalogs.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = Dimensions.PageBottomPadding),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No catalogs available",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                items(
                                    items = uiState.catalogs,
                                    key = { it.key }
                                ) { catalog ->
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                text = catalog.section.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                         supportingContent = {
                                             Text(
                                                 text = catalog.section.mediaType.uppercase(Locale.US),
                                                 maxLines = 1,
                                                 overflow = TextOverflow.Ellipsis
                                             )
                                         },
                                        trailingContent =
                                            if (uiState.selectedCatalogKey == catalog.key) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Check,
                                                        contentDescription = null
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onCatalogClick(catalog)
                                                activeSheet = null
                                            }
                                            .padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    DiscoverSheet.Genre -> {
                        val genres = selectedCatalog?.genres.orEmpty()
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = Dimensions.PageBottomPadding)
                        ) {
                            item {
                                Text(
                                    text = "Genre",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = Dimensions.SmallSpacing)
                                )
                            }
                            item {
                                ListItem(
                                    headlineContent = { Text("All genres") },
                                    trailingContent =
                                        if (uiState.selectedGenre == null) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onGenreClick(null)
                                            activeSheet = null
                                        }
                                )
                            }
                            items(genres) { genre ->
                                ListItem(
                                    headlineContent = { Text(genre) },
                                    trailingContent =
                                        if (uiState.selectedGenre == genre) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onGenreClick(genre)
                                            activeSheet = null
                                        }
                                )
                            }
                        }
                    }

                null -> Unit
            }
        }
    }
}
