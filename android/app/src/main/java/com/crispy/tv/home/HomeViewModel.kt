package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

@Immutable
data class HeroState(
    val items: List<HomeHeroItem> = emptyList(),
    val selectedId: String? = null,
    val isLoading: Boolean = true,
    val statusMessage: String = "Loading featured content..."
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

private data class HomePrimarySnapshot(
    val hero: HeroState = HeroState(),
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
    val catalogStatusMessage: String = "",
)

private data class HomeWatchActivitySnapshot(
    val continueWatching: HomeWideRailSectionUi? = null,
    val upNext: HomeWideRailSectionUi? = null,
)

class HomeViewModel internal constructor(
    private val homeCatalogService: HomeCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: WatchHistoryService,
    private val calendarService: CalendarService,
    private val suppressionStore: ContinueWatchingSuppressionStore
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val SECTION_SKELETON_MIN_DURATION_MS = 150L
        private const val BACKGROUND_REFRESH_DEBOUNCE_MS = 60_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                        val tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext)
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            homeCatalogService = SupabaseServicesProvider.homeCatalogService(appContext),
                            homeWatchActivityService =
                                HomeWatchActivityService(
                                    context = appContext,
                                    tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                                ),
                            watchHistoryService = watchHistoryService,
                            calendarService = CalendarService(
                                watchHistoryService = watchHistoryService,
                                tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                            ),
                            suppressionStore = ContinueWatchingSuppressionStore(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var isForegroundLoading: Boolean = true
    private var primarySnapshot = HomePrimarySnapshot()
    private var watchActivitySnapshot = HomeWatchActivitySnapshot()
    private var thisWeekSection: HomeWideRailSectionUi? = null

    private var suppressedItemsByKey: MutableMap<String, Long>? = null
    @Volatile
    private var refreshGeneration: Long = 0L
    private var refreshJob: Job? = null
    private var backgroundRefreshJob: Job? = null
    private var refreshPending: Boolean = false
    private var lastRefreshCompletedAtMs: Long = 0L

    init {
        refresh(forceForegroundLoading = true)
    }

    fun refresh(forceForegroundLoading: Boolean = false) {
        backgroundRefreshJob?.cancel()
        if (refreshJob?.isActive == true) {
            refreshPending = true
            return
        }
        refreshPending = false
        val currentRefreshGeneration = beginRefresh()
        val showForegroundLoading = forceForegroundLoading || !hasLoadedHomeContent()
        prepareForRefresh(showForegroundLoading = showForegroundLoading)

        refreshJob =
            viewModelScope.launch {
                var nextPrimarySnapshot: HomePrimarySnapshot? = null
                var nextWatchActivitySnapshot: HomeWatchActivitySnapshot? = null
                try {
                    coroutineScope {
                        val primaryDeferred = async { loadPrimarySnapshot(currentRefreshGeneration) }
                        val watchActivityDeferred =
                            async {
                                loadWatchActivitySnapshot(
                                    generation = currentRefreshGeneration,
                                    enrichMetadata = !showForegroundLoading,
                                )
                            }
                        nextPrimarySnapshot = primaryDeferred.await()
                        nextWatchActivitySnapshot = watchActivityDeferred.await()
                    }
                } finally {
                    if (isCurrentRefresh(currentRefreshGeneration)) {
                        nextPrimarySnapshot?.let { primarySnapshot = it }
                        nextWatchActivitySnapshot?.let { watchActivitySnapshot = it }
                        isForegroundLoading = false
                        publishUiState(isRefreshing = false)
                        lastRefreshCompletedAtMs = System.currentTimeMillis()
                    }
                    refreshJob = null
                    if (refreshPending) {
                        refreshPending = false
                        refresh()
                    } else if (isCurrentRefresh(currentRefreshGeneration)) {
                        backgroundRefreshJob =
                            viewModelScope.launch {
                                val refreshedWatchActivitySnapshot =
                                    if (showForegroundLoading) {
                                        loadWatchActivitySnapshot(
                                            generation = currentRefreshGeneration,
                                            enrichMetadata = true,
                                        )
                                    } else {
                                        watchActivitySnapshot
                                    }
                                val refreshedThisWeekSection = loadThisWeekSection(currentRefreshGeneration)
                                if (!isCurrentRefresh(currentRefreshGeneration)) {
                                    return@launch
                                }

                                val didChange =
                                    refreshedWatchActivitySnapshot != watchActivitySnapshot ||
                                        refreshedThisWeekSection != thisWeekSection
                                watchActivitySnapshot = refreshedWatchActivitySnapshot
                                thisWeekSection = refreshedThisWeekSection

                                if (didChange) {
                                    publishUiState(isRefreshing = false)
                                }
                            }
                    }
                }
            }
    }

    private fun beginRefresh(): Long {
        val nextGeneration = refreshGeneration + 1L
        refreshGeneration = nextGeneration
        return nextGeneration
    }

    fun refreshIfStale() {
        if (refreshJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (lastRefreshCompletedAtMs <= 0L) return
        if (now - lastRefreshCompletedAtMs < BACKGROUND_REFRESH_DEBOUNCE_MS) return
        refresh(forceForegroundLoading = false)
    }

    private fun prepareForRefresh(showForegroundLoading: Boolean) {
        isForegroundLoading = showForegroundLoading
        if (!showForegroundLoading) {
            _uiState.update { current -> current.copy(isRefreshing = true) }
            return
        }

        _uiState.update { current ->
            current.copy(
                isRefreshing = true,
                hero = current.hero.copy(
                    isLoading = true,
                    statusMessage = current.hero.statusMessage.ifBlank { "Loading featured content..." },
                ),
                sections = current.sections.ifEmpty { defaultLoadingSections() }.map(::markSectionLoading),
            )
        }
    }

    private fun hasLoadedHomeContent(): Boolean {
        val current = _uiState.value
        return current.hero.items.isNotEmpty() || current.sections.isNotEmpty() || current.headerPills.isNotEmpty()
    }

    private fun isCurrentRefresh(generation: Long): Boolean {
        return refreshGeneration == generation
    }

    private suspend fun loadPrimarySnapshot(generation: Long): HomePrimarySnapshot {
        val feedLoadStartedAt = System.currentTimeMillis()
        val primaryFeedResult =
            try {
                withContext(Dispatchers.IO) {
                    homeCatalogService.loadPrimaryHomeFeed(heroLimit = 10)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Primary home feed refresh failed", error)
                delayForMinimumSkeletonVisibility(feedLoadStartedAt)
                val message = error.message ?: "Failed to load home feed."
                return HomePrimarySnapshot(
                    hero = HeroState(items = emptyList(), isLoading = false, statusMessage = message),
                    catalogStatusMessage = message,
                )
            }

        delayForMinimumSkeletonVisibility(feedLoadStartedAt)
        val heroItems = primaryFeedResult.heroResult.items
        val allSections = primaryFeedResult.sections
        val headerPills =
            allSections
                .asSequence()
                .filter { it.presentation == HomeCatalogPresentation.PILL }
                .filter { it.displayTitle.trim().isNotEmpty() }
                .distinctBy { it.key }
                .toList()
        val catalogSections =
            allSections
                .asSequence()
                .filter { it.presentation != HomeCatalogPresentation.PILL }
                .map { section ->
                    HomeCatalogSectionUi(
                        section = section,
                        items = section.previewItems,
                        isLoading = false,
                    )
                }
                .toList()

        val selectedId =
            primarySnapshot.hero.selectedId?.takeIf { id ->
                heroItems.any { it.id == id }
            } ?: heroItems.firstOrNull()?.id

        return HomePrimarySnapshot(
            hero = HeroState(
                items = heroItems,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = primaryFeedResult.heroResult.statusMessage,
            ),
            headerPills = headerPills,
            catalogSections = catalogSections,
            catalogStatusMessage = primaryFeedResult.sectionsStatusMessage,
        )
    }

    private suspend fun loadWatchActivitySnapshot(
        generation: Long,
        enrichMetadata: Boolean,
    ): HomeWatchActivitySnapshot {
        val sectionLoadStartedAt = System.currentTimeMillis()
        val continueWatchingResult =
            try {
                withContext(Dispatchers.IO) {
                    val suppressionMap = suppressionStore.read()
                    suppressedItemsByKey = suppressionMap

                    coroutineScope {
                        val authStateDeferred = async { watchHistoryService.authState() }
                        val watchHistoryDeferred = async { watchHistoryService.listLocalHistory(limit = 40) }

                        val authState = authStateDeferred.await()
                        val selectedSource =
                            when {
                                authState.traktAuthenticated -> WatchProvider.TRAKT
                                authState.simklAuthenticated -> WatchProvider.SIMKL
                                else -> WatchProvider.LOCAL
                            }
                        val providerContinueWatchingDeferred = async {
                            loadProviderContinueWatching(selectedSource)
                        }

                        val filteredEntries =
                            applySuppressionFilter(
                                entries = watchHistoryDeferred.await().entries,
                                suppressionMap = suppressionMap,
                            )
                        val providerResult = providerContinueWatchingDeferred.await()
                        val filteredProviderResult =
                            providerResult.copy(
                                entries = applyProviderSuppressionFilter(providerResult.entries, suppressionMap),
                            )

                        homeWatchActivityService.loadWatchActivity(
                            selectedSource = selectedSource,
                            localEntries = filteredEntries,
                            providerResult = filteredProviderResult,
                            limit = 20,
                            enrichMetadata = enrichMetadata,
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Watch activity refresh failed", error)
                delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
                val message = error.message ?: "Failed to load continue watching."
                return HomeWatchActivitySnapshot(
                    continueWatching = HomeWideRailSectionUi(
                        key = "continueWatching",
                        title = "Continue Watching",
                        kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                        isLoading = false,
                        statusMessage = message,
                    ),
                    upNext = HomeWideRailSectionUi(
                        key = "upNext",
                        title = "Up Next",
                        kind = HomeWideRailSectionKind.UP_NEXT,
                        isLoading = false,
                    ),
                )
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)

        val nowMs = System.currentTimeMillis()
        val railItems = continueWatchingResult.items
        val continueWatchingItems = railItems.filterNot { it.isUpNextPlaceholder }
        val upNextItems = railItems.filter { it.isUpNextPlaceholder }

        return HomeWatchActivitySnapshot(
            continueWatching = HomeWideRailSectionUi(
                key = "continueWatching",
                title = "Continue Watching",
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                items = continueWatchingItems.map { item -> item.toWideRailItem(nowMs) },
                isLoading = false,
                statusMessage =
                    continueWatchingResult.statusMessage.takeIf {
                        continueWatchingItems.isNotEmpty() || continueWatchingResult.isError
                    }.orEmpty(),
            ),
            upNext = HomeWideRailSectionUi(
                key = "upNext",
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
                items = upNextItems.map { item -> item.toWideRailItem(nowMs) },
                isLoading = false,
            ),
        )
    }

    private suspend fun loadThisWeekSection(generation: Long): HomeWideRailSectionUi? {
        val sectionLoadStartedAt = System.currentTimeMillis()
        val thisWeekResult =
            try {
                withContext(Dispatchers.IO) {
                    calendarService.loadThisWeek(System.currentTimeMillis())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "This week refresh failed", error)
                delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
                return HomeWideRailSectionUi(
                    key = "thisWeek",
                    title = "This Week",
                    kind = HomeWideRailSectionKind.THIS_WEEK,
                    isLoading = false,
                    statusMessage = error.message ?: "Failed to load this week.",
                )
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)

        return HomeWideRailSectionUi(
            key = "thisWeek",
            title = "This Week",
            kind = HomeWideRailSectionKind.THIS_WEEK,
            items = thisWeekResult.items.map { item -> item.toWideRailItem() },
            isLoading = false,
            statusMessage =
                thisWeekResult.statusMessage.takeIf {
                    thisWeekResult.items.isNotEmpty() || thisWeekResult.isError
                }.orEmpty(),
        )
    }

    private suspend fun loadProviderContinueWatching(selectedSource: WatchProvider): ContinueWatchingResult {
        return when (selectedSource) {
            WatchProvider.LOCAL -> ContinueWatchingResult(statusMessage = "")
            WatchProvider.TRAKT, WatchProvider.SIMKL -> {
                watchHistoryService.listContinueWatching(
                    limit = 20,
                    source = selectedSource,
                )
            }
        }
    }

    private suspend fun delayForMinimumSkeletonVisibility(visibleAtMs: Long) {
        val elapsedMs = System.currentTimeMillis() - visibleAtMs
        val remainingMs = SECTION_SKELETON_MIN_DURATION_MS - elapsedMs
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
    }

    fun hideContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        updateWatchActivitySection(item) { current ->
            val remainingItems = current.items.filterNot { it.continueWatchingItem?.id == item.id }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Hidden ${item.title}.",
            )
        }
    }

    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        updateWatchActivitySection(item) { current ->
            val remainingItems = current.items.filterNot { it.continueWatchingItem?.id == item.id }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Removing ${item.title}...",
            )
        }

        viewModelScope.launch {
            val removalResult = withContext(Dispatchers.IO) {
                when {
                    item.isUpNextPlaceholder -> {
                        com.crispy.tv.player.WatchHistoryResult(
                            statusMessage = "Removed ${item.title} from Continue Watching."
                        )
                    }
                    item.provider == WatchProvider.LOCAL -> {
                        if (item.progressPercent < 100.0) {
                            watchHistoryService.removeLocalWatchProgress(item.toPlaybackIdentity())
                        } else {
                            watchHistoryService.unmarkWatched(
                                request = item.toUnmarkRequest(),
                                source = item.provider
                            )
                        }
                    }
                    !item.providerPlaybackId.isNullOrBlank() -> {
                        watchHistoryService.removeFromPlayback(
                            playbackId = item.providerPlaybackId,
                            source = item.provider
                        )
                    }
                    else -> {
                        com.crispy.tv.player.WatchHistoryResult(
                            statusMessage = "Removed ${item.title} from Continue Watching."
                        )
                    }
                }
            }
            updateWatchActivitySection(item) { current ->
                current.copy(
                    statusMessage = removalResult.statusMessage.takeIf { current.items.isNotEmpty() }.orEmpty(),
                )
            }
        }
    }

    private fun applySuppressionFilter(
        entries: List<WatchHistoryEntry>,
        suppressionMap: MutableMap<String, Long>? = suppressedItemsByKey,
    ): List<WatchHistoryEntry> {
        if (entries.isEmpty()) return emptyList()
        val activeSuppressionMap = suppressionMap ?: return entries

        var updated = false
        val filtered = mutableListOf<WatchHistoryEntry>()
        entries.forEach { entry ->
            val episodeKey = continueWatchingKey(entry)
            val contentKey = continueWatchingContentKey(entry)

            val episodeSuppressedAt = activeSuppressionMap[episodeKey]
            val contentSuppressedAt = activeSuppressionMap[contentKey]

            val suppressedAt = when {
                episodeSuppressedAt == null -> contentSuppressedAt
                contentSuppressedAt == null -> episodeSuppressedAt
                else -> maxOf(episodeSuppressedAt, contentSuppressedAt)
            }

            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.watchedAtEpochMs > suppressedAt) {
                if (episodeSuppressedAt != null && entry.watchedAtEpochMs > episodeSuppressedAt) {
                    activeSuppressionMap.remove(episodeKey)
                    updated = true
                }
                if (contentSuppressedAt != null && entry.watchedAtEpochMs > contentSuppressedAt) {
                    activeSuppressionMap.remove(contentKey)
                    updated = true
                }
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(activeSuppressionMap)
        }

        return filtered
    }

    private fun applyProviderSuppressionFilter(
        entries: List<ContinueWatchingEntry>,
        suppressionMap: MutableMap<String, Long>? = suppressedItemsByKey,
    ): List<ContinueWatchingEntry> {
        if (entries.isEmpty()) return emptyList()
        val activeSuppressionMap = suppressionMap ?: return entries

        var updated = false
        val filtered = mutableListOf<ContinueWatchingEntry>()
        entries.forEach { entry ->
            val key = continueWatchingContentKey(entry)
            val suppressedAt = activeSuppressionMap[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.lastUpdatedEpochMs > suppressedAt) {
                activeSuppressionMap.remove(key)
                updated = true
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(activeSuppressionMap)
        }

        return filtered
    }

    private fun ContinueWatchingItem.toPlaybackIdentity(): PlaybackIdentity {
        val contentType =
            when (type.lowercase(Locale.US)) {
                "series" -> MetadataLabMediaType.SERIES
                else -> MetadataLabMediaType.MOVIE
            }
        val normalizedImdb = contentId.trim().lowercase(Locale.US).takeIf { it.startsWith("tt") }

        return PlaybackIdentity(
            imdbId = normalizedImdb,
            tmdbId = null,
            contentType = contentType,
            season = season,
            episode = episode,
            title = title,
            year = null,
            showTitle = if (contentType == MetadataLabMediaType.SERIES) title else null,
            showYear = null,
        )
    }

    private fun suppressKeys(vararg keys: String) {
        val suppressionMap = suppressedItemsByKey ?: mutableMapOf<String, Long>().also { suppressedItemsByKey = it }
        val now = System.currentTimeMillis()
        keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { key -> suppressionMap[key] = now }
        suppressionStore.write(suppressionMap)
    }

    private fun updateWatchActivitySection(
        item: ContinueWatchingItem,
        transform: (HomeWideRailSectionUi) -> HomeWideRailSectionUi,
    ) {
        watchActivitySnapshot =
            if (item.isUpNextPlaceholder) {
                watchActivitySnapshot.copy(
                    upNext = watchActivitySnapshot.upNext?.let(transform),
                )
            } else {
                watchActivitySnapshot.copy(
                    continueWatching = watchActivitySnapshot.continueWatching?.let(transform),
                )
            }
        publishUiState(isRefreshing = _uiState.value.isRefreshing)
    }

    private fun publishUiState(isRefreshing: Boolean) {
        val newState =
            HomeUiState(
                isRefreshing = isRefreshing,
                headerPills = primarySnapshot.headerPills,
                hero = primarySnapshot.hero,
                sections = buildHomeSections(),
            )
        if (_uiState.value != newState) {
            _uiState.value = newState
        }
    }

    private fun buildHomeSections(): List<HomeContentSectionUi> {
        val sections = mutableListOf<HomeContentSectionUi>()
        watchActivitySnapshot.continueWatching?.takeIf { it.isVisible() }?.let(sections::add)
        watchActivitySnapshot.upNext?.takeIf { it.isVisible() }?.let(sections::add)
        thisWeekSection?.takeIf { it.isVisible() }?.let(sections::add)
        if (primarySnapshot.catalogSections.isEmpty() && primarySnapshot.catalogStatusMessage.isNotBlank()) {
            sections += HomeStatusSectionUi(
                key = "catalogStatus",
                statusMessage = primarySnapshot.catalogStatusMessage,
            )
        }
        sections += groupCatalogSections(primarySnapshot.catalogSections)
        return sections
    }

    private fun defaultLoadingSections(): List<HomeContentSectionUi> {
        return listOf(
            HomeWideRailSectionUi(
                key = "continueWatching",
                title = "Continue Watching",
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                isLoading = true,
            ),
            HomeWideRailSectionUi(
                key = "upNext",
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
                isLoading = true,
            ),
            HomeWideRailSectionUi(
                key = "thisWeek",
                title = "This Week",
                kind = HomeWideRailSectionKind.THIS_WEEK,
                isLoading = true,
            ),
        )
    }

    private fun groupCatalogSections(catalogSections: List<HomeCatalogSectionUi>): List<HomeContentSectionUi> {
        if (catalogSections.isEmpty()) {
            return emptyList()
        }

        val sections = mutableListOf<HomeContentSectionUi>()
        var index = 0
        while (index < catalogSections.size) {
            val sectionUi = catalogSections[index]
            if (sectionUi.section.presentation == HomeCatalogPresentation.COLLECTION_SHELF) {
                val groupedSections = mutableListOf<HomeCatalogSectionUi>()
                while (
                    index < catalogSections.size &&
                    catalogSections[index].section.presentation == HomeCatalogPresentation.COLLECTION_SHELF
                ) {
                    groupedSections += catalogSections[index]
                    index += 1
                }
                sections += HomeCollectionShelfSectionUi(
                    key = groupedSections.joinToString(separator = ":", prefix = "collections:") { it.section.key },
                    sectionUis = groupedSections,
                )
            } else {
                sections += HomeCatalogRowSectionUi(
                    key = sectionUi.section.key,
                    sectionUi = sectionUi,
                )
                index += 1
            }
        }
        return sections
    }

    private fun markSectionLoading(section: HomeContentSectionUi): HomeContentSectionUi {
        return when (section) {
            is HomeCatalogRowSectionUi -> section.copy(
                sectionUi = section.sectionUi.copy(
                    items = section.sectionUi.items.ifEmpty { section.sectionUi.section.previewItems },
                    isLoading = true,
                )
            )

            is HomeCollectionShelfSectionUi -> section.copy(
                sectionUis =
                    section.sectionUis.map { sectionUi ->
                        sectionUi.copy(
                            items = sectionUi.items.ifEmpty { sectionUi.section.previewItems },
                            isLoading = true,
                        )
                    }
            )

            is HomeStatusSectionUi -> section
            is HomeWideRailSectionUi -> section.copy(isLoading = true)
        }
    }

    private fun HomeWideRailSectionUi.isVisible(): Boolean {
        return isLoading || items.isNotEmpty() || statusMessage.isNotBlank()
    }
}

private fun ContinueWatchingItem.toWideRailItem(nowMs: Long): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${id}",
        title = title,
        subtitle = buildHomeWatchActivitySubtitle(nowMs),
        imageUrl = backdropUrl ?: posterUrl,
        progressFraction = progressPercent.takeIf { it > 0.0 }?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() },
        kind = HomeWideRailItemKind.WATCH_ACTIVITY,
        continueWatchingItem = this,
    )
}

private fun CalendarEpisodeItem.toWideRailItem(): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${id}",
        title = seriesName,
        subtitle = buildCalendarSecondaryText(),
        imageUrl = thumbnailUrl ?: backdropUrl ?: posterUrl,
        badgeLabel = buildCalendarBadgeLabel(),
        kind = HomeWideRailItemKind.CALENDAR_EPISODE,
        calendarEpisodeItem = this,
    )
}

private fun CalendarEpisodeItem.buildCalendarBadgeLabel(): String? {
    if (isReleased) return "Released"
    return try {
        val date = LocalDate.parse(releaseDate.take(10))
        "${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}"
    } catch (_: Exception) {
        null
    }
}

private fun CalendarEpisodeItem.buildCalendarSecondaryText(): String {
    val supportingText = when {
        isGroup -> "${episodeCount} new episodes"
        !episodeTitle.isNullOrBlank() -> episodeTitle
        !overview.isNullOrBlank() -> overview
        else -> null
    }?.trim()

    val episodeLabel =
        if (episodeRange != null) {
            "S${season} ${episodeRange}"
        } else {
            "S${season} E${episode}"
        }
    return if (supportingText.isNullOrBlank()) {
        episodeLabel
    } else {
        "$episodeLabel - $supportingText"
    }
}

class ContinueWatchingSuppressionStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): MutableMap<String, Long> {
        val raw = preferences.getString(KEY_ITEM_SUPPRESSIONS, null) ?: return mutableMapOf()
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, Long>()
        payload.keys().forEach { key ->
            val timestamp = payload.optLong(key)
            if (timestamp > 0L) {
                map[key] = timestamp
            }
        }
        return map
    }

    fun write(value: Map<String, Long>) {
        if (value.isEmpty()) {
            preferences.edit().remove(KEY_ITEM_SUPPRESSIONS).apply()
            return
        }

        val payload = JSONObject()
        value.forEach { (key, timestamp) ->
            payload.put(key, timestamp)
        }
        preferences.edit().putString(KEY_ITEM_SUPPRESSIONS, payload.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "home_continue_watching"
        private const val KEY_ITEM_SUPPRESSIONS = "suppressed_items"
    }
}

private fun continueWatchingKey(entry: WatchHistoryEntry): String {
    val seasonPart = entry.season?.toString() ?: "-"
    val episodePart = entry.episode?.toString() ?: "-"
    return "${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:$seasonPart:$episodePart"
}

private fun continueWatchingContentKey(entry: WatchHistoryEntry): String {
    val type = if (entry.contentType == MetadataLabMediaType.SERIES) "series" else "movie"
    return "$type:${entry.contentId.lowercase(Locale.US)}"
}

private fun continueWatchingContentKey(entry: ContinueWatchingEntry): String {
    val type = if (entry.contentType == MetadataLabMediaType.SERIES) "series" else "movie"
    return "$type:${entry.contentId.lowercase(Locale.US)}"
}

private fun continueWatchingContentKey(type: String, contentId: String): String {
    return "${type.trim().lowercase(Locale.US)}:${contentId.trim().lowercase(Locale.US)}"
}

private fun ContinueWatchingItem.buildHomeWatchActivitySubtitle(nowMs: Long): String {
    val seasonEpisode =
        if (type.equals("series", ignoreCase = true) && season != null && episode != null) {
            String.format(Locale.US, "S%02d:E%02d", season, episode)
        } else {
            null
        }
    val relativeWatched =
        DateUtils.getRelativeTimeSpanString(
            watchedAtEpochMs,
            nowMs,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

    return listOfNotNull(seasonEpisode, relativeWatched).joinToString(separator = " • ")
}

private fun ContinueWatchingItem.toUnmarkRequest(): WatchHistoryRequest {
    return WatchHistoryRequest(
        contentId = contentId,
        contentType = type.toMetadataLabMediaType(),
        title = title,
        season = season,
        episode = episode
    )
}

private fun String.toMetadataLabMediaType(): MetadataLabMediaType {
    return if (equals("series", ignoreCase = true)) {
        MetadataLabMediaType.SERIES
    } else {
        MetadataLabMediaType.MOVIE
    }
}
