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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = appBarScrollBehavior()

    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshIfStale()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                uiState = uiState,
                onRefresh = viewModel::refresh,
                onHideContinueWatchingItem = viewModel::hideContinueWatchingItem,
                onRemoveContinueWatchingItem = viewModel::removeContinueWatchingItem,
                onHeroClick = onHeroClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onThisWeekClick = onThisWeekClick,
                onThisWeekSeeAllClick = onThisWeekSeeAllClick,
                onCatalogItemClick = onCatalogItemClick,
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
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    onHideContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
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
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        indicator = {
            Indicator(
                state = pullToRefreshState,
                isRefreshing = uiState.isRefreshing,
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
                        sections = uiState.headerPills,
                        onSectionClick = onCatalogSeeAllClick,
                    )
                    HomeHeroSection(
                        state = uiState.hero,
                        onHeroClick = onHeroClick,
                    )
                }
            }

            if (uiState.sections.isNotEmpty()) {
                items(
                    items = uiState.sections,
                    key = { it.key },
                    contentType = {
                        when (it) {
                            is HomeCatalogRowSectionUi -> "catalogSection"
                            is HomeCollectionShelfSectionUi -> "collectionShelf"
                            is HomeStatusSectionUi -> "catalogStatus"
                            is HomeWideRailSectionUi -> it.kind.name
                        }
                    },
                ) { block ->
                    when (block) {
                        is HomeCatalogRowSectionUi -> {
                            HomeCatalogSectionRow(
                                sectionUi = block.sectionUi,
                                onSeeAllClick = { onCatalogSeeAllClick(block.sectionUi.section) },
                                onItemClick = onCatalogItemClick,
                            )
                        }

                        is HomeCollectionShelfSectionUi -> {
                            HomeCollectionSectionRow(
                                sectionUis = block.sectionUis,
                                onCollectionClick = onCatalogSeeAllClick,
                                onCollectionPlayClick = onCatalogItemClick,
                                onCollectionMovieClick = onCatalogItemClick,
                            )
                        }

                        is HomeStatusSectionUi -> {
                            HomeCatalogStatusCard(statusMessage = block.statusMessage)
                        }

                        is HomeWideRailSectionUi -> {
                            HomeWideRailSection(
                                section = block,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onHideContinueWatchingItem = onHideContinueWatchingItem,
                                onRemoveContinueWatchingItem = onRemoveContinueWatchingItem,
                                onThisWeekClick = onThisWeekClick,
                                onViewAllClick = if (block.kind == HomeWideRailSectionKind.THIS_WEEK) onThisWeekSeeAllClick else null,
                            )
                        }
                    }
                }
            }
        }
}
}

@Composable
private fun HomeHeaderSectionsItem(
    sections: List<CatalogSectionRef>,
    onSectionClick: (CatalogSectionRef) -> Unit,
) {
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
    state: HeroState,
    onHeroClick: (HomeHeroItem) -> Unit,
) {
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
                onItemClick = onHeroClick,
            )
        }
    }
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
                label = { Text(section.displayTitle) },
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
