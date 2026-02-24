package com.crispy.tv.ui.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackLabUiState
import com.crispy.tv.player.PlaybackLabViewModel

@Composable
internal fun CatalogLabSection(
    uiState: PlaybackLabUiState,
    viewModel: PlaybackLabViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Catalog + Search Lab", style = MaterialTheme.typography.titleMedium)
            Text(
                "Registry-ordered addon catalogs with Nuvio URL fallback; search uses searchable catalogs only.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("catalog_movie_button"),
                    enabled = uiState.catalogMediaType != MetadataLabMediaType.MOVIE,
                    onClick = {
                        viewModel.onCatalogMediaTypeSelected(MetadataLabMediaType.MOVIE)
                    }
                ) {
                    Text("Movie")
                }

                Button(
                    modifier = Modifier.testTag("catalog_series_button"),
                    enabled = uiState.catalogMediaType != MetadataLabMediaType.SERIES,
                    onClick = {
                        viewModel.onCatalogMediaTypeSelected(MetadataLabMediaType.SERIES)
                    }
                ) {
                    Text("Series")
                }
            }

            OutlinedTextField(
                value = uiState.catalogInputId,
                onValueChange = viewModel::onCatalogIdChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("catalog_id_input"),
                label = { Text("Catalog ID (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Text(
                "Tip: leave Catalog ID empty and tap Load Catalog to list available addon catalogs.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = uiState.catalogSearchQuery,
                onValueChange = viewModel::onCatalogSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("catalog_search_input"),
                label = { Text("Search query") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = uiState.catalogPreferredAddonId,
                onValueChange = viewModel::onCatalogPreferredAddonChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("catalog_preferred_addon_input"),
                label = { Text("Preferred addon (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("catalog_load_button"),
                    enabled = !uiState.isLoadingCatalog,
                    onClick = {
                        viewModel.onLoadCatalogRequested()
                    }
                ) {
                    Text(if (uiState.isLoadingCatalog) "Loading..." else "Load Catalog")
                }

                Button(
                    modifier = Modifier.testTag("catalog_search_button"),
                    enabled = !uiState.isLoadingCatalog,
                    onClick = {
                        viewModel.onSearchCatalogRequested()
                    }
                ) {
                    Text(if (uiState.isLoadingCatalog) "Searching..." else "Search")
                }
            }

            Text(
                modifier = Modifier.testTag("catalog_status_text"),
                text = uiState.catalogStatusMessage,
                style = MaterialTheme.typography.bodySmall
            )

            val catalogsText =
                if (uiState.catalogAvailableCatalogs.isEmpty()) {
                    "Catalogs: none"
                } else {
                    "Catalogs: " + uiState.catalogAvailableCatalogs.take(6).joinToString { catalog -> catalog.name }
                }
            Text(
                modifier = Modifier.testTag("catalog_catalogs_text"),
                text = catalogsText,
                style = MaterialTheme.typography.bodySmall
            )

            val itemsText =
                if (uiState.catalogItems.isEmpty()) {
                    "Results: none"
                } else {
                    "Results: " + uiState.catalogItems.take(5).joinToString { item -> item.title }
                }
            Text(
                modifier = Modifier.testTag("catalog_results_text"),
                text = itemsText,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
