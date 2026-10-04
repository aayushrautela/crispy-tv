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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.crispy.tv.accounts.ActiveProfileInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_dice
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource

private val HomeRandomPickButtonSize = 56.dp
private val HomeContentSectionSpacing = 24.dp
private val HomeTopSectionSpacing = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(
    onHeroClick: (HomeHeroItem, String?) -> Unit,
    onContinueWatchingOpenDetails: (CanonicalContinueWatchingItem, String?) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem, String?) -> Unit,
    onThisWeekSeeAllClick: () -> Unit,
    onCatalogItemClick: (CatalogItem, String?) -> Unit,
    /**
     * Navigates to the random-pick wheel.
     *
     * A capability slot rather than a local `remember`ed boolean, for the same reason
     * this file takes every other destination as a slot: the wheel is a nav route
     * (`AppRoutes.RandomWheelRoute`, an `NavigationRole.Overlay` destination like
     * search), so opening it is a navigation decision and belongs to whoever owns the
     * `NavHostController`. Home paints the dice button; `HomeNavGraph` decides where it
     * goes.
     *
     * No default, for the reason every other slot on this signature has none.
     */
    onOpenRandomWheel: () -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    onOpenPlayer: (PlaybackIdentity, Long, String?, String?, String?) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
    /**
     * The two viewmodel factories and the profile loader used to be built here, from
     * `LocalContext.current`.
     *
     * A note in this file argued that was cheaper than threading a slot through each
     * public signature, on the grounds that "these screens are androidMain and already
     * hold a Context". Measured, it cost **one parameter on one signature and one
     * argument at one call site** -- and the sole caller, `HomeNavGraph.kt`, is itself
     * `androidMain` and already holds a `Context`. The note also assumed these screens
     * had to be `androidMain`, which is the assumption being tested.
     *
     * **No-default, because every other slot on this signature has no default** and a
     * defaulted capability is one a call site can silently omit. The factories keep
     * taking a `Context` and stay in `androidMain`: a `Context` used for *wiring*
     * belongs in the factory, so it is the call sites that move, not the factories.
     */
    viewModelFactory: ViewModelProvider.Factory,
    selectorViewModelFactory: ViewModelProvider.Factory,
    loadProfile: suspend () -> ActiveProfileInfo?,
    /**
     * `HomeStreamSelector` used to read `LocalConfiguration.current.screenWidthDp`
     * and apply the `< 600` threshold itself. That local is Android-only, and a
     * `commonMain` composable cannot name it, so the **value** crosses instead and
     * the threshold stays where the platform value is available -- the one
     * `androidMain` file in the chain. See [HomeStreamSelector] for the other side.
     */
    isCompact: Boolean,
) {
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
    val selectorViewModel: HomeSelectorViewModel = viewModel(factory = selectorViewModelFactory)

    LaunchedEffect(selectorViewModel) {
        selectorViewModel.playStream.collect { selection ->
            onOpenPlayer(
                selection.identity,
                selection.resumePositionMs,
                selection.chosenStreamStableKey,
                selection.chosenProviderId,
                selection.chosenStreamHandoffKey,
            )
            selectorViewModel.dismiss()
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val horizontalPadding = responsivePageHorizontalPadding()
    val lazyListState = rememberLazyListState()
    val scrollBehavior = appBarScrollBehavior()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }

    LaunchedEffect(viewModel) {
        viewModel.errorEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onHomeVisible()
        onDispose { viewModel.onHomeHidden() }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        ProfileIconButton(
                            onClick = onOpenAccountsProfiles,
                            // Built here rather than passed in: these three screens are
                            // androidMain, so they already hold a Context, and threading a
                            // slot through each of their public signatures would be churn.
                            loadProfile = loadProfile,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                    colors = topLevelAppBarColors(),
                )
            },
            nestedScrollConnection = scrollBehavior.nestedScrollConnection,
            horizontalPadding = 0.dp,
            snackbarHostState = snackbarHostState,
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
                        }
                    },
                ) { block ->
                    when (block) {
                        is HomeCatalogRowSectionUi -> {
                            val sectionUi = catalogSections[block.sectionKey]
                            if (sectionUi != null) {
                                if (isTop10ListKey(sectionUi.section.kind)) {
                                    HomeTop10SectionRow(
                                        sectionUi = sectionUi,
                                        horizontalPadding = horizontalPadding,
                                        onItemClick = onCatalogItemClick,
                                    )
                                } else {
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
                                )
                            }
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
                                    onContinueWatchingClick = { item, _ -> selectorViewModel.openFor(item) },
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

    HomeStreamSelector(viewModel = selectorViewModel, isCompact = isCompact)

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = Dimensions.PageHorizontalPaddingCompact,
                    bottom = safeBottomPadding(Dimensions.PageBottomPadding),
                ),
        ) {
            HomeRandomPickButton(onClick = onOpenRandomWheel)
        }
    }
}

/**
 * The entry point to the random-pick wheel.
 *
 * A sibling of [CrispyScreen] rather than inside it, because its content slot is a
 * `LazyListScope` -- there is no box to put a floating button in. [safeBottomPadding] already
 * carries the floating nav bar's height and margin, so nothing here restates them.
 */
@Composable
private fun HomeRandomPickButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(HomeRandomPickButtonSize)
            .semantics { contentDescription = "Random pick" },
    ) {
        CrispyIcon(
            painter = painterResource(Res.drawable.ic_dice),
            contentDescription = null,
            modifier = Modifier.size(Dimensions.IconSize),
        )
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
    onHeroClick: (HomeHeroItem, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading && state.items.isEmpty() -> {
            HomeHeroSkeleton(modifier = modifier)
        }

        state.items.isEmpty() -> return

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
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}
