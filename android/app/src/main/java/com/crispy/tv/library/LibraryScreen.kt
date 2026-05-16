package com.crispy.tv.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LIBRARY_PAGE_SIZE = 60
internal const val LIBRARY_SECTION_HISTORY = "history"
internal const val LIBRARY_SECTION_WATCHLIST = "watchlist"
internal const val LIBRARY_SECTION_RATINGS = "ratings"

private const val RATING_BAND_TOP = "top_rated"
private const val RATING_BAND_LIKED = "liked"
private const val RATING_BAND_MIXED = "mixed"
private const val RATING_BAND_LOW = "low_rated"

private const val WATCHLIST_GROUP_THIS_MONTH = "this_month"
private const val WATCHLIST_GROUP_LAST_MONTH = "last_month"
private const val WATCHLIST_GROUP_EARLIER_THIS_YEAR = "earlier_this_year"
private const val WATCHLIST_GROUP_LAST_YEAR = "last_year"
private const val WATCHLIST_GROUP_OLDER = "older"

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
    val logoUrl: String?,
    val poster: ResponsiveImageSet = ResponsiveImageSet.fromSingle(posterUrl),
    val backdrop: ResponsiveImageSet = ResponsiveImageSet.fromSingle(backdropUrl),
    val logo: ResponsiveImageSet = ResponsiveImageSet.fromSingle(logoUrl),
    val rating: Double?,
    val year: Int?,
    val genre: String?,
    val maturityRating: String?,
    val addedAt: String?,
    val watchedAt: String?,
    val ratedAt: String?,
    val ratingValue: Int?,
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

    @OptIn(ExperimentalCoroutinesApi::class)
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

// region History month grouping

@Immutable
private data class HistoryMonthSectionUi(
    val monthKey: String,
    val label: String,
    val items: List<LibrarySectionItemUi>,
)

private fun buildHistoryMonthSections(items: List<LibrarySectionItemUi>): List<HistoryMonthSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<HistoryMonthSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<LibrarySectionItemUi>()
    for (item in items) {
        val key = historyMonthKey(item.lastActivityAt ?: item.watchedAt)
        if (key != currentKey && currentKey != null && currentItems.isNotEmpty()) {
            result.add(HistoryMonthSectionUi(currentKey, historyMonthLabel(currentKey), currentItems.toList()))
            currentItems = mutableListOf()
        }
        currentKey = key
        currentItems.add(item)
    }
    if (currentKey != null && currentItems.isNotEmpty()) {
        result.add(HistoryMonthSectionUi(currentKey, historyMonthLabel(currentKey), currentItems.toList()))
    }
    return result
}

private fun historyMonthKey(timestamp: String?): String {
    if (timestamp.isNullOrBlank()) return "unknown"
    return try {
        val instant = Instant.parse(timestamp)
        YearMonth.from(instant.atZone(ZoneId.systemDefault())).toString()
    } catch (_: Exception) {
        "unknown"
    }
}

private fun historyMonthLabel(monthKey: String): String {
    if (monthKey == "unknown") return "Unknown date"
    return try {
        val ym = YearMonth.parse(monthKey)
        val now = YearMonth.now()
        when (ym) {
            now -> "This Month"
            now.minusMonths(1) -> "Last Month"
            else -> ym.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }
    } catch (_: Exception) {
        "Unknown date"
    }
}

private sealed interface HistoryDisplayRow {
    val stableKey: String
    val contentType: String
    data class Header(val monthKey: String, val label: String) : HistoryDisplayRow {
        override val stableKey get() = "history-month-$monthKey"
        override val contentType get() = "sectionHeader"
    }
    data class Post(val monthKey: String, val items: List<LibrarySectionItemUi>) : HistoryDisplayRow {
        override val stableKey get() = "history-row-$monthKey"
        override val contentType get() = "posterRow"
    }
}

// endregion

// region Rating band grouping

@Immutable
private data class RatingBandUi(
    val bandKey: String,
    val label: String,
    val items: List<LibrarySectionItemUi>,
)

private fun buildRatingBandSections(items: List<LibrarySectionItemUi>): List<RatingBandUi> {
    val bands =
        listOf(
            RATING_BAND_TOP to "Top Rated",
            RATING_BAND_LIKED to "Liked",
            RATING_BAND_MIXED to "Mixed Feelings",
            RATING_BAND_LOW to "Low Rated",
        )
    return bands.map { (key, label) ->
        val bandItems =
            when (key) {
                RATING_BAND_TOP -> items.filter { it.ratingValue != null && it.ratingValue in 8..10 }
                RATING_BAND_LIKED -> items.filter { it.ratingValue != null && it.ratingValue in 6..7 }
                RATING_BAND_MIXED -> items.filter { it.ratingValue != null && it.ratingValue in 4..5 }
                RATING_BAND_LOW -> items.filter { it.ratingValue != null && it.ratingValue in 1..3 }
                else -> emptyList()
            }
        RatingBandUi(key, label, bandItems)
    }.filter { it.items.isNotEmpty() }
}

private sealed interface RatingDisplayRow {
    val stableKey: String
    val contentType: String
    data class Header(val bandKey: String, val label: String) : RatingDisplayRow {
        override val stableKey get() = "rating-band-$bandKey"
        override val contentType get() = "sectionHeader"
    }
    data class Post(val bandKey: String, val items: List<LibrarySectionItemUi>) : RatingDisplayRow {
        override val stableKey get() = "rating-row-$bandKey"
        override val contentType get() = "posterRow"
    }
}

// endregion

// region Watchlist date grouping

private fun watchlistGroupKey(addedAt: String?): String {
    if (addedAt.isNullOrBlank()) return WATCHLIST_GROUP_OLDER
    return try {
        val instant = Instant.parse(addedAt)
        val zdt = instant.atZone(ZoneId.systemDefault())
        val now = Instant.now().atZone(ZoneId.systemDefault())
        val addedMonth = YearMonth.from(zdt)
        val nowMonth = YearMonth.from(now)
        val addedYear = zdt.year
        val nowYear = now.year
        when {
            addedMonth == nowMonth -> WATCHLIST_GROUP_THIS_MONTH
            addedMonth == nowMonth.minusMonths(1) -> WATCHLIST_GROUP_LAST_MONTH
            addedYear == nowYear -> WATCHLIST_GROUP_EARLIER_THIS_YEAR
            addedYear == nowYear - 1 -> WATCHLIST_GROUP_LAST_YEAR
            else -> WATCHLIST_GROUP_OLDER
        }
    } catch (_: Exception) {
        WATCHLIST_GROUP_OLDER
    }
}

private fun watchlistGroupLabel(groupKey: String): String =
    when (groupKey) {
        WATCHLIST_GROUP_THIS_MONTH -> "This Month"
        WATCHLIST_GROUP_LAST_MONTH -> "Last Month"
        WATCHLIST_GROUP_EARLIER_THIS_YEAR -> "Earlier This Year"
        WATCHLIST_GROUP_LAST_YEAR -> "Last Year"
        WATCHLIST_GROUP_OLDER -> "Older"
        else -> "Older"
    }

@Immutable
private data class WatchlistDateSectionUi(
    val groupKey: String,
    val label: String,
    val items: List<LibrarySectionItemUi>,
)

private fun buildWatchlistDateSections(items: List<LibrarySectionItemUi>): List<WatchlistDateSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<WatchlistDateSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<LibrarySectionItemUi>()
    for (item in items) {
        val key = watchlistGroupKey(item.addedAt)
        if (key != currentKey && currentKey != null && currentItems.isNotEmpty()) {
            result.add(WatchlistDateSectionUi(currentKey, watchlistGroupLabel(currentKey), currentItems.toList()))
            currentItems = mutableListOf()
        }
        currentKey = key
        currentItems.add(item)
    }
    if (currentKey != null && currentItems.isNotEmpty()) {
        result.add(WatchlistDateSectionUi(currentKey, watchlistGroupLabel(currentKey), currentItems.toList()))
    }
    return result
}

private sealed interface WatchlistDisplayRow {
    val stableKey: String
    val contentType: String
    data class Header(val groupKey: String, val label: String) : WatchlistDisplayRow {
        override val stableKey get() = "watchlist-group-$groupKey"
        override val contentType get() = "sectionHeader"
    }
    data class Post(val groupKey: String, val items: List<LibrarySectionItemUi>) : WatchlistDisplayRow {
        override val stableKey get() = "watchlist-row-$groupKey"
        override val contentType get() = "posterRow"
    }
}

// endregion

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
    val listState = rememberLazyListState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()
    val refreshState = pagingItems.loadState.refresh
    val appendState = pagingItems.loadState.append
    val isRefreshing = refreshState is LoadState.Loading && pagingItems.itemCount > 0
    val isHistory = selectedSectionId == LIBRARY_SECTION_HISTORY
    val isRatings = selectedSectionId == LIBRARY_SECTION_RATINGS

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            listState.animateScrollToItem(0)
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
        if (isHistory) {
            HistoryLibraryContent(
                pagingItems = pagingItems,
                selectedSection = selectedSection,
                sections = sections,
                selectedSectionId = selectedSectionId,
                refreshState = refreshState,
                appendState = appendState,
                pageHorizontalPadding = pageHorizontalPadding,
                onRefresh = onRefresh,
                onItemClick = onItemClick,
                onSelectSection = onSelectSection,
                listState = listState,
            )
        } else if (isRatings) {
            RatingsLibraryContent(
                pagingItems = pagingItems,
                selectedSection = selectedSection,
                sections = sections,
                selectedSectionId = selectedSectionId,
                refreshState = refreshState,
                appendState = appendState,
                pageHorizontalPadding = pageHorizontalPadding,
                onRefresh = onRefresh,
                onItemClick = onItemClick,
                onSelectSection = onSelectSection,
                listState = listState,
            )
        } else {
            WatchlistLibraryContent(
                pagingItems = pagingItems,
                selectedSection = selectedSection,
                sections = sections,
                selectedSectionId = selectedSectionId,
                refreshState = refreshState,
                appendState = appendState,
                pageHorizontalPadding = pageHorizontalPadding,
                onRefresh = onRefresh,
                onItemClick = onItemClick,
                onSelectSection = onSelectSection,
                listState = listState,
            )
        }
    }
}

// region Extracted UI components

@Composable
private fun LibraryFiltersRow(
    sections: List<LibrarySectionUi>,
    selectedSectionId: String,
    onSelectSection: (String) -> Unit,
) {
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
}

@Composable
private fun LibraryStatusMessage(
    refreshState: LoadState,
    appendState: LoadState,
    hasItems: Boolean,
    selectedSectionLabel: String?,
    modifier: Modifier = Modifier,
) {
    val message =
        when {
            refreshState is LoadState.Error && hasItems -> {
                refreshState.error.message ?: "Failed to refresh ${selectedSectionLabel ?: "this section"}."
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
            modifier = modifier,
        )
    }
}

@Composable
private fun LibraryEmptyState(
    refreshState: LoadState,
    selectedSectionLabel: String?,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Dimensions.ListItemPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text =
                    if (refreshState is LoadState.Error) {
                        refreshState.error.message ?: "Failed to load ${selectedSectionLabel ?: "this section"}."
                    } else {
                        "No items in ${selectedSectionLabel ?: "this section"} yet."
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryAppendState(
    appendState: LoadState,
    onRetry: () -> Unit,
) {
    if (appendState is LoadState.Loading) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.ListItemPadding),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }
    } else if (appendState is LoadState.Error) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            FilledTonalButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// endregion

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistoryLibraryContent(
    pagingItems: LazyPagingItems<LibrarySectionItemUi>,
    selectedSection: LibrarySectionUi?,
    sections: List<LibrarySectionUi>,
    selectedSectionId: String,
    refreshState: LoadState,
    appendState: LoadState,
    pageHorizontalPadding: Dp,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val loadedItems = remember(pagingItems.itemSnapshotList) {
        pagingItems.itemSnapshotList.filterNotNull()
    }
    val monthSections = remember(loadedItems) { buildHistoryMonthSections(loadedItems) }
    val displayRows = remember(monthSections) {
        monthSections.flatMap { section ->
            listOf(
                HistoryDisplayRow.Header(section.monthKey, section.label),
                HistoryDisplayRow.Post(section.monthKey, section.items),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp + Dimensions.PageBottomPadding),
    ) {
        item(key = "filters") {
            Box(modifier = Modifier.padding(horizontal = pageHorizontalPadding)) {
                LibraryFiltersRow(
                    sections = sections,
                    selectedSectionId = selectedSectionId,
                    onSelectSection = onSelectSection,
                )
            }
        }

        item(key = "status") {
            LibraryStatusMessage(
                refreshState = refreshState,
                appendState = appendState,
                hasItems = pagingItems.itemCount > 0,
                selectedSectionLabel = selectedSection?.label,
                modifier = Modifier.padding(horizontal = pageHorizontalPadding),
            )
        }

        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            item(key = "history-loading") {
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
                    onRefresh = onRefresh,
                )
            }
        } else {
            items(displayRows, key = { it.stableKey }, contentType = { it.contentType }) { row ->
                when (row) {
                    is HistoryDisplayRow.Header -> {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = pageHorizontalPadding, vertical = 8.dp),
                        )
                    }
                    is HistoryDisplayRow.Post -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = pageHorizontalPadding),
                        ) {
                            items(row.items, key = { it.stableKey }, contentType = { "poster" }) { item ->
                                PosterCard(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    rating = item.rating?.toString(),
                                    year = item.year?.toString(),
                                    maturityRating = item.maturityRating,
                                    genre = item.genre,
                                    logoUrl = item.logoUrl,
                                    poster = item.poster,
                                    backdrop = item.backdrop,
                                    logo = item.logo,
                                    gradientColorHex = null,
                                    modifier = Modifier.width(Dimensions.PosterCardWidth),
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "load-more") {
                if (pagingItems.itemCount > 0) {
                    pagingItems[pagingItems.itemCount - 1]
                }
                LibraryAppendState(
                    appendState = appendState,
                    onRetry = { pagingItems.retry() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RatingsLibraryContent(
    pagingItems: LazyPagingItems<LibrarySectionItemUi>,
    selectedSection: LibrarySectionUi?,
    sections: List<LibrarySectionUi>,
    selectedSectionId: String,
    refreshState: LoadState,
    appendState: LoadState,
    pageHorizontalPadding: Dp,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val loadedItems = remember(pagingItems.itemSnapshotList) {
        pagingItems.itemSnapshotList.filterNotNull()
    }
    val bandSections = remember(loadedItems) { buildRatingBandSections(loadedItems) }
    val displayRows = remember(bandSections) {
        bandSections.flatMap { section ->
            listOf(
                RatingDisplayRow.Header(section.bandKey, section.label),
                RatingDisplayRow.Post(section.bandKey, section.items),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp + Dimensions.PageBottomPadding),
    ) {
        item(key = "filters") {
            Box(modifier = Modifier.padding(horizontal = pageHorizontalPadding)) {
                LibraryFiltersRow(
                    sections = sections,
                    selectedSectionId = selectedSectionId,
                    onSelectSection = onSelectSection,
                )
            }
        }

        item(key = "status") {
            LibraryStatusMessage(
                refreshState = refreshState,
                appendState = appendState,
                hasItems = pagingItems.itemCount > 0,
                selectedSectionLabel = selectedSection?.label,
                modifier = Modifier.padding(horizontal = pageHorizontalPadding),
            )
        }

        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            item(key = "ratings-loading") {
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
                    onRefresh = onRefresh,
                )
            }
        } else {
            items(displayRows, key = { it.stableKey }, contentType = { it.contentType }) { row ->
                when (row) {
                    is RatingDisplayRow.Header -> {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = pageHorizontalPadding, vertical = 8.dp),
                        )
                    }
                    is RatingDisplayRow.Post -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = pageHorizontalPadding),
                        ) {
                            items(row.items, key = { it.stableKey }, contentType = { "poster" }) { item ->
                                PosterCard(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    rating = item.rating?.toString(),
                                    year = item.year?.toString(),
                                    maturityRating = item.maturityRating,
                                    genre = item.genre,
                                    logoUrl = item.logoUrl,
                                    poster = item.poster,
                                    backdrop = item.backdrop,
                                    logo = item.logo,
                                    gradientColorHex = null,
                                    modifier = Modifier.width(Dimensions.PosterCardWidth),
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "load-more") {
                if (pagingItems.itemCount > 0) {
                    pagingItems[pagingItems.itemCount - 1]
                }
                LibraryAppendState(
                    appendState = appendState,
                    onRetry = { pagingItems.retry() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WatchlistLibraryContent(
    pagingItems: LazyPagingItems<LibrarySectionItemUi>,
    selectedSection: LibrarySectionUi?,
    sections: List<LibrarySectionUi>,
    selectedSectionId: String,
    refreshState: LoadState,
    appendState: LoadState,
    pageHorizontalPadding: Dp,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val loadedItems = remember(pagingItems.itemSnapshotList) {
        pagingItems.itemSnapshotList.filterNotNull()
    }
    val dateSections = remember(loadedItems) { buildWatchlistDateSections(loadedItems) }
    val displayRows = remember(dateSections) {
        dateSections.flatMap { section ->
            listOf(
                WatchlistDisplayRow.Header(section.groupKey, section.label),
                WatchlistDisplayRow.Post(section.groupKey, section.items),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp + Dimensions.PageBottomPadding),
    ) {
        item(key = "filters") {
            Box(modifier = Modifier.padding(horizontal = pageHorizontalPadding)) {
                LibraryFiltersRow(
                    sections = sections,
                    selectedSectionId = selectedSectionId,
                    onSelectSection = onSelectSection,
                )
            }
        }

        item(key = "status") {
            LibraryStatusMessage(
                refreshState = refreshState,
                appendState = appendState,
                hasItems = pagingItems.itemCount > 0,
                selectedSectionLabel = selectedSection?.label,
                modifier = Modifier.padding(horizontal = pageHorizontalPadding),
            )
        }

        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            item(key = "watchlist-loading") {
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
                    onRefresh = onRefresh,
                )
            }
        } else {
            items(displayRows, key = { it.stableKey }, contentType = { it.contentType }) { row ->
                when (row) {
                    is WatchlistDisplayRow.Header -> {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = pageHorizontalPadding, vertical = 8.dp),
                        )
                    }
                    is WatchlistDisplayRow.Post -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = pageHorizontalPadding),
                        ) {
                            items(row.items, key = { it.stableKey }, contentType = { "poster" }) { item ->
                                PosterCard(
                                    title = item.title,
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    rating = item.rating?.toString(),
                                    year = item.year?.toString(),
                                    maturityRating = item.maturityRating,
                                    genre = item.genre,
                                    logoUrl = item.logoUrl,
                                    poster = item.poster,
                                    backdrop = item.backdrop,
                                    logo = item.logo,
                                    gradientColorHex = null,
                                    modifier = Modifier.width(Dimensions.PosterCardWidth),
                                    onClick = { onItemClick(item) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "load-more") {
                if (pagingItems.itemCount > 0) {
                    pagingItems[pagingItems.itemCount - 1]
                }
                LibraryAppendState(
                    appendState = appendState,
                    onRetry = { pagingItems.retry() },
                )
            }
        }
    }
}
