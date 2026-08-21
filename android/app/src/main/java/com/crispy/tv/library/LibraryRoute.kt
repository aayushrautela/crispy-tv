package com.crispy.tv.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.ItemActionSheet
import com.crispy.tv.ui.components.ItemActionSheetItem
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryRoute(
    onItemClick: (CatalogItem, String?) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: LibraryViewModel =
        viewModel(
            factory = remember(appContext) { LibraryViewModel.factory(appContext) },
        )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val pagingItems = viewModel.items.collectAsLazyPagingItems()
    val horizontalPadding = responsivePageHorizontalPadding()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    var selectedLibraryItem by remember { mutableStateOf<CatalogItem?>(null) }
    val librarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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

    Box(modifier = Modifier.fillMaxSize()) {
        CrispyScreen(
        topBar = {
            StandardTopAppBar(
                title = { CrispySectionAppBarTitle(label = "Library") },
                actions = {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Outlined.Event, contentDescription = "Calendar")
                    }
                    ProfileIconButton(onClick = onOpenAccountsProfiles)
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
                    LoadingIndicator()
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
                HistoryKey -> historyItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it })
                RatingsKey -> ratingsItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it })
                WatchlistKey -> watchlistItems(loadedItems, horizontalPadding, onItemClick, onItemLongPress = { selectedLibraryItem = it })
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
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            selectedLibraryItem = null
                            onItemClick(item, null)
                        },
                    ),
                )
                add(
                    ItemActionSheetItem(
                        label = if (watched) "Mark as unwatched" else "Mark as watched",
                        icon = if (watched) Icons.Filled.Check else Icons.Outlined.Check,
                        filled = watched,
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
                    subtitle = item.episodeCount?.takeIf { it > 1 }?.let { "$it episodes" },
                    imageUrl = item.posterUrl ?: item.backdropUrl,
                    actions = actions,
                )
            }
        }
    }
}

private object HistoryKey
private object RatingsKey
private object WatchlistKey
