package com.crispy.tv.ui.components

// TODO: follow-up pass — the reference app uses a different screen pattern:
// no Scaffold anywhere (except player overlay); top bars are stickyHeader
// blocks inside the LazyListScope that scroll away naturally; bottom padding
// comes from safeBottomPadding(). Our screens use a Material3 collapsing
// TopAppBar (scrollBehavior + nestedScroll) which requires Scaffold, so this
// wrapper keeps that behavior. If we want the reference app's scroll-away
// sticky header behavior, delete this wrapper and bake the LazyColumn +
// PaddingValues directly into each screen (or extract a thinner wrapper
// that takes a LazyListScope + stickyHeader block, no Scaffold).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.theme.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrispyScreen(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    nestedScrollConnection: NestedScrollConnection? = null,
    pullToRefreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    horizontalPadding: Dp = Dimensions.PageHorizontalPaddingCompact,
    topPadding: Dp = Dimensions.PageTopPadding,
    bottomPaddingExtra: Dp = Dimensions.PageBottomPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scaffoldModifier = if (nestedScrollConnection != null) {
        modifier.fillMaxSize().nestedScroll(nestedScrollConnection)
    } else {
        modifier.fillMaxSize()
    }
    Scaffold(
        modifier = scaffoldModifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = topBar ?: {},
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            val listContent: @Composable () -> Unit = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = horizontalPadding,
                        top = topPadding,
                        end = horizontalPadding,
                        bottom = safeBottomPadding(bottomPaddingExtra),
                    ),
                    beyondBoundsPageCount = 1,
                    verticalArrangement = verticalArrangement,
                    content = content,
                )
            }

            if (pullToRefreshState != null && onRefresh != null) {
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    state = pullToRefreshState,
                    indicator = {
                        androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                    listContent()
                }
            } else {
                listContent()
            }
        }
    }
}
