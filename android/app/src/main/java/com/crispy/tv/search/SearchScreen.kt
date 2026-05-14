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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.History

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.components.skeletonElement
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
    val resultsScrollState = rememberScrollState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest, uiState.hasActiveResults) {
        if (scrollToTopRequest > 0) {
            if (uiState.hasActiveResults) {
                resultsScrollState.animateScrollTo(0)
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
            SearchTopBar(onOpenAccountsProfiles = onOpenAccountsProfiles)
        },
    ) { paddingValues ->
        val contentModifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()

        SearchContent(
            uiState = uiState,
            browseGridState = browseGridState,
            resultsScrollState = resultsScrollState,
            pageHorizontalPadding = pageHorizontalPadding,
            onQueryChange = viewModel::updateQuery,
            onSearch = viewModel::submitSearch,
            onAiSearch = viewModel::submitAiSearch,
            onClear = viewModel::clearSearch,
            onSuggestionClick = viewModel::selectSuggestion,
            onGenreClick = viewModel::selectGenre,
            onRecentSearchClick = viewModel::submitSearch,
            onRemoveRecentSearch = viewModel::removeRecentSearch,
            onItemClick = onItemClick,
            modifier = contentModifier,
        )
    }
}

@Composable
private fun SearchContent(
    uiState: SearchUiState,
    browseGridState: LazyGridState,
    resultsScrollState: ScrollState,
    pageHorizontalPadding: Dp,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAiSearch: () -> Unit,
    onClear: () -> Unit,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAiMode = uiState.searchMode == SearchMode.AI

    Column(modifier = modifier) {
        SearchBar(
            query = uiState.query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onAiSearch = onAiSearch,
            onClear = onClear,
            isAiActive = isAiMode && uiState.hasActiveResults,
            isAiLoading = isAiMode && uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pageHorizontalPadding, vertical = Dimensions.SmallSpacing),
        )

        when {
            uiState.hasActiveResults -> SearchResultsContent(
                uiState = uiState,
                scrollState = resultsScrollState,
                pageHorizontalPadding = pageHorizontalPadding,
                onItemClick = onItemClick,
                emptyMessage = uiState.statusMessage,
                modifier = Modifier.fillMaxSize(),
            )

            uiState.shouldShowSuggestions -> SearchSuggestionsContent(
                suggestions = uiState.suggestions,
                isLoading = uiState.isLoadingSuggestions,
                onSuggestionClick = onSuggestionClick,
                pageHorizontalPadding = pageHorizontalPadding,
                modifier = Modifier.fillMaxSize(),
            )

            else -> SearchBrowseContent(
                uiState = uiState,
                gridState = browseGridState,
                pageHorizontalPadding = pageHorizontalPadding,
                onGenreClick = onGenreClick,
                onRecentSearchClick = onRecentSearchClick,
                onRemoveRecentSearch = onRemoveRecentSearch,
                modifier = Modifier.fillMaxSize(),
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
    modifier: Modifier = Modifier,
) {
    SearchGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        pageHorizontalPadding = pageHorizontalPadding,
        modifier = modifier,
    ) {
        if (uiState.recentSearches.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecentSearchStrip(
                    recentSearches = uiState.recentSearches,
                    onRecentSearchClick = onRecentSearchClick,
                    onRemoveRecentSearch = onRemoveRecentSearch,
                )
            }
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
private fun RecentSearchStrip(
    recentSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
) {
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
    scrollState: ScrollState,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem) -> Unit,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
) {
    val buckets = uiState.resultBuckets
    val isLoading = uiState.isLoading

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = pageHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when {
            isLoading && buckets.isEmpty -> {
                SearchSectionSkeleton()
                SearchSectionSkeleton()
                SearchSectionSkeleton()
            }

            buckets.isEmpty -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMessage
                            ?: if (uiState.searchMode == SearchMode.AI) "No AI matches found." else "No results",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (buckets.movies.isNotEmpty()) {
                    SearchSectionRow(title = "Movies", items = buckets.movies, onItemClick = onItemClick)
                }
                if (buckets.series.isNotEmpty()) {
                    SearchSectionRow(title = "Series", items = buckets.series, onItemClick = onItemClick)
                }
                if (buckets.people.isNotEmpty()) {
                    SearchSectionRow(title = "People", items = buckets.people, onItemClick = onItemClick)
                }
            }
        }
    }
}

@Composable
private fun SearchSectionRow(
    title: String,
    items: List<CatalogItem>,
    onItemClick: (CatalogItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                PosterCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    rating = item.rating,
                    year = item.year,
                    genre = item.genre,
                    logoUrl = item.logoUrl,
                    poster = item.poster,
                    backdrop = item.backdrop,
                    logo = item.logo,
                    gradientColorHex = null,
                    modifier = Modifier.width(124.dp),
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun SearchSectionSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(24.dp)
                .skeletonElement(pulse = false),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(6) {
                Box(
                    modifier = Modifier
                        .width(124.dp)
                        .aspectRatio(2f / 3f)
                        .skeletonElement(pulse = false),
                )
            }
        }
    }
}

@Composable
private fun GenreTab(
    genre: SearchGenreSuggestion,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(id = genre.imageResId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x1A000000)),
        )
        Text(
            text = genre.label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchSuggestionsContent(
    suggestions: List<SearchSuggestion>,
    isLoading: Boolean,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    pageHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = pageHorizontalPadding),
    ) {
        if (isLoading && suggestions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (suggestions.isEmpty()) {
            Text(
                text = "Keep typing to search",
                modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            suggestions.forEach { suggestion ->
                SearchSuggestionRow(
                    suggestion = suggestion,
                    onClick = { onSuggestionClick(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    suggestion: SearchSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(if (suggestion.mediaType == "series") "Series" else "Movie")
                if (suggestion.year != null) {
                    append(" · ${suggestion.year}")
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
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




