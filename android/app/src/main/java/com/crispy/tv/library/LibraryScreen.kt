package com.crispy.tv.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private const val LIBRARY_PAGE_SIZE = 60
internal const val LIBRARY_SECTION_HISTORY = "history"
internal const val LIBRARY_SECTION_WATCHLIST = "watchlist"
internal const val LIBRARY_SECTION_RATINGS = "ratings"

private val LIBRARY_SECTIONS =
    listOf(
        LibrarySectionUi(id = LIBRARY_SECTION_HISTORY, label = "History"),
        LibrarySectionUi(id = LIBRARY_SECTION_WATCHLIST, label = "Watchlist"),
        LibrarySectionUi(id = LIBRARY_SECTION_RATINGS, label = "Ratings"),
    )

@Immutable
data class LibrarySectionItemUi(
    val id: String,
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addedAt: String?,
    val watchedAt: String?,
    val ratedAt: String?,
    val rating: Int?,
    val lastActivityAt: String?,
    val origins: List<String>,
) {
    val stableKey: String
        get() = id
}

@Immutable
data class LibrarySectionUi(
    val id: String,
    val label: String,
)

@Immutable
data class LibraryUiState(
    val sections: List<LibrarySectionUi> = LIBRARY_SECTIONS,
    val selectedSectionId: String = LIBRARY_SECTIONS.first().id,
)

class LibraryViewModel internal constructor(
    private val backend: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    val items: Flow<PagingData<LibrarySectionItemUi>> =
        _uiState
            .map { it.selectedSectionId }
            .distinctUntilChanged()
            .flatMapLatest { sectionId ->
                Pager(
                    config =
                        PagingConfig(
                            pageSize = LIBRARY_PAGE_SIZE,
                            initialLoadSize = LIBRARY_PAGE_SIZE,
                            prefetchDistance = 10,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = {
                        LibraryPagingSource(
                            backend = backend,
                            backendContextResolver = backendContextResolver,
                            sectionId = sectionId,
                        )
                    },
                ).flow
            }.cachedIn(viewModelScope)

    fun selectSection(sectionId: String) {
        val normalized = sectionId.trim()
        if (normalized.isEmpty()) return
        val current = _uiState.value
        if (current.selectedSectionId == normalized || current.sections.none { it.id == normalized }) return

        _uiState.update {
            it.copy(
                selectedSectionId = normalized,
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LibraryViewModel(
                            backend = BackendServicesProvider.backendClient(appContext),
                            backendContextResolver = BackendContextResolverProvider.get(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryRouteContent(
    uiState: LibraryUiState,
    pagingItems: LazyPagingItems<LibrarySectionItemUi>,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val sections = uiState.sections
    val selectedSectionId = uiState.selectedSectionId
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId }
    val pullToRefreshState = rememberPullToRefreshState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val gridState = rememberLazyGridState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val isRefreshing = refreshState is LoadState.Loading && pagingItems.itemCount > 0

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            gridState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        indicator = {
            Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                top = 12.dp,
                end = pageHorizontalPadding,
                bottom = 12.dp + Dimensions.PageBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                if (sections.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sections, key = { it.id }) { section ->
                            FilterChip(
                                selected = section.id == selectedSectionId,
                                onClick = { onSelectSection(section.id) },
                                label = { Text(section.label) },
                            )
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                val message =
                    when {
                        refreshState is LoadState.Error && pagingItems.itemCount > 0 -> {
                            refreshState.error.message ?: "Failed to refresh ${selectedSection?.label ?: "this section"}."
                        }

                        appendState is LoadState.Error -> {
                            appendState.error.message ?: "Failed to load more items."
                        }

                        else -> ""
                    }
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
                items(LIBRARY_SKELETON_COUNT, span = { GridItemSpan(1) }, key = { index -> "library-skeleton-$index" }) {
                    LibraryPosterSkeleton(modifier = Modifier.fillMaxWidth())
                }
            } else if (pagingItems.itemCount == 0) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-empty") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Dimensions.ListItemPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text =
                                    if (refreshState is LoadState.Error) {
                                        refreshState.error.message ?: "Failed to load ${selectedSection?.label ?: "this section"}."
                                    } else {
                                        "No items in ${selectedSection?.label ?: "this section"} yet."
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (refreshState is LoadState.Error) {
                                FilledTonalButton(onClick = onRefresh) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            } else {
                items(
                    count = pagingItems.itemCount,
                    key = pagingItems.itemKey { it.stableKey },
                ) { index ->
                    val item = pagingItems[index] ?: return@items
                    PosterCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = null,
                        year = null,
                        genre = null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onItemClick(item) },
                    )
                }

                if (appendState is LoadState.Loading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "load-more-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimensions.ListItemPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator()
                        }
                    }
                } else if (appendState is LoadState.Error) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "append-error") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FilledTonalButton(onClick = { pagingItems.retry() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPosterSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .skeletonElement(pulse = false),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .skeletonElement(pulse = false),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(12.dp)
                .skeletonElement(pulse = false),
        )
    }
}

private const val LIBRARY_SKELETON_COUNT = 9
