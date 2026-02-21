package com.crispy.rewrite.search

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
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.ui.components.PosterCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchResultsContent(
    uiState: SearchUiState,
    onMediaTypeChange: (MetadataLabMediaType) -> Unit,
    onCatalogToggle: (String) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text(
                    text = "Catalogs",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        if (uiState.catalogsStatusMessage.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = uiState.catalogsStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.statusMessage.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

        if (uiState.query.isBlank() && uiState.results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Type to search and use chips to filter catalogs.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
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
