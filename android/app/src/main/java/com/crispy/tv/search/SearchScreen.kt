package com.crispy.tv.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

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
        onFilterChange = viewModel::setFilter,
        onGenreSuggestionClick = viewModel::selectGenreSuggestion,
        onClearGenreSuggestion = viewModel::clearGenreSuggestion,
        onItemClick = onItemClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onGenreSuggestionClick: (SearchGenreSuggestion) -> Unit,
    onClearGenreSuggestion: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val activeGenreSuggestion = uiState.activeGenreSuggestion
    val filters = SearchTypeFilter.entries
    val genreSuggestions = SearchGenreSuggestion.entries

    Scaffold(
        topBar = {
            StandardTopAppBar(title = "Search")
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding =
                PaddingValues(
                    start = pageHorizontalPadding,
                    end = pageHorizontalPadding,
                    top = innerPadding.calculateTopPadding() + Dimensions.SmallSpacing,
                    bottom = Dimensions.PageBottomPadding
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search movies, shows, and people") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (uiState.query.isNotBlank()) {
                            IconButton(onClick = onClearQuery) {
                                Icon(
                                    imageVector = Icons.Outlined.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    }
                )
            }

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

            if (activeGenreSuggestion != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ActiveGenreSuggestionHeader(
                        genreSuggestion = activeGenreSuggestion,
                        onClear = onClearGenreSuggestion
                    )
                }
            }

            when {
                uiState.showGenreSuggestions -> {
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

                uiState.showBlankSearchHint -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SearchEmptyState(text = "Search for people by name")
                    }
                }

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
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
