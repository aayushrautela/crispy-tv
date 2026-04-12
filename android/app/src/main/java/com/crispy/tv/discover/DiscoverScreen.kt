package com.crispy.tv.discover

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogPagingSource
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.home.RecommendationCatalogService
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiscoverTypeFilter(val label: String, val mediaType: String?) {
    All(label = "All", mediaType = null),
    Movies(label = "Movies", mediaType = "movie"),
    Series(label = "Series", mediaType = "series")
}

@Immutable
data class DiscoverUiState(
    val typeFilter: DiscoverTypeFilter = DiscoverTypeFilter.Movies,
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val catalogs: List<DiscoverCatalogRef> = emptyList(),
    val selectedCatalogKey: String? = null,
) {
    val selectedCatalog: DiscoverCatalogRef?
        get() = catalogs.firstOrNull { it.key == selectedCatalogKey }
}

class DiscoverViewModel(
    private val recommendationCatalogService: RecommendationCatalogService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<CatalogItem>> =
        _uiState
            .map { it.selectedCatalog }
            .distinctUntilChanged()
            .flatMapLatest { selectedCatalog ->
                if (selectedCatalog == null) {
                    flowOf(PagingData.empty())
                } else {
                    Pager(
                        config =
                            PagingConfig(
                                pageSize = PAGE_SIZE,
                                initialLoadSize = PAGE_SIZE,
                                prefetchDistance = 10,
                                enablePlaceholders = false,
                            ),
                        pagingSourceFactory = {
                            CatalogPagingSource(
                                recommendationCatalogService = recommendationCatalogService,
                                section = selectedCatalog.section,
                            )
                        },
                    ).flow
                }
            }.cachedIn(viewModelScope)

    init {
        refresh()
    }

    fun setTypeFilter(filter: DiscoverTypeFilter) {
        _uiState.update { it.copy(typeFilter = filter) }
        refresh(preserveCatalogs = false)
    }

    fun selectCatalog(catalog: DiscoverCatalogRef) {
        _uiState.update { it.copy(selectedCatalogKey = catalog.key) }
    }

    fun refresh() {
        refresh(preserveCatalogs = true)
    }

    private fun refresh(preserveCatalogs: Boolean) {
        val snapshot = uiState.value
        val filterSnapshot = snapshot.typeFilter
        val priorSelected = snapshot.selectedCatalogKey

        _uiState.update {
            it.copy(
                isRefreshing = true,
                statusMessage = if (preserveCatalogs && it.catalogs.isNotEmpty()) it.statusMessage else "",
                catalogs = if (preserveCatalogs) it.catalogs else emptyList(),
                selectedCatalogKey = if (preserveCatalogs) it.selectedCatalogKey else null,
            )
        }

        viewModelScope.launch {
            val catalogsResult =
                withContext(Dispatchers.IO) {
                    recommendationCatalogService.listDiscoverCatalogs(
                        mediaType = filterSnapshot.mediaType,
                    )
                }
            val catalogs = catalogsResult.first
            val statusMessage = catalogsResult.second
            val selectedKey =
                priorSelected?.takeIf { key -> catalogs.any { it.key == key } }
                    ?: catalogs.firstOrNull()?.key

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    catalogs = catalogs,
                    selectedCatalogKey = selectedKey,
                    statusMessage = statusMessage,
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
                            recommendationCatalogService = SupabaseServicesProvider.recommendationCatalogService(appContext)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private const val PAGE_SIZE = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverRoute(
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
    onOpenAccountsProfiles: () -> Unit,
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
    val pagingItems = viewModel.items.collectAsLazyPagingItems()
    val scrollBehavior = appBarScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            StandardTopAppBar(
                title = {
                    CrispySectionAppBarTitle(label = "Discover")
                },
                actions = {
                    ProfileIconButton(onClick = onOpenAccountsProfiles)
                },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            DiscoverScreen(
                uiState = uiState,
                pagingItems = pagingItems,
                onRefresh = {
                    viewModel.refresh()
                    pagingItems.refresh()
                },
                onTypeFilterClick = viewModel::setTypeFilter,
                onCatalogClick = viewModel::selectCatalog,
                onItemClick = onItemClick,
                scrollToTopRequests = scrollToTopRequests,
                onScrollToTopConsumed = onScrollToTopConsumed,
            )
        }
    }
}

private enum class DiscoverSheet {
    Type,
    Catalog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscoverScreen(
    uiState: DiscoverUiState,
    pagingItems: LazyPagingItems<CatalogItem>,
    onRefresh: () -> Unit,
    onTypeFilterClick: (DiscoverTypeFilter) -> Unit,
    onCatalogClick: (DiscoverCatalogRef) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    var activeSheet by remember { mutableStateOf<DiscoverSheet?>(null) }
    val selectedCatalog = uiState.selectedCatalog
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val gridState = rememberLazyGridState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val isRefreshing = uiState.isRefreshing || (refreshState is LoadState.Loading && pagingItems.itemCount > 0)
    val pagingStatusMessage =
        when {
            refreshState is LoadState.Error && pagingItems.itemCount > 0 -> {
                refreshState.error.message ?: "Failed to refresh results."
            }

            appendState is LoadState.Error -> {
                appendState.error.message ?: "Failed to load more results."
            }

            else -> ""
        }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            gridState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        indicator = {
            Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                top = Dimensions.SmallSpacing,
                end = pageHorizontalPadding,
                bottom = Dimensions.PageBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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

                    }
                }
                if (selectedCatalog != null || uiState.statusMessage.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (selectedCatalog != null) {
                                Text(
                                    text = "${selectedCatalog.section.title} | ${uiState.typeFilter.label}",
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

                            if (pagingStatusMessage.isNotBlank() && pagingStatusMessage != uiState.statusMessage) {
                                Text(
                                    text = pagingStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if ((uiState.isRefreshing && uiState.catalogs.isEmpty()) || (refreshState is LoadState.Loading && pagingItems.itemCount == 0 && selectedCatalog != null)) {
                    items(DISCOVER_SKELETON_COUNT, span = { GridItemSpan(1) }, key = { index -> "discover-skeleton-$index" }) {
                        DiscoverPosterSkeleton(modifier = Modifier.fillMaxWidth())
                    }
                } else if (pagingItems.itemCount == 0) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(Dimensions.ListItemPadding),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text =
                                        when {
                                            selectedCatalog == null -> "Select a catalog to start discovering"
                                            refreshState is LoadState.Error -> refreshState.error.message ?: "Failed to load results."
                                            else -> "No content found"
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (refreshState is LoadState.Error) {
                                    FilledTonalButton(onClick = onRefresh) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(
                        count = pagingItems.itemCount,
                        key = pagingItems.itemKey { "${it.type}:${it.id}" }
                    ) { index ->
                        val item = pagingItems[index] ?: return@items
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

                if (appendState is LoadState.Loading) {
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
                } else if (appendState is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            FilledTonalButton(onClick = { pagingItems.retry() }) {
                                Text("Retry")
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
                                                 text = catalog.addonName,
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

                null -> Unit
            }
        }
    }
}

@Composable
private fun DiscoverPosterSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .skeletonElement(pulse = false),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .skeletonElement(pulse = false),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(84.dp)
                .height(12.dp)
                .skeletonElement(pulse = false),
        )
    }
}

private const val DISCOVER_SKELETON_COUNT = 9
