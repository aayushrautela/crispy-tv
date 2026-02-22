package com.crispy.rewrite.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val query = uiState.query
    var isExpanded by rememberSaveable { mutableStateOf(false) }

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
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = pageHorizontalPadding)
                    .padding(top = 12.dp, bottom = 8.dp),
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = { isExpanded = false },
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = it },
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
                            }
                        }
                    )
                },
                expanded = isExpanded,
                onExpandedChange = { isExpanded = it }
            ) {
                SearchResultsContent(
                    uiState = uiState,
                    onMediaTypeChange = onMediaTypeChange,
                    onCatalogToggle = onCatalogToggle,
                    onItemClick = onItemClick,
                    modifier = Modifier.fillMaxWidth().imePadding()
                )
            }

            if (!isExpanded) {
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
}
