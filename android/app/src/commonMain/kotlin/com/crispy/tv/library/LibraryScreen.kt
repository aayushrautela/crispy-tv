package com.crispy.tv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.domain.watch.WatchSyncEffect
import com.crispy.tv.home.HomeRefreshBus
import com.crispy.tv.home.HomeRefreshEvent
import com.crispy.tv.watchhistory.sync.WatchSyncSource
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.optimistic.toContentType
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.theme.CrispyPalette
import com.crispy.tv.ui.theme.Dimensions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.crispy.tv.domain.watch.civilMonthKey
import com.crispy.tv.domain.watch.civilMonthKeyFromEpochMillis
import com.crispy.tv.domain.watch.previousMonthKey
import com.crispy.tv.backend.WatchGenerationsResponse

private const val LIBRARY_PAGE_SIZE = 60

// `internal` rather than `private` because these are the *identity* of each band:
// they are what a section's `groupKey`/`bandKey` carries and what its row `key`
// is built from, so a suite asserting against a hand-typed `"this_month"` would be
// asserting its own string rather than this constant. `LibraryMonthKeyTest` names
// all seven.
internal const val RATING_BAND_LIKED = "liked"
internal const val RATING_BAND_DISLIKED = "disliked"

internal const val WATCHLIST_GROUP_THIS_MONTH = "this_month"
internal const val WATCHLIST_GROUP_LAST_MONTH = "last_month"
internal const val WATCHLIST_GROUP_EARLIER_THIS_YEAR = "earlier_this_year"
internal const val WATCHLIST_GROUP_LAST_YEAR = "last_year"
internal const val WATCHLIST_GROUP_OLDER = "older"

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
    private val backend: BackendApi,
    private val backendContextResolver: BackendContextResolver,
    private val userMediaRepository: UserMediaRepository,
    private val outbox: UserMutationOutbox,
    private val libraryCache: LibraryDiskCache,
    // The sync channel is an `okhttp` socket, so the socket itself cannot cross.
    // What crosses is the *decision* to open one for a resolved session -- the same
    // slot HomeViewModel takes, deliberately given the same name and the same three
    // parameters, so the two readers of the codebase find one shape rather than two.
    private val watchSyncFactory: (
        accessToken: String,
        profileId: String,
        onEffect: (WatchSyncEffect) -> Unit,
    ) -> WatchSyncSource,
    private val clock: () -> Long,
    // The slot exists so this screen's own ids are deterministic under test, and nothing
    // else. It used to carry a second, load-bearing reason -- "java.util.UUID has no
    // Kotlin/Native equivalent, so the mint lives in androidMain" -- which was false, and
    // that is why this file, already in commonMain, still could not name the function
    // that mints the ids it stores. `kotlin.uuid.Uuid.random()` is stable in Kotlin
    // 2.4.10, so `newUserMutationId` is in commonMain now and the slot is the only
    // thing between this screen and it. DetailsViewModel takes this same slot under this
    // same name, and both supply it from that one function.
    private val newMutationId: () -> String,
    /**
     * The dispatcher the library disk cache is read on.
     *
     * No default, for the reason `LibraryPagingSource` documents: `Dispatchers.IO`
     * is `internal` on Kotlin/Native, and a `Dispatchers.Default` default would put
     * blocking disk work on a CPU-sized pool while looking correct. The factory in
     * `androidMain` is the caller, and it hands the same value down to the paging
     * source it builds.
     */
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    // Bumped per section to re-trigger the Pager when a server signal invalidates
    // that section. Combined into the flow key below.
    private val sectionRefreshTokens = MutableStateFlow<Map<String, Int>>(emptyMap())

    private var syncSource: WatchSyncSource? = null

    // The most recent server generations snapshot, used to stamp fresh cache
    // writes so the next open can tell whether the cached page is stale.
    private var latestGenerations: WatchGenerationsResponse? = null

    init {
        viewModelScope.launch {
            val context = backendContextResolver.resolve() ?: return@launch
            syncSource =
                watchSyncFactory(
                    context.accessToken,
                    context.profileId,
                    { effect -> onSyncEffect(effect) },
                )
            syncSource?.onSurfaceVisible()
        }

        viewModelScope.launch {
            val context = backendContextResolver.resolve() ?: return@launch
            checkServerGenerations(context)
        }

        viewModelScope.launch {
            HomeRefreshBus.events.collect { event ->
                val sectionId =
                    when (event) {
                        HomeRefreshEvent.WatchlistChanged -> LIBRARY_SECTION_WATCHLIST
                        HomeRefreshEvent.HistoryChanged -> LIBRARY_SECTION_HISTORY
                        HomeRefreshEvent.RatingsChanged -> LIBRARY_SECTION_RATINGS
                        else -> return@collect
                    }
                viewModelScope.launch { invalidateSection(sectionId) }
            }
        }
    }

    private suspend fun checkServerGenerations(context: BackendContext) {
        val generations =
            runCatching {
                backend.getWatchGenerations(
                    accessToken = context.accessToken,
                    profileId = context.profileId,
                )
            }.getOrNull() ?: return
        latestGenerations = generations
        LIBRARY_SECTIONS.forEach { section ->
            val sectionId = section.id
            val serverGen = generations.generationMsFor(sectionId)
            if (serverGen != null) {
                val applied =
                    withContext(ioDispatcher) {
                        libraryCache.read(context.profileId, sectionId)?.appliedGenerationMs
                    }
                if (applied == null || serverGen > applied) {
                    libraryCache.invalidate(context.profileId, sectionId)
                    bumpSectionToken(sectionId)
                }
            }
        }
    }

    private suspend fun invalidateSection(sectionId: String) {
        val context = backendContextResolver.resolve() ?: return
        libraryCache.invalidate(context.profileId, sectionId)
        bumpSectionToken(sectionId)
    }

    private fun bumpSectionToken(sectionId: String) {
        sectionRefreshTokens.update { tokens ->
            tokens + (sectionId to ((tokens[sectionId] ?: 0) + 1))
        }
    }

    private fun onSyncEffect(effect: WatchSyncEffect) {
        val sectionId =
            when (effect) {
                WatchSyncEffect.RefetchHistory -> LIBRARY_SECTION_HISTORY
                WatchSyncEffect.RefetchWatchlist -> LIBRARY_SECTION_WATCHLIST
                WatchSyncEffect.RefetchRatings -> LIBRARY_SECTION_RATINGS
                else -> return
            }
        viewModelScope.launch { invalidateSection(sectionId) }
    }

    // Retained per-section pagers, keyed by section id.
    //
    // The old shape was one flow that `flatMapLatest`-ed on the selected
    // section: every switch killed the pager, the new section started from a
    // fresh empty `PagingData`, and the screen showed a spinner. That made a tab
    // switch animation impossible -- `AnimatedContent` needs the old section's
    // data still present while the new section loads -- and it refetched on
    // every switch-back. Each section now owns a pager built once via
    // `getOrPut` and kept across switches; only the section token invalidates
    // its own pager, and `cachedIn(viewModelScope)` survives collection restarts
    // the same way the old flow did.
    private val sectionPagers = mutableMapOf<String, Flow<PagingData<CatalogItem>>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun itemsFor(sectionId: String): Flow<PagingData<CatalogItem>> =
        sectionPagers.getOrPut(sectionId) {
            sectionRefreshTokens
                .map { tokens -> tokens[sectionId] ?: 0 }
                .distinctUntilChanged()
                .flatMapLatest {
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
                                libraryCache = libraryCache,
                                appliedGenerationMsProvider = { latestGenerations?.generationMsFor(sectionId) },
                                ioDispatcher = ioDispatcher,
                            )
                        },
                    ).flow
                }.cachedIn(viewModelScope)
        }

    override fun onCleared() {
        syncSource?.onSurfaceHidden()
        syncSource?.close()
        syncSource = null
        super.onCleared()
    }

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
        val now = clock()
        outbox.enqueue(
            TitleWatchedMutation(
                id = newMutationId(),
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

}

internal fun WatchGenerationsResponse.generationMsFor(sectionId: String): Long? {
    return when (sectionId) {
        LIBRARY_SECTION_HISTORY -> historyMs
        LIBRARY_SECTION_WATCHLIST -> watchlistMs
        LIBRARY_SECTION_RATINGS -> ratingsMs
        else -> null
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
internal data class HistoryMonthSectionUi(
    val monthKey: String,
    val label: String,
    val items: List<CatalogItem>,
)

// `java.time` supplied two impure answers to these questions -- which month an
// instant falls in, and which month "now" is -- and both are now parameters. The
// caller decides the zone, because `ZoneId.systemDefault()` is a reading of the
// device rather than a property of the timestamp, and it decides the clock for the
// same reason. Everything below is arithmetic on two `"yyyy-MM"` strings.
internal fun buildHistoryMonthSections(
    items: List<CatalogItem>,
    currentMonthKey: String,
    utcOffsetMillis: Long,
    monthName: (String) -> String,
): List<HistoryMonthSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<HistoryMonthSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<CatalogItem>()
    for (item in items) {
        val key = historyMonthKey(item.lastActivityAt ?: item.watchedAt, utcOffsetMillis)
        if (key != currentKey && currentKey != null && currentItems.isNotEmpty()) {
            result.add(HistoryMonthSectionUi(currentKey, historyMonthLabel(currentKey, currentMonthKey, monthName), currentItems.toList()))
            currentItems = mutableListOf()
        }
        currentKey = key
        currentItems.add(item)
    }
    if (currentKey != null && currentItems.isNotEmpty()) {
        result.add(HistoryMonthSectionUi(currentKey, historyMonthLabel(currentKey, currentMonthKey, monthName), currentItems.toList()))
    }
    return result.map { section -> section.copy(items = collapseEpisodesByShow(section.items)) }
}

internal fun historyMonthKey(timestamp: String?, utcOffsetMillis: Long): String =
    // `isNullOrBlank` is redundant with the `?:` on the other side, and that was
    // measured: a mutation to `isNullOrEmpty` compiles and every test still passes,
    // because `civilMonthKey("   ", offset)` is null -- the ISO parser rejects a blank
    // string -- so both spellings reach `"unknown"`. It is kept because it states the
    // rule the repository's own style asks for ("treat blank as missing") rather than
    // leaving it as a side effect of the parser's strictness, and because it is the
    // cheap half of the pair: `previousMonthKey` and `historyMonthLabel` both treat
    // the literal `"unknown"` as the sentinel, so the branch that produces it should
    // say why. Do not read the surviving mutation as a missing test.
    if (timestamp.isNullOrBlank()) "unknown" else civilMonthKey(timestamp, utcOffsetMillis) ?: "unknown"

/**
 * The copy here is the app's, not a format: only the *name* of an older month is
 * locale-aware, and that half is the [monthName] slot. The two words this decides on
 * its own are English in the original too, so they did not follow the slot.
 *
 * The `try`/`catch` this replaced guarded a `YearMonth.parse` throw that a key
 * [historyMonthKey] produced cannot make, so the guard is gone rather than
 * reproduced: a `when` over two strings has nothing to throw on. A key from
 * somewhere else is a different question, and the answer is at [yearOf].
 */
internal fun historyMonthLabel(
    monthKey: String,
    currentMonthKey: String,
    monthName: (String) -> String,
): String = when {
    monthKey == "unknown" -> "Unknown date"
    monthKey == currentMonthKey -> "This Month"
    monthKey == previousMonthKey(currentMonthKey) -> "Last Month"
    else -> monthName(monthKey)
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
internal data class RatingBandUi(
    val bandKey: String,
    val label: String,
    val items: List<CatalogItem>,
)

// `internal` rather than `private`: this picks the band's order and its `liked ==
// null` fate, and both are decisions a test can otherwise only reach through the
// whole screen. `LibraryMonthKeyTest` calls it and pins all three.
internal fun buildRatingBandSections(items: List<CatalogItem>): List<RatingBandUi> {
    val bands =
        listOf(
            RATING_BAND_LIKED to "Liked",
            RATING_BAND_DISLIKED to "Disliked",
        )
    return bands.map { (key, label) ->
        val bandItems =
            when (key) {
                RATING_BAND_LIKED -> items.filter { it.liked == true }
                RATING_BAND_DISLIKED -> items.filter { it.liked == false }
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

/**
 * The four bands, oldest last. The original read `addedYear` off the resolved
 * `ZonedDateTime` and `nowYear` off `Instant.now()`, which meant it held a year *as
 * well as* a month; holding only the month key and reading the year out of it is the
 * same answer, because a `"yyyy-MM"` key's year is its first four characters.
 *
 * [yearOf] returns `0` for a key it cannot read, and that is deliberate rather than
 * defensive: the only keys that reach here come from [civilMonthKey], so the
 * fallback is for a caller that has not been written yet, and it lands in
 * [WATCHLIST_GROUP_OLDER] the same way a parse failure did before.
 */
internal fun watchlistGroupKey(
    addedAt: String?,
    currentMonthKey: String,
    utcOffsetMillis: Long,
): String {
    // Redundant with the `?:` two lines down, and measured: mutating this to
    // `isNullOrEmpty` compiles and every test still passes, because a blank string
    // fails the ISO parser and lands in `WATCHLIST_GROUP_OLDER` anyway. Kept for the
    // same reason as the `isNullOrBlank` in `historyMonthKey` -- it names the rule
    // instead of inheriting it from the parser's strictness. This is the *second*
    // instance of that shape in this file and the ninth in the repository; both are
    // written down here so the next person does not read the surviving mutation as a
    // gap in the suite.
    if (addedAt.isNullOrBlank()) return WATCHLIST_GROUP_OLDER
    val addedMonthKey = civilMonthKey(addedAt, utcOffsetMillis) ?: return WATCHLIST_GROUP_OLDER
    val addedYear = yearOf(addedMonthKey)
    val currentYear = yearOf(currentMonthKey)
    return when {
        addedMonthKey == currentMonthKey -> WATCHLIST_GROUP_THIS_MONTH
        addedMonthKey == previousMonthKey(currentMonthKey) -> WATCHLIST_GROUP_LAST_MONTH
        addedYear == currentYear -> WATCHLIST_GROUP_EARLIER_THIS_YEAR
        addedYear == currentYear - 1 -> WATCHLIST_GROUP_LAST_YEAR
        else -> WATCHLIST_GROUP_OLDER
    }
}

private fun yearOf(monthKey: String): Int = monthKey.take(4).toIntOrNull() ?: 0

// `internal` rather than `private` for the same reason `buildHistoryMonthSections`
// is: a decision no test can call is a decision no test can cover, and this one has
// five arms plus a fallback. `LibraryMonthKeyTest` calls it directly rather than
// re-deriving the answer, which is what makes a deleted arm a failure.
internal fun watchlistGroupLabel(groupKey: String): String =
    when (groupKey) {
        WATCHLIST_GROUP_THIS_MONTH -> "This Month"
        WATCHLIST_GROUP_LAST_MONTH -> "Last Month"
        WATCHLIST_GROUP_EARLIER_THIS_YEAR -> "Earlier This Year"
        WATCHLIST_GROUP_LAST_YEAR -> "Last Year"
        WATCHLIST_GROUP_OLDER -> "Older"
        else -> "Older"
    }

@Immutable
internal data class WatchlistDateSectionUi(
    val groupKey: String,
    val label: String,
    val items: List<CatalogItem>,
)

// Same two decisions as the history grouping above, one more question: the two
// year-only bands ("earlier this year", "last year") need the year, which the
// four leading characters of a `"yyyy-MM"` key already are.
internal fun buildWatchlistDateSections(
    items: List<CatalogItem>,
    currentMonthKey: String,
    utcOffsetMillis: Long,
): List<WatchlistDateSectionUi> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<WatchlistDateSectionUi>()
    var currentKey: String? = null
    var currentItems = mutableListOf<CatalogItem>()
    for (item in items) {
        val key = watchlistGroupKey(item.addedAt, currentMonthKey, utcOffsetMillis)
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

@Composable
internal fun ColumnScope.HistorySectionContent(
    loadedItems: List<CatalogItem>,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    onItemLongPress: (CatalogItem) -> Unit,
    currentMonthKey: String,
    utcOffsetMillis: Long,
    monthName: (String) -> String,
) {
    val monthSections =
        buildHistoryMonthSections(
            items = loadedItems,
            currentMonthKey = currentMonthKey,
            utcOffsetMillis = utcOffsetMillis,
            monthName = monthName,
        )
    val displayRows = monthSections.flatMap { section ->
        listOf(
            HistoryDisplayRow.Header(section.monthKey, section.label),
            HistoryDisplayRow.Post(section.monthKey, section.items),
        )
    }
    displayRows.forEach { row ->
        key(row.stableKey) {
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
}

@Composable
internal fun ColumnScope.RatingsSectionContent(
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
    displayRows.forEach { row ->
        key(row.stableKey) {
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
}

@Composable
internal fun ColumnScope.WatchlistSectionContent(
    loadedItems: List<CatalogItem>,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    onItemLongPress: (CatalogItem) -> Unit,
    currentMonthKey: String,
    utcOffsetMillis: Long,
) {
    val dateSections =
        buildWatchlistDateSections(
            items = loadedItems,
            currentMonthKey = currentMonthKey,
            utcOffsetMillis = utcOffsetMillis,
        )
    val displayRows = dateSections.flatMap { section ->
        listOf(
            WatchlistDisplayRow.Header(section.groupKey, section.label),
            WatchlistDisplayRow.Post(section.groupKey, section.items),
        )
    }
    displayRows.forEach { row ->
        key(row.stableKey) {
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryFiltersRow(
    sections: List<LibrarySectionUi>,
    selectedSectionId: String,
    onSelectSection: (String) -> Unit,
) {
    // A connected button group, not filter chips: one full-width control whose
    // checked button carries `Primary` and whose shape is continuous across the
    // group. The position decides the shape -- leading, middle or trailing --
    // so the three buttons read as one segmented control rather than three
    // independent chips. The label is bold only when checked, which is the
    // group's only per-state styling; everything else is the theme default.
    if (sections.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            sections.forEachIndexed { index, section ->
                val checked = section.id == selectedSectionId
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onSelectSection(section.id) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shapes =
                        when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            sections.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = section.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                    )
                }
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
            LoadingIndicator(color = CrispyPalette.spinner)
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

/**
 * The month the device is currently in, as the same `"yyyy-MM"` key
 * [civilMonthKey] produces for a history entry.
 *
 * This is the answer `YearMonth.now()` used to give inline, and it is separated for
 * one reason: it is the only value in either grouping that changes without any input
 * changing, so it is the one a test has to be able to set.
 */
internal fun currentMonthKeyOf(clock: () -> Long, utcOffsetMillis: Long): String =
    civilMonthKeyFromEpochMillis(clock(), utcOffsetMillis)
