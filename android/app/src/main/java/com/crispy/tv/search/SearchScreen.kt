package com.crispy.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.LocalAppChromeInsets
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import java.util.Locale

@Composable
fun SearchRoute(
    onItemClick: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: SearchViewModel =
        viewModel(
            factory = remember(appContext) {
                SearchViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onClearQuery = viewModel::clearQuery,
        onSearch = viewModel::submitQuery,
        onFilterChange = viewModel::setFilter,
        onGenreSuggestionClick = viewModel::selectGenreSuggestion,
        onClearGenreSuggestion = viewModel::clearGenreSuggestion,
        onRecentSearchClick = viewModel::selectRecentSearch,
        onRemoveRecentSearch = viewModel::removeRecentSearch,
        onClearRecentSearches = viewModel::clearRecentSearches,
        onItemClick = { item ->
            viewModel.rememberCurrentQuery()
            onItemClick(item)
        }
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onGenreSuggestionClick: (SearchGenreSuggestion) -> Unit,
    onClearGenreSuggestion: () -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val chromePadding = LocalAppChromeInsets.current.asPaddingValues()
    val bottomInset = chromePadding.calculateBottomPadding()
    val activeGenreSuggestion = uiState.activeGenreSuggestion
    val filters =
        remember(activeGenreSuggestion) {
            SearchTypeFilter.entries.filter {
                activeGenreSuggestion == null || it.supportsGenreSuggestions
            }
        }
    val hasTypedQuery = uiState.trimmedQuery.isNotBlank()
    val hasGenreResults = activeGenreSuggestion != null
    val showResultFilters = hasTypedQuery || hasGenreResults
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val showRecentSearches = isSearchActive && !showResultFilters
    val showInactiveResults = !isSearchActive && showResultFilters
    val searchBarModifier =
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .then(
                if (isSearchActive) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = pageHorizontalPadding, vertical = 12.dp)
                }
            )

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchBar(
                modifier = searchBarModifier,
                query = uiState.query,
                onQueryChange = {
                    isSearchActive = true
                    onQueryChange(it)
                },
                onSearch = {
                    onSearch()
                    isSearchActive = false
                },
                active = isSearchActive,
                onActiveChange = { isSearchActive = it },
                placeholder = {
                    Text("Search movies, shows, and people")
                },
                leadingIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = { isSearchActive = false }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Close search"
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null
                        )
                    }
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                },
                colors = SearchBarDefaults.colors()
            ) {
                if (showRecentSearches) {
                    RecentSearchesContent(
                        recentSearches = uiState.recentSearches,
                        pageHorizontalPadding = pageHorizontalPadding,
                        bottomInset = bottomInset,
                        onRecentSearchClick = onRecentSearchClick,
                        onRemoveRecentSearch = onRemoveRecentSearch,
                        onClearRecentSearches = onClearRecentSearches,
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                    )
                } else {
                    SearchResultsContent(
                        uiState = uiState,
                        filters = filters,
                        showFilters = showResultFilters,
                        pageHorizontalPadding = pageHorizontalPadding,
                        bottomInset = bottomInset,
                        onFilterChange = onFilterChange,
                        onClearGenreSuggestion = onClearGenreSuggestion,
                        onItemClick = onItemClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showInactiveResults) {
                SearchResultsContent(
                    uiState = uiState,
                    filters = filters,
                    showFilters = true,
                    pageHorizontalPadding = pageHorizontalPadding,
                    bottomInset = bottomInset,
                    onFilterChange = onFilterChange,
                    onClearGenreSuggestion = onClearGenreSuggestion,
                    onItemClick = onItemClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            if (!isSearchActive && !showResultFilters) {
                BrowseContent(
                    genreSuggestions = SearchGenreSuggestion.entries,
                    pageHorizontalPadding = pageHorizontalPadding,
                    bottomInset = bottomInset,
                    onGenreSuggestionClick = {
                        isSearchActive = false
                        onGenreSuggestionClick(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RecentSearchesContent(
    recentSearches: List<String>,
    pageHorizontalPadding: Dp,
    bottomInset: Dp,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SmallSpacing,
                bottom = bottomInset + Dimensions.PageBottomPadding
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (recentSearches.isEmpty()) {
            item {
                SearchEmptyState(text = "Your recent searches appear here")
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClearRecentSearches) {
                        Text("Clear all")
                    }
                }
            }

            items(
                items = recentSearches,
                key = { it.lowercase(Locale.ROOT) }
            ) { query ->
                RecentSearchRow(
                    query = query,
                    onClick = { onRecentSearchClick(query) },
                    onRemoveClick = { onRemoveRecentSearch(query) }
                )
            }
        }
    }
}

@Composable
private fun BrowseContent(
    genreSuggestions: List<SearchGenreSuggestion>,
    pageHorizontalPadding: Dp,
    bottomInset: Dp,
    onGenreSuggestionClick: (SearchGenreSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SmallSpacing,
                bottom = bottomInset + Dimensions.PageBottomPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Explore genres",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
            )
        }
        items(genreSuggestions, key = { it.name }) { genre ->
            GenreCard(
                genre = genre,
                onClick = { onGenreSuggestionClick(genre) }
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    filters: List<SearchTypeFilter>,
    showFilters: Boolean,
    pageHorizontalPadding: Dp,
    bottomInset: Dp,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onClearGenreSuggestion: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeGenreSuggestion = uiState.activeGenreSuggestion

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SmallSpacing,
                bottom = bottomInset + Dimensions.PageBottomPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showFilters) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters, key = { it.name }) { filter ->
                        FilterChip(
                            selected = uiState.filter == filter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }
        }

        if (activeGenreSuggestion != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ActiveGenreSuggestionHeader(
                    genreSuggestion = activeGenreSuggestion,
                    onClear = onClearGenreSuggestion
                )
            }
        }

        when {
            uiState.isLoading -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadingIndicator()
                }
            }

            uiState.results.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(
                        text = activeGenreSuggestion?.let { "No ${it.label} titles found" } ?: "No results"
                    )
                }
            }

            else -> {
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
        }
    }
}

@Composable
private fun RecentSearchRow(
    query: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Outlined.Clear,
                contentDescription = "Remove recent search"
            )
        }
    }
}

@Composable
private fun ActiveGenreSuggestionHeader(
    genreSuggestion: SearchGenreSuggestion,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Showing ${genreSuggestion.label}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Outlined.Clear,
                contentDescription = "Clear genre suggestion"
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
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun SearchLoadingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun GenreCard(
    genre: SearchGenreSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(id = genre.imageRes),
            contentDescription = genre.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )
        Text(
            text = genre.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        )
    }
}
