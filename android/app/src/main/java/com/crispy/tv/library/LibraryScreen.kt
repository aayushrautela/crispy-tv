package com.crispy.tv.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.app.appGraph
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.optimistic.toContentType
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.catalog.CatalogItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.theme.Dimensions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    private val userMediaRepository: UserMediaRepository,
    private val outbox: UserMutationOutbox,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<CatalogItem>> =
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

    fun setWatched(item: CatalogItem, desired: Boolean) {
        val contentType =
            (if (item.type == "movie") MetadataLabMediaType.MOVIE else MetadataLabMediaType.SERIES).toContentType()
        val now = System.currentTimeMillis()
        outbox.enqueue(
            TitleWatchedMutation(
                id = UserMutationOutbox.newId(),
                titleItemId = item.itemId,
                entityId = item.itemId,
                createdAtMs = now,
                attempt = 0,
                status = MutationStatus.Pending,
                nextAttemptAtMs = now,
                contentType = contentType,
                desired = desired,
            ),
        )
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
                            userMediaRepository = DefaultUserMediaRepository(PlaybackDependencies.watchHistoryServiceFactory(appContext)),
                            outbox = appContext.appGraph().userMutationOutbox,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

// region Episode -> show collapse

internal fun collapseEpisodesByShow(items: List<CatalogItem>): List<CatalogItem> {
    val mergedByShow = mutableMapOf<String, CatalogItem>()
    val out = mutableListOf<CatalogItem>()
    for (item in items) {
        val existing = mergedByShow[item.itemId]
        if (existing == null) {
            mergedByShow[item.itemId] = item
            out += item
        } else {
            val updated = existing.copy(episodeCount = (existing.episodeCount ?: 0) + (item.episodeCount ?: 1))
            out[out.indexOf(existing)] = updated
            mergedByShow[item.itemId] = updated
        }
    }
    return out
}

// endregion

// region History month grouping

@Immutable
private data class HistoryMonthSectionUi(
    val monthKey: String,
    val label: String,
    val items: List<CatalogItem>,
)

private fun buildHistoryMonthSections(items: List<CatalogItem>): List<HistoryMonthSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<HistoryMonthSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<CatalogItem>()
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
    return result.map { section -> section.copy(items = collapseEpisodesByShow(section.items)) }
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
    data class Post(val monthKey: String, val items: List<CatalogItem>) : HistoryDisplayRow {
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
    val items: List<CatalogItem>,
)

private fun buildRatingBandSections(items: List<CatalogItem>): List<RatingBandUi> {
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
    data class Post(val bandKey: String, val items: List<CatalogItem>) : RatingDisplayRow {
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
    val items: List<CatalogItem>,
)

private fun buildWatchlistDateSections(items: List<CatalogItem>): List<WatchlistDateSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<WatchlistDateSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<CatalogItem>()
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
    data class Post(val groupKey: String, val items: List<CatalogItem>) : WatchlistDisplayRow {
        override val stableKey get() = "watchlist-row-$groupKey"
        override val contentType get() = "posterRow"
    }
}

// endregion

internal fun LazyListScope.historyItems(
    loadedItems: List<CatalogItem>,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    onItemLongPress: (CatalogItem) -> Unit,
) {
    val monthSections = buildHistoryMonthSections(loadedItems)
    val displayRows = monthSections.flatMap { section ->
        listOf(
            HistoryDisplayRow.Header(section.monthKey, section.label),
            HistoryDisplayRow.Post(section.monthKey, section.items),
        )
    }
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
                    items(row.items, key = { it.id }, contentType = { "poster" }) { item ->
                        val sharedElementKey = "library-history-${row.monthKey}-${item.itemId}"
                        LandscapeCard(
                            title = item.title,
                            artworkUrl = item.artworkUrl,
                            logoUrl = item.logoUrl,
                            artwork = item.artwork,
                            logo = item.logo,
                            rating = item.rating,
                            year = item.year,
                            maturityRating = item.maturityRating,
                            genre = item.genre,
                            badge = item.episodeCount?.let { if (it > 1) "$it episodes" else null },
                            modifier = Modifier.width(CardStyle.landscapeCardWidth()),
                            onClick = { onItemClick(item, sharedElementKey) },
onLongPress = { onItemLongPress(item) },
                            itemId = item.itemId,
                            sharedElementKey = sharedElementKey,
                        )
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.ratingsItems(
    loadedItems: List<CatalogItem>,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    onItemLongPress: (CatalogItem) -> Unit,
) {
    val bandSections = buildRatingBandSections(loadedItems)
    val displayRows = bandSections.flatMap { section ->
        listOf(
            RatingDisplayRow.Header(section.bandKey, section.label),
            RatingDisplayRow.Post(section.bandKey, section.items),
        )
    }
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
                    items(row.items, key = { it.id }, contentType = { "poster" }) { item ->
                        val sharedElementKey = "library-ratings-${row.bandKey}-${item.itemId}"
                        LandscapeCard(
                            title = item.title,
                            artworkUrl = item.artworkUrl,
                            logoUrl = item.logoUrl,
                            artwork = item.artwork,
                            logo = item.logo,
                            rating = item.rating,
                            year = item.year,
                            maturityRating = item.maturityRating,
                            genre = item.genre,
                            modifier = Modifier.width(CardStyle.landscapeCardWidth()),
                            onClick = { onItemClick(item, sharedElementKey) },
onLongPress = { onItemLongPress(item) },
                            itemId = item.itemId,
                            sharedElementKey = sharedElementKey,
                        )
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.watchlistItems(
    loadedItems: List<CatalogItem>,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    onItemLongPress: (CatalogItem) -> Unit,
) {
    val dateSections = buildWatchlistDateSections(loadedItems)
    val displayRows = dateSections.flatMap { section ->
        listOf(
            WatchlistDisplayRow.Header(section.groupKey, section.label),
            WatchlistDisplayRow.Post(section.groupKey, section.items),
        )
    }
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
                    items(row.items, key = { it.id }, contentType = { "poster" }) { item ->
                        val sharedElementKey = "library-watchlist-${row.groupKey}-${item.itemId}"
                        LandscapeCard(
                            title = item.title,
                            artworkUrl = item.artworkUrl,
                            logoUrl = item.logoUrl,
                            artwork = item.artwork,
                            logo = item.logo,
                            rating = item.rating,
                            year = item.year,
                            maturityRating = item.maturityRating,
                            genre = item.genre,
                            modifier = Modifier.width(CardStyle.landscapeCardWidth()),
                            onClick = { onItemClick(item, sharedElementKey) },
onLongPress = { onItemLongPress(item) },
                            itemId = item.itemId,
                            sharedElementKey = sharedElementKey,
                        )
                    }
                }
            }
        }
    }
}

// region Extracted UI components

@Composable
internal fun LibraryFiltersRow(
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
                        selectedContainerColor = Color.White,
                        selectedLabelColor = Color(0xFF141414),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun LibraryStatusMessage(
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
internal fun LibraryEmptyState(
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
internal fun LibraryAppendState(
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

