package com.crispy.rewrite.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.search.SearchResultsContent
import com.crispy.rewrite.search.SearchViewModel
import com.crispy.rewrite.ui.theme.Dimensions
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeScreen(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    onProfileClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        }
    )
    val heroState by viewModel.heroState.collectAsStateWithLifecycle()
    val continueWatchingState by viewModel.continueWatchingState.collectAsStateWithLifecycle()
    val upNextState by viewModel.upNextState.collectAsStateWithLifecycle()
    val catalogSectionsState by viewModel.catalogSectionsState.collectAsStateWithLifecycle()

    val searchViewModel: SearchViewModel = viewModel(
        factory = remember(appContext) {
            SearchViewModel.factory(appContext)
        }
    )
    val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val queryText = textFieldState.text.toString()

    LaunchedEffect(queryText) {
        if (queryText != searchUiState.query) {
            searchViewModel.updateQuery(queryText)
        }
    }

    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed) {
            if (searchUiState.query.isNotBlank()) {
                searchViewModel.clearQuery()
            }
            if (queryText.isNotBlank()) {
                textFieldState.setTextAndPlaceCursorAtEnd("")
            }
        }
    }

    BackHandler(enabled = searchBarState.currentValue == SearchBarValue.Expanded) {
        scope.launch {
            searchBarState.animateToCollapsed()
        }
    }

    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            searchBarState = searchBarState,
            textFieldState = textFieldState,
            onSearch = {
                scope.launch {
                    searchBarState.animateToCollapsed()
                }
            },
            placeholder = {
                Text("Find your next watch")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    if (queryText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                searchViewModel.clearQuery()
                                textFieldState.setTextAndPlaceCursorAtEnd("")
                            }
                        ) {
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

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onProfileClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = "Profile",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
    }

    val horizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppBarWithSearch(
                modifier = Modifier.padding(horizontal = horizontalPadding),
                state = searchBarState,
                inputField = inputField,
                colors = SearchBarDefaults.appBarWithSearchColors(
                    appBarContainerColor = Color.Transparent,
                    scrolledAppBarContainerColor = Color.Transparent
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                contentPadding = PaddingValues(0.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior
            )

            ExpandedFullScreenSearchBar(
                state = searchBarState,
                inputField = inputField
            ) {
                SearchResultsContent(
                    uiState = searchUiState,
                    onMediaTypeChange = searchViewModel::setMediaType,
                    onCatalogToggle = searchViewModel::toggleCatalog,
                    onItemClick = { item ->
                        onCatalogItemClick(item)
                        scope.launch {
                            searchBarState.animateToCollapsed()
                        }
                        searchViewModel.clearQuery()
                        textFieldState.setTextAndPlaceCursorAtEnd("")
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = Dimensions.PageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            item(contentType = "hero") {
                when {
                    heroState.isLoading && heroState.items.isEmpty() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading featured content...")
                            }
                        }
                    }

                    heroState.items.isEmpty() -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = heroState.statusMessage,
                                modifier = Modifier.padding(Dimensions.CardInternalPadding)
                            )
                        }
                    }

                    else -> {
                        HomeHeroCarousel(
                            items = heroState.items,
                            selectedId = heroState.selectedId,
                            onItemClick = onHeroClick
                        )
                    }
                }
            }

            item(contentType = "continueWatching") {
                HomeRailSection(
                    title = "Continue Watching",
                    items = continueWatchingState.items,
                    statusMessage = continueWatchingState.statusMessage,
                    actionMenuContentDescription = "Continue watching actions",
                    subtitleFor = ::continueWatchingSubtitle,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem,
                    showTitleFallbackWhenNoLogo = true
                )
            }

            item(contentType = "upNext") {
                HomeRailSection(
                    title = "Up Next",
                    items = upNextState.items,
                    statusMessage = upNextState.statusMessage,
                    actionMenuContentDescription = "Up next actions",
                    subtitleFor = ::upNextSubtitle,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem,
                    badgeLabel = "UP NEXT",
                    showProgressBar = true
                )
            }

            if (catalogSectionsState.sections.isNotEmpty()) {
                items(
                    items = catalogSectionsState.sections,
                    key = { it.section.key },
                    contentType = { "catalogSection" }
                ) { sectionUi ->
                    HomeCatalogSectionRow(
                        sectionUi = sectionUi,
                        onSeeAllClick = { onCatalogSeeAllClick(sectionUi.section) },
                        onItemClick = onCatalogItemClick
                    )
                }
            }
        }
    }
}
