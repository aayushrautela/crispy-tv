package com.crispy.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchResultsContent(
    uiState: SearchUiState,
    onFilterChange: (SearchTypeFilter) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = pageHorizontalPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = uiState.filter == SearchTypeFilter.ALL,
                        onClick = { onFilterChange(SearchTypeFilter.ALL) },
                        label = { Text("All") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.filter == SearchTypeFilter.MOVIES,
                        onClick = { onFilterChange(SearchTypeFilter.MOVIES) },
                        label = { Text("Movies") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.filter == SearchTypeFilter.SERIES,
                        onClick = { onFilterChange(SearchTypeFilter.SERIES) },
                        label = { Text("Series") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.filter == SearchTypeFilter.PEOPLE,
                        onClick = { onFilterChange(SearchTypeFilter.PEOPLE) },
                        label = { Text("People") }
                    )
                }
            }
        }

        if (uiState.isSearching) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchLoadingIndicator()
            }
        }

        gridItems(
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
                onClick = { onItemClick(item) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchLoadingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(modifier = Modifier.size(36.dp))
    }
}
