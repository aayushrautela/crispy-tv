package com.crispy.tv.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchResultsContent(
    uiState: SearchUiState,
    onMediaTypeChange: (MetadataLabMediaType?) -> Unit,
    onCatalogToggle: (String) -> Unit,
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
                        selected = uiState.mediaType == null,
                        onClick = { onMediaTypeChange(null) },
                        label = { Text("All") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.mediaType == MetadataLabMediaType.MOVIE,
                        onClick = { onMediaTypeChange(MetadataLabMediaType.MOVIE) },
                        label = { Text("Movies") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.mediaType == MetadataLabMediaType.SERIES,
                        onClick = { onMediaTypeChange(MetadataLabMediaType.SERIES) },
                        label = { Text("Series") }
                    )
                }
            }
        }

        if (uiState.catalogs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.selectedCatalogKeys.size == uiState.catalogs.size,
                            onClick = { onCatalogToggle("__all__") },
                            label = { Text("All") }
                        )
                    }
                    items(items = uiState.catalogs, key = { it.key }) { option ->
                        FilterChip(
                            selected = uiState.selectedCatalogKeys.contains(option.key),
                            onClick = { onCatalogToggle(option.key) },
                            label = {
                                Text(
                                    text = option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }

        if (uiState.isLoadingCatalogs || uiState.isSearching) {
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
