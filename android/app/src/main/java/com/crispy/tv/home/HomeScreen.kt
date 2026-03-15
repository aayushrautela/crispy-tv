package com.crispy.tv.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.StateFlow

private typealias HomeCatalogSectionStateProvider = (CatalogSectionRef) -> StateFlow<HomeCatalogSectionUi>

private val HomeContentSectionSpacing = 24.dp
private val HomeTopSectionSpacing = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCollectionPlayClick: (CatalogItem) -> Unit,
    onCollectionMovieClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        },
    )
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val standardCatalogSections by viewModel.standardCatalogSections.collectAsStateWithLifecycle()
    val catalogSectionState = remember(viewModel) { viewModel::catalogSectionState }
    val scrollBehavior = appBarScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            StandardTopAppBar(
                title = {
                    CrispyWordmark(
                        modifier = Modifier
                            .width(118.dp)
                            .height(26.dp),
                    )
                },
                actions = {
                    ProfileIconButton(onClick = onOpenAccountsProfiles)
                },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            HomeScreen(
                isRefreshing = isRefreshing,
                headerSectionsState = viewModel.headerSections,
                collectionSectionUisState = viewModel.collectionSectionUis,
                standardCatalogSections = standardCatalogSections,
                catalogStatusState = viewModel.catalogStatusState,
                heroState = viewModel.heroState,
                continueWatchingState = viewModel.continueWatchingState,
                upNextState = viewModel.upNextState,
                thisWeekState = viewModel.thisWeekState,
                catalogSectionState = catalogSectionState,
                onRefresh = viewModel::refresh,
                onHideContinueWatchingItem = viewModel::hideContinueWatchingItem,
                onRemoveContinueWatchingItem = viewModel::removeContinueWatchingItem,
                onHeroClick = onHeroClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onThisWeekClick = onThisWeekClick,
                onThisWeekSeeAllClick = onThisWeekSeeAllClick,
                onCatalogItemClick = onCatalogItemClick,
                onCollectionPlayClick = onCollectionPlayClick,
                onCollectionMovieClick = onCollectionMovieClick,
                onCatalogSeeAllClick = onCatalogSeeAllClick,
                scrollToTopRequests = scrollToTopRequests,
                onScrollToTopConsumed = onScrollToTopConsumed,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeScreen(
    isRefreshing: Boolean,
    headerSectionsState: StateFlow<List<CatalogSectionRef>>,
    collectionSectionUisState: StateFlow<List<HomeCatalogSectionUi>>,
    standardCatalogSections: List<CatalogSectionRef>,
    catalogStatusState: StateFlow<HomeCatalogStatusState>,
    heroState: StateFlow<HeroState>,
    continueWatchingState: StateFlow<HomeWatchActivityRailState>,
    upNextState: StateFlow<HomeWatchActivityRailState>,
    thisWeekState: StateFlow<ThisWeekState>,
    catalogSectionState: HomeCatalogSectionStateProvider,
    onRefresh: () -> Unit,
    onHideContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCollectionPlayClick: (CatalogItem) -> Unit,
    onCollectionMovieClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val horizontalPadding = responsivePageHorizontalPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val lazyListState = rememberLazyListState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            lazyListState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        indicator = {
            Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = Dimensions.PageTopPadding,
                end = horizontalPadding,
                bottom = Dimensions.PageBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(HomeContentSectionSpacing),
        ) {
                item(key = "topHeader", contentType = "topHeader") {
                    Column(verticalArrangement = Arrangement.spacedBy(HomeTopSectionSpacing)) {
                        HomeHeaderSectionsItem(
                            headerSectionsState = headerSectionsState,
                            onSectionClick = onCatalogSeeAllClick,
                        )
                        HomeHeroSection(
                            heroState = heroState,
                            onHeroClick = onHeroClick,
                        )
                    }
                }

                item(key = "continueWatching", contentType = "continueWatching") {
                    HomeContinueWatchingSection(
                        continueWatchingState = continueWatchingState,
                        onItemClick = onContinueWatchingClick,
                        onHideItem = onHideContinueWatchingItem,
                        onRemoveItem = onRemoveContinueWatchingItem,
                    )
                }

                item(key = "upNext", contentType = "upNext") {
                    HomeUpNextSection(
                        upNextState = upNextState,
                        onItemClick = onContinueWatchingClick,
                    )
                }

                item(key = "thisWeek", contentType = "thisWeek") {
                    HomeThisWeekRail(
                        thisWeekState = thisWeekState,
                        onItemClick = onThisWeekClick,
                        onViewAllClick = onThisWeekSeeAllClick,
                    )
                }

                item(key = "collections", contentType = "collections") {
                    HomeCollectionSectionsItem(
                        collectionSectionUisState = collectionSectionUisState,
                        onCollectionClick = onCatalogSeeAllClick,
                        onCollectionPlayClick = onCollectionPlayClick,
                        onCollectionMovieClick = onCollectionMovieClick,
                    )
                }

                item(key = "catalogStatus", contentType = "catalogStatus") {
                    HomeCatalogStatusItem(catalogStatusState = catalogStatusState)
                }

                if (standardCatalogSections.isNotEmpty()) {
                    items(
                        items = standardCatalogSections,
                        key = { it.key },
                        contentType = { "catalogSection" },
                    ) { section ->
                        HomeCatalogSectionItem(
                            sectionState = catalogSectionState(section),
                            onSeeAllClick = onCatalogSeeAllClick,
                            onItemClick = onCatalogItemClick,
                        )
                    }
                }
            }
        }
}

@Composable
private fun HomeHeaderSectionsItem(
    headerSectionsState: StateFlow<List<CatalogSectionRef>>,
    onSectionClick: (CatalogSectionRef) -> Unit,
) {
    val sections by headerSectionsState.collectAsStateWithLifecycle()
    if (sections.isEmpty()) {
        return
    }
    HomeHeaderSectionChips(
        sections = sections,
        onSectionClick = onSectionClick,
    )
}

@Composable
private fun HomeHeroSection(
    heroState: StateFlow<HeroState>,
    onHeroClick: (HomeHeroItem) -> Unit,
) {
    val state by heroState.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.items.isEmpty() -> {
            HomeHeroSkeleton()
        }

        state.items.isEmpty() -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.statusMessage,
                    modifier = Modifier.padding(Dimensions.CardInternalPadding),
                )
            }
        }

        else -> {
            HomeHeroCarousel(
                items = state.items,
                selectedId = state.selectedId,
                onItemClick = onHeroClick,
            )
        }
    }
}

@Composable
private fun HomeContinueWatchingSection(
    continueWatchingState: StateFlow<HomeWatchActivityRailState>,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onHideItem: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
) {
    val state by continueWatchingState.collectAsStateWithLifecycle()

    HomeRailSection(
        title = "Continue Watching",
        items = state.items,
        statusMessage = state.statusMessage,
        actionMenuContentDescription = "Continue watching actions",
        onItemClick = onItemClick,
        onHideItem = onHideItem,
        onRemoveItem = onRemoveItem,
        showProgressBarFor = { item -> item.progressPercent > 0 },
        showTitleFallbackWhenNoLogo = true,
        useBottomSheetActions = true,
        isLoading = state.isLoading,
    )
}

@Composable
private fun HomeUpNextSection(
    upNextState: StateFlow<HomeWatchActivityRailState>,
    onItemClick: (ContinueWatchingItem) -> Unit,
) {
    val state by upNextState.collectAsStateWithLifecycle()

    HomeRailSection(
        title = "Up Next",
        items = state.items,
        statusMessage = state.statusMessage,
        actionMenuContentDescription = "Up next details",
        onItemClick = onItemClick,
        showTitleFallbackWhenNoLogo = true,
        isLoading = state.isLoading,
    )
}

@Composable
private fun HomeThisWeekRail(
    thisWeekState: StateFlow<ThisWeekState>,
    onItemClick: (CalendarEpisodeItem) -> Unit,
    onViewAllClick: () -> Unit,
) {
    val state by thisWeekState.collectAsStateWithLifecycle()

    ThisWeekSection(
        items = state.items,
        isLoading = state.isLoading,
        statusMessage = state.statusMessage,
        onItemClick = onItemClick,
        onViewAllClick = onViewAllClick,
    )
}

@Composable
private fun HomeCatalogStatusCard(statusMessage: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = statusMessage,
            modifier = Modifier.padding(Dimensions.CardInternalPadding),
        )
    }
}

@Composable
private fun HomeCatalogStatusItem(catalogStatusState: StateFlow<HomeCatalogStatusState>) {
    val state by catalogStatusState.collectAsStateWithLifecycle()
    if (state.hasCatalogSections || state.statusMessage.isBlank()) {
        return
    }
    HomeCatalogStatusCard(statusMessage = state.statusMessage)
}

@Composable
private fun HomeCatalogSectionItem(
    sectionState: StateFlow<HomeCatalogSectionUi>,
    onSeeAllClick: (CatalogSectionRef) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
) {
    val sectionUi by sectionState.collectAsStateWithLifecycle()

    HomeCatalogSectionRow(
        sectionUi = sectionUi,
        onSeeAllClick = { onSeeAllClick(sectionUi.section) },
        onItemClick = onItemClick,
    )
}

@Composable
private fun HomeCollectionSectionsItem(
    collectionSectionUisState: StateFlow<List<HomeCatalogSectionUi>>,
    onCollectionClick: (CatalogSectionRef) -> Unit,
    onCollectionPlayClick: (CatalogItem) -> Unit,
    onCollectionMovieClick: (CatalogItem) -> Unit,
) {
    val sectionUis by collectionSectionUisState.collectAsStateWithLifecycle()
    HomeCollectionSectionRow(
        sectionUis = sectionUis,
        onCollectionClick = onCollectionClick,
        onCollectionPlayClick = onCollectionPlayClick,
        onCollectionMovieClick = onCollectionMovieClick,
    )
}

@Composable
private fun HomeHeaderSectionChips(
    sections: List<CatalogSectionRef>,
    onSectionClick: (CatalogSectionRef) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(sections, key = { it.key }) { section ->
            FilterChip(
                selected = false,
                onClick = { onSectionClick(section) },
                label = { Text(section.title) },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

// End of file
