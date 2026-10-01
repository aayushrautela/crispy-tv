package com.crispy.tv.library

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.paging.compose.collectAsLazyPagingItems
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
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
    val pagingItems = viewModel.items.collectAsLazyPagingItems()
    val horizontalPadding = responsivePageHorizontalPadding()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    var selectedLibraryItem by remember { mutableStateOf<CatalogItem?>(null) }
    val librarySheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scrollBehavior = appBarScrollBehavior()

    val sections = uiState.sections
    val selectedSectionId = uiState.selectedSectionId
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId }
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val selectedSectionKey = when (selectedSectionId) {
        LIBRARY_SECTION_HISTORY -> HistoryKey
        LIBRARY_SECTION_RATINGS -> RatingsKey
        else -> WatchlistKey
    }
    val loadedItems = remember(pagingItems.itemCount) {
        (0 until pagingItems.itemCount).mapNotNull { index -> pagingItems[index] }
    }
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
                LibraryFiltersRow(
                    sections = sections,
                    selectedSectionId = selectedSectionId,
                    onSelectSection = viewModel::selectSection,
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

        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator(color = CrispyPalette.spinner)
                }
            }
        } else if (pagingItems.itemCount == 0) {
            item(key = "section-empty") {
                LibraryEmptyState(
                    refreshState = refreshState,
                    selectedSectionLabel = selectedSection?.label,
                    onRefresh = { pagingItems.refresh() },
                )
            }
        } else {
            when (selectedSectionKey) {
                HistoryKey -> historyItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it }, currentMonthKey = currentMonthKey, utcOffsetMillis = utcOffsetMillis(), monthName = monthName)
                RatingsKey -> ratingsItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it })
                WatchlistKey -> watchlistItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it }, currentMonthKey = currentMonthKey, utcOffsetMillis = utcOffsetMillis())
            }

            item(key = "load-more") {
                LibraryAppendState(
                    appendState = appendState,
                    onRetry = { pagingItems.retry() },
                )
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

private object HistoryKey
private object RatingsKey
private object WatchlistKey
