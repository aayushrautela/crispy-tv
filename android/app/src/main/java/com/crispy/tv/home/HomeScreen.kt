package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.StateFlow

private val HomeContentSectionSpacing = 24.dp
private val HomeTopSectionSpacing = 16.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeRoute(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (CanonicalContinueWatchingItem) -> Unit,
    onContinueWatchingOpenDetails: (CanonicalContinueWatchingItem) -> Unit,
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
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val horizontalPadding = responsivePageHorizontalPadding()
    val lazyListState = rememberLazyListState()
    val scrollBehavior = appBarScrollBehavior()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            lazyListState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    val headerPills = uiState.headerPills
    val heroState = uiState.heroState
    val layoutState = uiState.layoutState
    val wideRailSections = uiState.wideRailSections
    val catalogSections = uiState.catalogSections

    CrispyScreen(
        topBar = {
            StandardTopAppBar(
                title = {
                    CrispyWordmark(
                        modifier = Modifier
                            .width(164.dp)
                            .height(36.dp),
                    )
                },
                actions = {
                    ProfileIconButton(onClick = onOpenAccountsProfiles)
                },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
        nestedScrollConnection = scrollBehavior.nestedScrollConnection,
        horizontalPadding = 0.dp,
        topPadding = 0.dp,
        bottomPaddingExtra = Dimensions.PageBottomPadding,
        verticalArrangement = Arrangement.spacedBy(HomeContentSectionSpacing),
        listState = lazyListState,
    ) {
        item(key = "topHeader", contentType = "topHeader") {
            Column(verticalArrangement = Arrangement.spacedBy(HomeTopSectionSpacing)) {
                Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                    HomeHeaderSectionsItem(
                        sections = headerPills,
                        onSectionClick = onCatalogSeeAllClick,
                    )
                }
                HomeHeroSection(
                    state = heroState,
                    onHeroClick = onHeroClick,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            }
        }

        if (layoutState.blocks.isNotEmpty()) {
            items(
                items = layoutState.blocks,
                key = { it.key },
                contentType = {
                    when (it) {
                        is HomeWideRailLayoutUi -> it.kind.name
                        is HomeCatalogRowSectionUi -> "catalogSection"
                        is HomeCollectionShelfSectionUi -> "collectionShelf"
                        is HomeStatusSectionUi -> "catalogStatus"
                    }
                },
            ) { block ->
                when (block) {
                    is HomeCatalogRowSectionUi -> {
                        val sectionUi = catalogSections[block.sectionKey]
                        if (sectionUi != null) {
                            val onSeeAll = remember(sectionUi.section) {
                                { onCatalogSeeAllClick(sectionUi.section) }
                            }
                            HomeCatalogSectionRow(
                                sectionUi = sectionUi,
                                horizontalPadding = horizontalPadding,
                                onSeeAllClick = onSeeAll,
                                onItemClick = onCatalogItemClick,
                            )
                        }
                    }

                    is HomeCollectionShelfSectionUi -> {
                        val sectionUis = remember(block.sectionKeys, catalogSections) {
                            block.sectionKeys.mapNotNull(catalogSections::get)
                        }
                        if (sectionUis.isNotEmpty()) {
                            HomeCollectionSectionRow(
                                sectionUis = sectionUis,
                                horizontalPadding = horizontalPadding,
                                onCollectionClick = onCatalogSeeAllClick,
                                onCollectionPlayClick = onCatalogItemClick,
                                onCollectionMovieClick = onCatalogItemClick,
                            )
                        }
                    }

                    is HomeStatusSectionUi -> {
                        HomeCatalogStatusCard(statusMessage = block.statusMessage)
                    }

                    is HomeWideRailLayoutUi -> {
                        val section = wideRailSections[block.key]
                        if (section != null) {
                            val onViewAll = remember(block.kind) {
                                if (block.kind == HomeWideRailSectionKind.THIS_WEEK) onThisWeekSeeAllClick else null
                            }
                            HomeWideRailSection(
                                section = section,
                                horizontalPadding = horizontalPadding,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingOpenDetails = onContinueWatchingOpenDetails,
                                onRemoveContinueWatchingItem = viewModel::removeContinueWatchingItem,
                                onThisWeekClick = onThisWeekClick,
                                onViewAllClick = onViewAll,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            List(4) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(32.dp)
                        .skeletonElement(shape = RoundedCornerShape(16.dp), pulse = false),
                )
            }
        }
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
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading && state.items.isEmpty() -> {
            HomeHeroSkeleton(modifier = modifier)
        }

        state.items.isEmpty() -> {
            Card(modifier = modifier.fillMaxWidth()) {
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
                modifier = modifier,
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
        items(sections, key = { it.key }, contentType = { "headerPill" }) { section ->
            FilterChip(
                selected = false,
                onClick = { onSectionClick(section) },
                label = { Text(section.displayTitle) },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}
