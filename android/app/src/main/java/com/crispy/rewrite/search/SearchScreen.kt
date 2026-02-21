package com.crispy.rewrite.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchRoute(
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val searchViewModel: SearchViewModel = viewModel(
        factory = remember(appContext) {
            SearchViewModel.factory(appContext)
        }
    )
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()

    var expanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(expanded) {
        if (!expanded) {
            searchViewModel.clearQuery()
            onBack()
        }
    }

    BackHandler {
        expanded = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.surface
        ) {
            SearchBar(
                modifier = Modifier.fillMaxSize(),
                windowInsets = WindowInsets(0, 0, 0, 0),
                shape = SearchBarDefaults.fullScreenShape,
                inputField = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SearchBarDefaults.InputField(
                            query = uiState.query,
                            onQueryChange = searchViewModel::updateQuery,
                            onSearch = { },
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            placeholder = { Text("Search movies, series...") },
                            leadingIcon = {
                                IconButton(onClick = { expanded = false }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            },
                            trailingIcon = {
                                Row {
                                    if (uiState.query.isNotBlank()) {
                                        IconButton(onClick = searchViewModel::clearQuery) {
                                            Icon(
                                                imageVector = Icons.Outlined.Clear,
                                                contentDescription = "Clear"
                                            )
                                        }
                                    }
                                    IconButton(onClick = searchViewModel::refreshCatalogs) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "Refresh"
                                        )
                                    }
                                }
                            }
                        )
                    }
                },
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                SearchResultsContent(
                    uiState = uiState,
                    onMediaTypeChange = searchViewModel::setMediaType,
                    onCatalogToggle = searchViewModel::toggleCatalog,
                    onItemClick = onItemClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .imePadding()
                )
            }
        }
    }
}
