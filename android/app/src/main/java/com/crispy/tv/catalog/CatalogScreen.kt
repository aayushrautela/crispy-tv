package com.crispy.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CatalogRoute(
    section: CatalogSectionRef,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit
) {
    val viewModel: CatalogViewModel = viewModel(
        factory = CatalogViewModel.factory(context = androidx.compose.ui.platform.LocalContext.current, section = section)
    )
    val pagingItems = viewModel.items.collectAsLazyPagingItems()
    val pullToRefreshState = rememberPullToRefreshState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollBehavior = appBarScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            StandardTopAppBar(
                title = section.displayTitle,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = pagingItems.loadState.refresh is LoadState.Loading,
            onRefresh = { pagingItems.refresh() },
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
            indicator = {
                Indicator(
                    state = pullToRefreshState,
                    isRefreshing = pagingItems.loadState.refresh is LoadState.Loading,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = innerPadding.calculateTopPadding()),
                )
            },
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = CardStyle.landscapeCardWidth()),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = pageHorizontalPadding,
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        end = pageHorizontalPadding,
                        bottom = innerPadding.calculateBottomPadding() + 12.dp + safeBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0) {
                        items(CATALOG_SKELETON_COUNT, contentType = { "posterSkeleton" }) {
                            CatalogPosterSkeleton(modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        items(
                            count = pagingItems.itemCount,
                            key = pagingItems.itemKey { "${it.type}:${it.id}" },
                            contentType = { "poster" }
                        ) { index ->
                            val item = pagingItems[index] ?: return@items
                            val sharedElementKey = "catalog-${section.key}-${item.itemId}-${index}"
                            LandscapeCard(
                                title = item.title,
                                artworkUrl = item.artworkUrl,
                                artwork = item.artwork,
                                rating = item.rating,
                                year = item.year,
                                genre = item.genre,
                                onClick = { onItemClick(item, sharedElementKey) },
                                itemId = item.itemId,
                                sharedElementKey = sharedElementKey,
                            )
                        }
                    }
                }

                val refreshState = pagingItems.loadState.refresh
                if (refreshState is LoadState.Error) {
                    Text(
                        text = refreshState.error.message ?: "Failed to load catalog.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogPosterSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(CardStyle.LandscapeAspectRatio)
            .skeletonElement(shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp), pulse = false),
    )
}

private const val CATALOG_SKELETON_COUNT = 12
