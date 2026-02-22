package com.crispy.rewrite.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding

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
        onMediaTypeChange = viewModel::setMediaType,
        onCatalogToggle = viewModel::toggleCatalog,
        onItemClick = onItemClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onMediaTypeChange: (MetadataLabMediaType?) -> Unit,
    onCatalogToggle: (String) -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val query = uiState.query

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding()
                    )
                )
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = pageHorizontalPadding)
                    .padding(top = 12.dp, bottom = 8.dp),
                singleLine = true,
                placeholder = { Text("Search") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = "Clear"
                            )
                        }
                    } else {
                        IconButton(onClick = { }) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "AI Search"
                            )
                        }
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = containerColor,
                        unfocusedContainerColor = containerColor,
                        disabledContainerColor = containerColor,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
            )

            SearchResultsContent(
                uiState = uiState,
                onMediaTypeChange = onMediaTypeChange,
                onCatalogToggle = onCatalogToggle,
                onItemClick = onItemClick,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .imePadding()
            )
        }
    }
}
