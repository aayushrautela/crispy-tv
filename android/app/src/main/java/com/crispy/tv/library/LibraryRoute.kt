package com.crispy.tv.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.player.WatchHistoryEntry
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LibraryRoute(
    onItemClick: (WatchHistoryEntry) -> Unit,
    onNavigateToDiscover: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: LibraryViewModel =
        viewModel(
            factory = remember(appContext) {
                LibraryViewModel.factory(appContext)
            },
        )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    LibraryRouteContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onItemClick = onItemClick,
        onNavigateToDiscover = onNavigateToDiscover,
        onSelectProviderFolder = viewModel::selectProviderFolder,
        scrollToTopRequests = scrollToTopRequests,
        onScrollToTopConsumed = onScrollToTopConsumed,
    )
}
