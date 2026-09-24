package com.crispy.tv.discover

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.assets.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.search.SearchGenreSuggestion
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.theme.CrispySpinner
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class DiscoverTypeFilter(val label: String, val value: String) {
    All(label = "All", value = "all"),
    Movies(label = "Movies", value = "movie"),
    Series(label = "Shows", value = "series")
}

enum class DiscoverSortFilter(val label: String, val value: String) {
    Trending(label = "Trending", value = "popularity"),
    Rating(label = "Rating", value = "rating"),
    ReleaseDate(label = "Release date", value = "release")
}

@Immutable
data class DiscoverUiState(
    val typeFilter: DiscoverTypeFilter = DiscoverTypeFilter.All,
    val genreKey: String? = null,
    val genreLabel: String? = null,
    val sortFilter: DiscoverSortFilter = DiscoverSortFilter.Trending,
) {
    val comboKey: String
        get() = "${typeFilter.value}|${genreKey.orEmpty()}|${sortFilter.value}"
}

class DiscoverViewModel(
    private val repository: BackendBrowseRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<CatalogItem>> =
        _uiState
            .map { it.comboKey }
            .distinctUntilChanged()
            .flatMapLatest { _ ->
                val state = _uiState.value
                Pager(
                    config =
                        PagingConfig(
                            pageSize = PAGE_SIZE,
                            initialLoadSize = PAGE_SIZE,
                            prefetchDistance = 10,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = {
                        BrowsePagingSource(
                            repository = repository,
                            type = state.typeFilter.value,
                            genre = state.genreKey,
                            sort = state.sortFilter.value,
                        )
                    },
                ).flow
            }.cachedIn(viewModelScope)

    fun setTypeFilter(filter: DiscoverTypeFilter) {
        _uiState.update { it.copy(typeFilter = filter) }
    }

    fun setGenre(genre: SearchGenreSuggestion?) {
        _uiState.update {
            it.copy(
                genreKey = genre?.key,
                genreLabel = genre?.label,
            )
        }
    }

    fun setSortFilter(filter: DiscoverSortFilter) {
        _uiState.update { it.copy(sortFilter = filter) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return DiscoverViewModel(
                            repository = BackendBrowseRepository.create(appContext)
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
    onItemClick: (CatalogItem, String?) -> Unit
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
                onRefresh = { pagingItems.refresh() },
                onTypeFilterClick = viewModel::setTypeFilter,
                onGenreClick = viewModel::setGenre,
                onSortClick = viewModel::setSortFilter,
                onItemClick = onItemClick,
                scrollToTopRequests = scrollToTopRequests,
                onScrollToTopConsumed = onScrollToTopConsumed,
            )
        }
    }
}

private enum class DiscoverSheet {
    Type,
    Genre,
    Sort
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscoverScreen(
    uiState: DiscoverUiState,
    pagingItems: LazyPagingItems<CatalogItem>,
    onRefresh: () -> Unit,
    onTypeFilterClick: (DiscoverTypeFilter) -> Unit,
    onGenreClick: (SearchGenreSuggestion?) -> Unit,
    onSortClick: (DiscoverSortFilter) -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    var activeSheet by remember { mutableStateOf<DiscoverSheet?>(null) }
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val gridState = rememberLazyGridState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val isRefreshing = refreshState is LoadState.Loading && pagingItems.itemCount > 0
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
    val genreLabel = uiState.genreLabel ?: "All genres"

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
            columns = GridCells.Adaptive(minSize = CardStyle.landscapeCardMinCell()),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                top = Dimensions.SmallSpacing,
                end = pageHorizontalPadding,
                bottom = safeBottomPadding(),
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
                                        painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                                        contentDescription = null
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color(0xFF141414),
                                ),
                            )
                        }

                        item {
                            FilterChip(
                                selected = false,
                                onClick = { activeSheet = DiscoverSheet.Genre },
                                label = {
                                    Text(
                                        text = genreLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                                        contentDescription = null
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color(0xFF141414),
                                ),
                            )
                        }

                        item {
                            FilterChip(
                                selected = false,
                                onClick = { activeSheet = DiscoverSheet.Sort },
                                label = {
                                    Text(
                                        text = uiState.sortFilter.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                                        contentDescription = null
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color(0xFF141414),
                                ),
                            )
                        }

                    }
                }
                if (pagingStatusMessage.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = pagingStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Dimensions.SmallSpacing)
                        )
                    }
                }

                if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
                    items(DISCOVER_SKELETON_COUNT, span = { GridItemSpan(1) }, key = { index -> "discover-skeleton-$index" }, contentType = { "posterSkeleton" }) {
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
                                            refreshState is LoadState.Error -> refreshState.error.message ?: "Failed to load results."
                                            else -> "No results found. Try changing the filters."
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
                        key = pagingItems.itemKey { "${it.type}:${it.id}" },
                        contentType = { "poster" }
                    ) { index ->
                        val item = pagingItems[index] ?: return@items
                        val key = "discover-${item.itemId}-${index}"
                          LandscapeCard(
                              title = item.title,
                              artworkUrl = item.artworkUrl,
                              logoUrl = item.logoUrl,
                              artwork = item.artwork,
                              logo = item.logo,
                             rating = item.rating,
                             year = item.year,
                             genre = item.genre,
                             onClick = { onItemClick(item, key) },
                             itemId = item.itemId,
                             sharedElementKey = key,
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
                            LoadingIndicator(modifier = Modifier.size(20.dp), color = CrispySpinner)
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
                        contentPadding = PaddingValues(bottom = safeBottomPadding())
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
                                    trailingContent =
                                        if (uiState.typeFilter == filter) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
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
                                ) {
                                    Text(filter.label) 
                                }
                            }
                        }
                    }

                    DiscoverSheet.Genre -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = safeBottomPadding())
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
                                    leadingContent = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_layers),
                                            contentDescription = null
                                        )
                                    },
                                    trailingContent =
                                        if (uiState.genreKey == null) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
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
                                        .padding(horizontal = 4.dp)
                                ) {
                                    Text("All genres")
                                }
                            }
                            items(SearchGenreSuggestion.entries) { genre ->
                                ListItem(
                                    leadingContent = {
                                        Icon(
                                            painter = painterResource(genre.imageResId),
                                            contentDescription = null
                                        )
                                    },
                                    trailingContent =
                                        if (uiState.genreKey == genre.key) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
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
                                        .padding(horizontal = 4.dp)
                                ) {
                                    Text(genre.label)
                                }
                            }
                        }
                    }

                    DiscoverSheet.Sort -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = safeBottomPadding())
                    ) {
                            item {
                                Text(
                                    text = "Sort by",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = Dimensions.SmallSpacing)
                                )
                            }
                            items(DiscoverSortFilter.entries) { filter ->
                                ListItem(
                                    trailingContent =
                                        if (uiState.sortFilter == filter) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    modifier = Modifier
                                        .fillMaxWidth()
.clickable {
                                            onSortClick(filter)
                                            activeSheet = null
                                        }
                                        .padding(horizontal = 4.dp)
                                ) {
                                    Text(filter.label)
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(CardStyle.LandscapeAspectRatio)
            .skeletonElement(pulse = false),
    )
}

private const val DISCOVER_SKELETON_COUNT = 9
