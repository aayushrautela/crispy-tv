package com.crispy.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onSearchRequested: (String) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val viewModel = rememberSearchViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            listState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchInputTopBar(
                query = query,
                onQueryChange = { query = it },
                onBack = onBack,
                onClear = { query = "" },
                onSearch = {
                    val normalizedQuery = query.trim()
                    if (normalizedQuery.isNotBlank()) {
                        viewModel.recordQuery(normalizedQuery)
                        onSearchRequested(normalizedQuery)
                    }
                },
            )
        },
    ) { innerPadding ->
        RecentSearchesContent(
            recentSearches = uiState.recentSearches,
            listState = listState,
            pageHorizontalPadding = pageHorizontalPadding,
            topPadding = innerPadding.calculateTopPadding() + Dimensions.SmallSpacing,
            onRecentSearchClick = { recentQuery ->
                viewModel.recordQuery(recentQuery)
                onSearchRequested(recentQuery)
            },
            onRemoveRecentSearch = viewModel::removeRecentSearch,
            onClearRecentSearches = viewModel::clearRecentSearches,
            modifier = Modifier.fillMaxSize().imePadding(),
        )
    }
}

@Composable
fun SearchResultsRoute(
    initialQuery: String,
    onBack: () -> Unit,
    onSearchRequested: (String) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val viewModel = rememberSearchViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }

    LaunchedEffect(initialQuery) {
        query = initialQuery
        viewModel.showQuery(initialQuery)
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            gridState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        SearchResultsBar(
            query = query,
            onQueryChange = { query = it },
            onBack = onBack,
            onClear = { query = "" },
            onSearch = {
                val normalizedQuery = query.trim()
                if (normalizedQuery.isNotBlank()) {
                    viewModel.recordQuery(normalizedQuery)
                    if (normalizedQuery == uiState.trimmedQuery) {
                        viewModel.showQuery(normalizedQuery)
                    } else {
                        onSearchRequested(normalizedQuery)
                    }
                }
            },
        )
        SearchResultsContent(
            uiState = uiState,
            gridState = gridState,
            pageHorizontalPadding = pageHorizontalPadding,
            onFilterChange = viewModel::setFilter,
            onItemClick = onItemClick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun rememberSearchViewModel(): SearchViewModel {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    return viewModel(
        factory = remember(appContext) {
            SearchViewModel.factory(appContext)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInputTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    TopAppBar(
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                    ) {
                        if (query.isBlank()) {
                            Text(
                                text = "Search movies, shows, and people",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = {
            if (query.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = "Clear search",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SearchResultsBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = RoundedCornerShape(28.dp),
            placeholder = {
                Text("Search movies, shows, and people")
            },
            leadingIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = "Clear search",
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
    }
}

@Composable
private fun RecentSearchesContent(
    recentSearches: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    pageHorizontalPadding: androidx.compose.ui.unit.Dp,
    topPadding: androidx.compose.ui.unit.Dp,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = pageHorizontalPadding,
            top = topPadding,
            end = pageHorizontalPadding,
            bottom = Dimensions.PageBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recentSearches.isEmpty()) {
            item {
                SearchEmptyState(text = "Recent searches show up here")
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClearRecentSearches) {
                        Text("Clear all")
                    }
                }
            }

            items(
                items = recentSearches,
                key = { it.lowercase(Locale.ROOT) },
            ) { query ->
                RecentSearchRow(
                    query = query,
                    onClick = { onRecentSearchClick(query) },
                    onRemoveClick = { onRemoveRecentSearch(query) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    pageHorizontalPadding: androidx.compose.ui.unit.Dp,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 124.dp),
        modifier = modifier,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchTypeFilter.entries, key = { it.name }) { filter ->
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
        }

        when {
            uiState.isLoading -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadingIndicator()
                }
            }

            uiState.trimmedQuery.isBlank() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(text = "Search movies, shows, and people")
                }
            }

            uiState.results.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(text = "No results")
                }
            }

            else -> {
                items(
                    items = uiState.results,
                    key = { "${it.type}:${it.id}" },
                ) { item ->
                    PosterCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = item.rating,
                        year = item.year,
                        genre = item.genre,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Outlined.Clear,
                contentDescription = "Remove recent search",
            )
        }
    }
}

@Composable
private fun SearchEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchLoadingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
    }
}
