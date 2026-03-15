package com.crispy.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val viewModel = rememberSearchViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val browseListState = rememberLazyListState()
    val resultsGridState = rememberLazyGridState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest, uiState.hasActiveResults) {
        if (scrollToTopRequest > 0) {
            if (uiState.hasActiveResults) {
                resultsGridState.animateScrollToItem(0)
            } else {
                browseListState.animateScrollToItem(0)
            }
            onScrollToTopConsumed()
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchTopBar(
                query = uiState.query,
                onQueryChange = viewModel::updateQuery,
                onSearch = viewModel::submitSearch,
                onClear = viewModel::clearSearch,
                onOpenAccountsProfiles = onOpenAccountsProfiles,
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            if (uiState.hasActiveResults) {
                SearchResultsContent(
                    uiState = uiState,
                    gridState = resultsGridState,
                    pageHorizontalPadding = pageHorizontalPadding,
                    onFilterChange = viewModel::setFilter,
                    onItemClick = onItemClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                )
            } else {
                SearchBrowseContent(
                    uiState = uiState,
                    listState = browseListState,
                    pageHorizontalPadding = pageHorizontalPadding,
                    onGenreClick = viewModel::selectGenre,
                    onRecentSearchClick = viewModel::submitSearch,
                    onRemoveRecentSearch = viewModel::removeRecentSearch,
                    onClearRecentSearches = viewModel::clearRecentSearches,
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                )
            }
        }
    }
}

@Composable
private fun SearchBrowseContent(
    uiState: SearchUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    pageHorizontalPadding: Dp,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
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
            top = Dimensions.SmallSpacing,
            end = pageHorizontalPadding,
            bottom = Dimensions.PageBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            SearchSectionHeader(
                title = "Genres",
                subtitle = "Pick a genre to browse instantly, or type a title above.",
            )
        }
        item {
            GenreSuggestionsRow(onGenreClick = onGenreClick)
        }

        if (uiState.recentSearches.isEmpty()) {
            item {
                SearchSectionHeader(
                    title = "Recent searches",
                    subtitle = "Your recent searches will show up here.",
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchSectionTitle(text = "Recent searches")
                    TextButton(onClick = onClearRecentSearches) {
                        Text("Clear all")
                    }
                }
            }
            items(
                items = uiState.recentSearches,
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
private fun GenreSuggestionsRow(
    onGenreClick: (SearchGenreSuggestion) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SearchGenreSuggestion.entries, key = { it.name }) { genre ->
            SuggestionChip(
                onClick = { onGenreClick(genre) },
                icon = {
                    Icon(
                        painter = painterResource(id = genre.imageRes),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(genre.label)
                },
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    pageHorizontalPadding: Dp,
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
            SearchResultsHeader(
                uiState = uiState,
                onFilterChange = onFilterChange,
            )
        }

        when {
            uiState.isLoading -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadingIndicator()
                }
            }

            uiState.results.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(text = "No results")
                }
            }

            else -> {
                gridItems(
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
private fun SearchResultsHeader(
    uiState: SearchUiState,
    onFilterChange: (SearchTypeFilter) -> Unit,
) {
    val resultLabel = uiState.selectedGenre?.let { "Popular in ${it.label}" } ?: uiState.appliedQuery.let { "Results for \"$it\"" }
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SearchSectionHeader(
            title = "Search",
            subtitle = resultLabel,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.availableFilters, key = { it.name }) { filter ->
                FilterChip(
                    selected = uiState.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    subtitle: String,
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SearchSectionTitle(text = title)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
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
