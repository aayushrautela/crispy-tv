package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (ThisWeekItem) -> Unit,
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit
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
    val thisWeekState by viewModel.thisWeekState.collectAsStateWithLifecycle()
    val catalogSectionsState by viewModel.catalogSectionsState.collectAsStateWithLifecycle()
    val headerCatalogSectionsState by viewModel.headerCatalogSectionsState.collectAsStateWithLifecycle()

    val horizontalPadding = responsivePageHorizontalPadding()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val headerSections =
        remember(headerCatalogSectionsState.sections) {
            headerCatalogSectionsState.sections
                .asSequence()
                .filter { it.title.trim().isNotEmpty() }
                .distinctBy { it.key }
                .toList()
        }
    val (collectionSections, standardCatalogSections) =
        remember(catalogSectionsState.sections) {
            catalogSectionsState.sections.partition {
                it.section.catalogId.startsWith(COLLECTION_CATALOG_PREFIX, ignoreCase = true)
            }
        }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                            CrispyWordmark(Modifier.height(36.dp))

                            Box(modifier = Modifier.weight(1f))

                            IconButton(onClick = onSearchClick) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "Search"
                                )
                            }

                            HomeProfileSelector(onClick = onProfileClick)
                        }

                        if (headerSections.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(headerSections, key = { it.key }) { section ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { onCatalogSeeAllClick(section) },
                                        label = { Text(section.title) }
                                    )
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
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
                        HomeHeroSkeleton()
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
                    showTitleFallbackWhenNoLogo = true,
                    useBottomSheetActions = true,
                    isLoading = continueWatchingState.isLoading
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
                    showProgressBar = true,
                    isLoading = upNextState.isLoading
                )
            }

            item(contentType = "thisWeek") {
                ThisWeekSection(
                    items = thisWeekState.items,
                    isLoading = thisWeekState.isLoading,
                    onItemClick = onThisWeekClick,
                )
            }

            if (collectionSections.isNotEmpty()) {
                item(contentType = "collections") {
                    HomeCollectionSectionRow(
                        sections = collectionSections,
                        onCollectionClick = onCatalogSeeAllClick,
                        onItemClick = onCatalogItemClick
                    )
                }
            }

            if (catalogSectionsState.sections.isEmpty() && catalogSectionsState.statusMessage.isNotBlank()) {
                item(contentType = "catalogStatus") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = catalogSectionsState.statusMessage,
                            modifier = Modifier.padding(Dimensions.CardInternalPadding)
                        )
                    }
                }
            }

            if (catalogSectionsState.sections.isNotEmpty()) {
                items(
                    items = standardCatalogSections,
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

private const val COLLECTION_CATALOG_PREFIX = "tmdb.collection."

@Composable
private fun HomeProfileSelector(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = "Profile"
        )
    }
}
