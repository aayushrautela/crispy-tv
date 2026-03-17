package com.crispy.tv.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
            if (uiState.hasActiveResults) {
                resultsGridState.animateScrollToItem(0)
            } else {
                browseGridState.animateScrollToItem(0)
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
                    gridState = browseGridState,
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
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    pageHorizontalPadding: Dp,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quickPickTiles = quickPickTiles()

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = pageHorizontalPadding,
            top = Dimensions.SmallSpacing,
            end = pageHorizontalPadding,
            bottom = Dimensions.PageBottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (uiState.recentSearches.isNotEmpty()) {
                    RecentSearchStrip(
                        recentSearches = uiState.recentSearches,
                        onRecentSearchClick = onRecentSearchClick,
                        onRemoveRecentSearch = onRemoveRecentSearch,
                        onClearRecentSearches = onClearRecentSearches,
                    )
                }
                SearchSectionHeader(
                    title = "Quick picks",
                    subtitle = "Browse a genre instantly with compact visual tiles.",
                )
            }
        }
        gridItems(
            items = quickPickTiles,
            key = { it.genre.name },
            span = { tile -> GridItemSpan(tile.span) },
        ) { tile ->
            QuickPickTile(
                tile = tile,
                onClick = { onGenreClick(tile.genre) },
            )
        }
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
    val resultLabel =
        uiState.selectedGenre?.let { "Popular in ${it.label}" }
            ?: uiState.appliedQuery.let { "Results for \"$it\"" }
    Column(
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
private fun QuickPickTile(
    tile: QuickPickTileModel,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (tile.span == 2) 1.85f else 1f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Image(
            painter = painterResource(id = tile.genre.imageRes),
            contentDescription = tile.genre.label,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tile.overlayBrush),
        )
        QuickPickLabel(tile = tile)
    }
}

@Composable
private fun BoxScope.QuickPickLabel(tile: QuickPickTileModel) {
    val isCentered = tile.textPlacement == QuickPickTextPlacement.CENTER
    Text(
        text = tile.genre.label,
        modifier = Modifier
            .align(if (isCentered) Alignment.Center else Alignment.BottomStart)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        style = if (isCentered) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
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

private data class QuickPickTileModel(
    val genre: SearchGenreSuggestion,
    val span: Int,
    val textPlacement: QuickPickTextPlacement,
) {
    val overlayBrush: Brush
        get() =
            if (textPlacement == QuickPickTextPlacement.CENTER) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.5f),
                        Color.Black.copy(alpha = 0.22f),
                        Color.Black.copy(alpha = 0.58f),
                    ),
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.18f),
                        Color.Black.copy(alpha = 0.78f),
                    ),
                )
            }
}

private enum class QuickPickTextPlacement {
    CENTER,
    BOTTOM_START,
}

private fun quickPickTiles(): List<QuickPickTileModel> {
    return listOf(
        QuickPickTileModel(SearchGenreSuggestion.ACTION, span = 2, textPlacement = QuickPickTextPlacement.BOTTOM_START),
        QuickPickTileModel(SearchGenreSuggestion.ANIMATED, span = 1, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.COMEDY, span = 1, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.DOCUMENTARY, span = 1, textPlacement = QuickPickTextPlacement.BOTTOM_START),
        QuickPickTileModel(SearchGenreSuggestion.DRAMA, span = 1, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.FAMILY, span = 1, textPlacement = QuickPickTextPlacement.BOTTOM_START),
        QuickPickTileModel(SearchGenreSuggestion.FANTASY, span = 1, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.HORROR, span = 2, textPlacement = QuickPickTextPlacement.BOTTOM_START),
        QuickPickTileModel(SearchGenreSuggestion.MYSTERY, span = 1, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.ROMANCE, span = 1, textPlacement = QuickPickTextPlacement.BOTTOM_START),
        QuickPickTileModel(SearchGenreSuggestion.SCI_FI, span = 2, textPlacement = QuickPickTextPlacement.CENTER),
        QuickPickTileModel(SearchGenreSuggestion.THRILLER, span = 1, textPlacement = QuickPickTextPlacement.BOTTOM_START),
    )
}
