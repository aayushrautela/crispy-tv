package com.crispy.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
    onBack: () -> Unit,
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
        onBack = onBack,
        onItemClick = onItemClick
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val query = uiState.query.trim()
    val filters =
        remember {
            listOf(
                SearchTypeFilter.ALL to "All",
                SearchTypeFilter.MOVIES to "Movies",
                SearchTypeFilter.SERIES to "Series",
                SearchTypeFilter.PEOPLE to "People"
            )
        }

    Scaffold(
        topBar = {
            StandardTopAppBar(
                title = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Search",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        TextField(
                            value = uiState.query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.fillMaxWidth().height(Dimensions.SearchBarPillHeight),
                            singleLine = true,
                            placeholder = { Text("Search") },
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
                                            contentDescription = "Clear"
                                        )
                                    }
                                }
                            },
                            shape = MaterialTheme.shapes.extraLarge,
                            colors =
                                TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                        )

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filters, key = { it.first.name }) { (filter, label) ->
                                FilterChip(
                                    selected = uiState.filter == filter,
                                    onClick = { onFilterChange(filter) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
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
            if (query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(text = "Start typing to search")
                }
                return@LazyVerticalGrid
            }

            if (uiState.isSearching) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadingIndicator()
                }
            }

            if (!uiState.isSearching && uiState.results.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchEmptyState(text = "No results")
                }
            }

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
