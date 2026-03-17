package com.crispy.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val browseGridState = rememberLazyGridState()
    val resultsGridState = rememberLazyGridState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest, uiState.hasActiveResults) {
        if (scrollToTopRequest > 0) {
            val targetGridState = if (uiState.hasActiveResults) resultsGridState else browseGridState
            targetGridState.animateScrollToItem(0)
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
        val contentModifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()

        if (uiState.hasActiveResults) {
            SearchResultsContent(
                uiState = uiState,
                gridState = resultsGridState,
                pageHorizontalPadding = pageHorizontalPadding,
                onFilterChange = viewModel::setFilter,
                onItemClick = onItemClick,
                modifier = contentModifier,
            )
        } else {
            SearchBrowseContent(
                uiState = uiState,
                gridState = browseGridState,
                pageHorizontalPadding = pageHorizontalPadding,
                onGenreClick = viewModel::selectGenre,
                onRecentSearchClick = viewModel::submitSearch,
                onRemoveRecentSearch = viewModel::removeRecentSearch,
                onClearRecentSearches = viewModel::clearRecentSearches,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun SearchBrowseContent(
    uiState: SearchUiState,
    gridState: LazyGridState,
    pageHorizontalPadding: Dp,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        pageHorizontalPadding = pageHorizontalPadding,
        modifier = modifier,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchBrowseHeader(
                recentSearches = uiState.recentSearches,
                onRecentSearchClick = onRecentSearchClick,
                onRemoveRecentSearch = onRemoveRecentSearch,
                onClearRecentSearches = onClearRecentSearches,
            )
        }
        gridItems(
            items = SearchGenreSuggestion.entries,
            key = { it.name },
        ) { genre ->
            GenreTab(
                genre = genre,
                onClick = { onGenreClick(genre) },
            )
        }
    }
}

@Composable
private fun SearchBrowseHeader(
    recentSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (recentSearches.isNotEmpty()) {
            RecentSearchStrip(
                recentSearches = recentSearches,
                onRecentSearchClick = onRecentSearchClick,
                onRemoveRecentSearch = onRemoveRecentSearch,
                onClearRecentSearches = onClearRecentSearches,
            )
        }
        SearchSectionHeader(
            title = "Genres",
            subtitle = "Small genre tabs for quick browsing.",
        )
    }
}

@Composable
private fun RecentSearchStrip(
    recentSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchSectionTitle(text = "History")
            TextButton(onClick = onClearRecentSearches) {
                Text("Clear")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                items = recentSearches,
                key = { it.lowercase(Locale.ROOT) },
            ) { query ->
                RecentSearchChip(
                    query = query,
                    onClick = { onRecentSearchClick(query) },
                    onRemoveClick = { onRemoveRecentSearch(query) },
                )
            }
        }
    }
}

@Composable
private fun RecentSearchChip(
    query: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = query,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemoveClick)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Clear,
                contentDescription = "Remove recent search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    gridState: LazyGridState,
    pageHorizontalPadding: Dp,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 124.dp),
        pageHorizontalPadding = pageHorizontalPadding,
        modifier = modifier,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchFilterRow(
                availableFilters = uiState.availableFilters,
                selectedFilter = uiState.filter,
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
private fun SearchFilterRow(
    availableFilters: List<SearchTypeFilter>,
    selectedFilter: SearchTypeFilter,
    onFilterChange: (SearchTypeFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(availableFilters, key = { it.name }) { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    subtitle: String,
) {
    Column(
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
private fun GenreTab(
    genre: SearchGenreSuggestion,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = genre.label,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchGrid(
    state: LazyGridState,
    columns: GridCells,
    pageHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = pageHorizontalPadding,
            top = Dimensions.SmallSpacing,
            end = pageHorizontalPadding,
            bottom = Dimensions.PageBottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
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
