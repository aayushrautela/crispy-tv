package com.crispy.tv.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.library.currentMonthKeyOf
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.CrispySegmentedButton
import com.crispy.tv.ui.components.CrispySegmentedButtonRow
import com.crispy.tv.ui.components.ItemActionSheet
import com.crispy.tv.ui.components.ItemActionSheetItem
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_check
import com.crispy.tv.ui.resources.ic_check_filled
import com.crispy.tv.ui.resources.ic_event
import com.crispy.tv.ui.resources.ic_open_in_new_filled
import com.crispy.tv.ui.theme.CrispyPalette
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryRoute(
    // A `Context` cannot be named in a `commonMain` signature, and this route is
    // `commonMain` now -- so the three platform values it used to read for itself
    // cross as required parameters. `viewModelFactory` and `monthName` were already
    // slots before this file moved; the other three are new, and the same shape as
    // the auth, search and settings routes.
    //
    // `loadProfile` stays a **lambda**, unlike the factories: `ProfileIconButton`
    // takes it as a `produceState(initialValue = null, loadProfile, refreshKey)` key,
    // so a fresh instance each recomposition would restart the profile load. Its own
    // KDoc says the same thing, and the caller `remember`s it. `clock` and
    // `utcOffsetMillis` are lambdas for their own reasons -- `deviceUtcOffsetMillis()`
    // is deliberately read fresh per call, because an offset remembered at composition
    // time is wrong for the hours either side of a daylight-saving change, and this
    // screen groups rows by month.
    viewModelFactory: ViewModelProvider.Factory,
    monthName: (String) -> String,
    clock: () -> Long,
    utcOffsetMillis: () -> Long,
    loadProfile: suspend () -> ActiveProfileInfo?,
    onItemClick: (CatalogItem, String?) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = viewModelFactory)
    val currentMonthKey = currentMonthKeyOf(clock, utcOffsetMillis())
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val horizontalPadding = responsivePageHorizontalPadding()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    var selectedLibraryItem by remember { mutableStateOf<CatalogItem?>(null) }
    val librarySheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scrollBehavior = appBarScrollBehavior()

    val sections = uiState.sections
    val selectedSectionId = uiState.selectedSectionId
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId }

    // Sections the user has already opened keep their collected pager, so a
    // switch-back shows the retained list instead of refetching. The selected
    // section is always collected even before the effect below records it, so
    // there is no frame where the animation target has no data.
    var visitedSectionIds by remember { mutableStateOf(setOf(selectedSectionId)) }
    val collectedSectionIds = visitedSectionIds + selectedSectionId

    LaunchedEffect(selectedSectionId) {
        if (selectedSectionId !in visitedSectionIds) visitedSectionIds += selectedSectionId
        listState.scrollToItem(0)
    }

    // One retained pager per collected section. Each `key` group appears once
    // and never moves, so a group composed later takes fresh slots rather than
    // shifting its siblings'.
    val sectionItems = mutableMapOf<String, LazyPagingItems<CatalogItem>>()
    val sectionLoadedItems = mutableMapOf<String, List<CatalogItem>>()
    for (section in sections) {
        if (section.id in collectedSectionIds) {
            key(section.id) {
                val sectionFlow = remember(viewModel, section.id) { viewModel.itemsFor(section.id) }
                val items = sectionFlow.collectAsLazyPagingItems()
                sectionItems[section.id] = items
                sectionLoadedItems[section.id] =
                    remember(items.itemCount) {
                        (0 until items.itemCount).mapNotNull { index -> items[index] }
                    }
            }
        }
    }
    val pagingItems =
        checkNotNull(sectionItems[selectedSectionId]) {
            "Selected library section $selectedSectionId was never collected"
        }
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            listState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    // The three values below used to be read here: `LocalContext.current` for the
    // profile loader, a `System.currentTimeMillis()` default, and the same-package
    // `deviceUtcOffsetMillis()` from `LibraryViewModelFactory.kt`. All three crossed
    // as required parameters instead. The comment that used to sit here argued the
    // opposite -- "these screens are androidMain and already hold a Context, so
    // building the loader here is cheaper than threading a slot through each public
    // signature" -- and the reason it preferred that was the pin: the screens being
    // `androidMain` is what the slot removes, so the comment argued for its own
    // cause.
    Box(modifier = Modifier.fillMaxSize()) {
        CrispyScreen(
        topBar = {
            StandardTopAppBar(
                title = { CrispySectionAppBarTitle(label = "Library") },
                actions = {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(painter = painterResource(Res.drawable.ic_event), contentDescription = "Calendar")
                    }
ProfileIconButton(
                        onClick = onOpenAccountsProfiles,
                    loadProfile = loadProfile,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
        nestedScrollConnection = scrollBehavior.nestedScrollConnection,
        pullToRefreshState = pullToRefreshState,
        isRefreshing = refreshState is LoadState.Loading && pagingItems.itemCount > 0,
        onRefresh = { pagingItems.refresh() },
        horizontalPadding = 0.dp,
        topPadding = 0.dp,
        bottomPaddingExtra = 12.dp,
        listState = listState,
    ) {
        item(key = "filters") {
            Box(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                CrispySegmentedButtonRow(
                    // Remapped rather than passed straight through: the shared row's option is its
                    // own type, and its `icon` is nullable because the random-pick page's genre
                    // glyphs are a different lookup from this page's three fixed sections.
                    options = remember(sections) {
                        sections.map { section ->
                            CrispySegmentedButton(id = section.id, label = section.label, icon = section.icon)
                        }
                    },
                    selectedId = selectedSectionId,
                    onSelect = viewModel::selectSection,
                )
            }
        }

        item(key = "status") {
            LibraryStatusMessage(
                refreshState = refreshState,
                appendState = appendState,
                hasItems = pagingItems.itemCount > 0,
                selectedSectionLabel = selectedSection?.label,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
        }

        item(key = "content") {
            // The tab switch animation. `AnimatedContent` keeps the outgoing
            // section composed while the incoming one loads, which is exactly
            // what the retained per-section pagers are for: without them the
            // outgoing side would already be an empty spinner. The directional
            // spec mirrors Koda's tab animation -- the incoming section slides
            // in full-width from the side being moved towards while the
            // outgoing fades sliding a third out.
            AnimatedContent(
                targetState = selectedSectionId,
                label = "library-section",
                transitionSpec = {
                    val orderedIds = sections.map { it.id }
                    val fromIndex = orderedIds.indexOf(initialState).coerceAtLeast(0)
                    val toIndex = orderedIds.indexOf(targetState).coerceAtLeast(0)
                    val sign = if (toIndex >= fromIndex) 1 else -1
                    (slideInHorizontally { sign * it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -sign * (it / 3) } + fadeOut())
                },
            ) { animatedSectionId ->
                val animatedItems =
                    checkNotNull(sectionItems[animatedSectionId]) {
                        "Animated library section $animatedSectionId was never collected"
                    }
                val animatedLoaded =
                    checkNotNull(sectionLoadedItems[animatedSectionId]) {
                        "Animated library section $animatedSectionId has no loaded snapshot"
                    }
                val animatedRefresh = animatedItems.loadState.refresh
                val animatedAppend = animatedItems.loadState.append
                val animatedLabel = sections.firstOrNull { it.id == animatedSectionId }?.label
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (animatedRefresh is LoadState.Loading && animatedItems.itemCount == 0) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator(color = CrispyPalette.spinner)
                        }
                    } else if (animatedItems.itemCount == 0) {
                        LibraryEmptyState(
                            refreshState = animatedRefresh,
                            selectedSectionLabel = animatedLabel,
                            onRefresh = { animatedItems.refresh() },
                        )
                    } else {
                        when (animatedSectionId) {
                            LIBRARY_SECTION_HISTORY ->
                                HistorySectionContent(
                                    loadedItems = animatedLoaded,
                                    pageHorizontalPadding = horizontalPadding,
                                    onItemClick = onItemClick,
                                    onItemLongPress = { selectedLibraryItem = it },
                                    currentMonthKey = currentMonthKey,
                                    utcOffsetMillis = utcOffsetMillis(),
                                    monthName = monthName,
                                )

                            LIBRARY_SECTION_RATINGS ->
                                RatingsSectionContent(
                                    loadedItems = animatedLoaded,
                                    pageHorizontalPadding = horizontalPadding,
                                    onItemClick = onItemClick,
                                    onItemLongPress = { selectedLibraryItem = it },
                                )

                            else ->
                                WatchlistSectionContent(
                                    loadedItems = animatedLoaded,
                                    pageHorizontalPadding = horizontalPadding,
                                    onItemClick = onItemClick,
                                    onItemLongPress = { selectedLibraryItem = it },
                                    currentMonthKey = currentMonthKey,
                                    utcOffsetMillis = utcOffsetMillis(),
                                )
                        }

                        LibraryAppendState(
                            appendState = animatedAppend,
                            onRetry = { animatedItems.retry() },
                        )
                    }
                }
            }
        }
        }
        if (selectedLibraryItem != null) {
            val item = selectedLibraryItem!!
            val watched = item.watchedAt != null
            val actions = buildList {
                add(
                    ItemActionSheetItem(
                        label = "Open details",
                        icon = Res.drawable.ic_open_in_new_filled,
                        autoMirror = true,
                        onClick = {
                            selectedLibraryItem = null
                            onItemClick(item, null)
                        },
                    ),
                )
                add(
                    ItemActionSheetItem(
                        label = if (watched) "Mark as unwatched" else "Mark as watched",
                        icon = if (watched) Res.drawable.ic_check_filled else Res.drawable.ic_check,
                        filled = watched,
                        dividerBefore = true,
                        onClick = {
                            selectedLibraryItem = null
                            viewModel.setWatched(item, !watched)
                            pagingItems.refresh()
                        },
                    ),
                )
            }
            ModalBottomSheet(
                onDismissRequest = { selectedLibraryItem = null },
                sheetState = librarySheetState,
            ) {
                ItemActionSheet(
                    title = item.title,
                    subtitle = listOfNotNull(
                        item.year?.takeIf { it.isNotBlank() },
                        item.genre?.takeIf { it.isNotBlank() },
                        item.episodeCount?.takeIf { it > 1 }?.let { "$it episodes" },
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                    imageUrl = item.artworkUrl,
                    actions = actions,
                )
            }
        }
    }
}
