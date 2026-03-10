package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.flow.StateFlow

private typealias HomeCatalogSectionStateProvider = (CatalogSectionRef) -> StateFlow<HomeCatalogSectionUi>

@Composable
internal fun HomeRoute(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onProfileClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        },
    )
    val layoutState by viewModel.layoutState.collectAsStateWithLifecycle()

    HomeScreen(
        layoutState = layoutState,
        heroState = viewModel.heroState,
        continueWatchingState = viewModel.continueWatchingState,
        upNextState = viewModel.upNextState,
        thisWeekState = viewModel.thisWeekState,
        catalogSectionState = viewModel::catalogSectionState,
        onHideContinueWatchingItem = viewModel::hideContinueWatchingItem,
        onRemoveContinueWatchingItem = viewModel::removeContinueWatchingItem,
        onHeroClick = onHeroClick,
        onContinueWatchingClick = onContinueWatchingClick,
        onThisWeekClick = onThisWeekClick,
        onThisWeekSeeAllClick = onThisWeekSeeAllClick,
        onProfileClick = onProfileClick,
        onCatalogItemClick = onCatalogItemClick,
        onCatalogSeeAllClick = onCatalogSeeAllClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    layoutState: HomeLayoutState,
    heroState: StateFlow<HeroState>,
    continueWatchingState: StateFlow<HomeWatchActivityRailState>,
    upNextState: StateFlow<HomeWatchActivityRailState>,
    thisWeekState: StateFlow<ThisWeekState>,
    catalogSectionState: HomeCatalogSectionStateProvider,
    onHideContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onProfileClick: () -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
) {
    val horizontalPadding = responsivePageHorizontalPadding()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = {
                    CrispyWordmark(Modifier.height(36.dp))
                },
                actions = {
                    ProfileIconButton(onClick = onProfileClick)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = Dimensions.PageBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing),
        ) {
            if (layoutState.headerSections.isNotEmpty()) {
                item(contentType = "headerSections") {
                    HomeHeaderSectionChips(
                        sections = layoutState.headerSections,
                        onSectionClick = onCatalogSeeAllClick,
                    )
                }
            }

            item(contentType = "hero") {
                HomeHeroSection(
                    heroState = heroState,
                    onHeroClick = onHeroClick,
                )
            }

            if (layoutState.showContinueWatching) {
                item(contentType = "continueWatching") {
                    HomeContinueWatchingSection(
                        continueWatchingState = continueWatchingState,
                        onItemClick = onContinueWatchingClick,
                        onHideItem = onHideContinueWatchingItem,
                        onRemoveItem = onRemoveContinueWatchingItem,
                    )
                }
            }

            if (layoutState.showUpNext) {
                item(contentType = "upNext") {
                    HomeUpNextSection(
                        upNextState = upNextState,
                        onItemClick = onContinueWatchingClick,
                    )
                }
            }

            if (layoutState.showThisWeek) {
                item(contentType = "thisWeek") {
                    HomeThisWeekRail(
                        thisWeekState = thisWeekState,
                        onItemClick = onThisWeekClick,
                        onViewAllClick = onThisWeekSeeAllClick,
                    )
                }
            }

            if (layoutState.collectionSections.isNotEmpty()) {
                item(contentType = "collections") {
                    HomeCollectionSectionRow(
                        sections = layoutState.collectionSections,
                        sectionState = catalogSectionState,
                        onCollectionClick = onCatalogSeeAllClick,
                    )
                }
            }

            if (!layoutState.hasCatalogSections && layoutState.catalogStatusMessage.isNotBlank()) {
                item(contentType = "catalogStatus") {
                    HomeCatalogStatusCard(statusMessage = layoutState.catalogStatusMessage)
                }
            }

            if (layoutState.standardCatalogSections.isNotEmpty()) {
                items(
                    items = layoutState.standardCatalogSections,
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
private fun HomeHeaderSectionChips(
    sections: List<CatalogSectionRef>,
    onSectionClick: (CatalogSectionRef) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sections, key = { it.key }) { section ->
            FilterChip(
                selected = false,
                onClick = { onSectionClick(section) },
                label = { Text(section.title) },
            )
        }
    }
}

// End of file
