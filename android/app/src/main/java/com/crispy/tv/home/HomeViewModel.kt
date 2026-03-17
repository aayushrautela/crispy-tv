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
import com.crispy.tv.catalog.CatalogPageResult
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
import java.util.LinkedHashMap
import java.util.Locale

@Immutable
data class HeroState(
    val items: List<HomeHeroItem> = emptyList(),
    val selectedId: String? = null,
    val isLoading: Boolean = true,
    val statusMessage: String = "Loading featured content..."
) {
    val selected: HomeHeroItem?
        get() = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
}

@Immutable
data class HomeWatchActivityItemUi(
    val item: ContinueWatchingItem,
    val subtitle: String,
)

@Immutable
data class HomeWatchActivityRailState(
    val items: List<HomeWatchActivityItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = "",
    val isError: Boolean = false,
) {
    val isVisible: Boolean
        get() = isLoading || items.isNotEmpty() || isError
}

@Immutable
data class ThisWeekState(
    val items: List<CalendarEpisodeItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = "",
    val isError: Boolean = false,
) {
    val isVisible: Boolean
        get() = isLoading || items.isNotEmpty() || isError
}

@Immutable
data class HomeCatalogStatusState(
    val statusMessage: String = "",
    val hasCatalogSections: Boolean = false,
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
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

    private val _heroState = MutableStateFlow(HeroState())
    val heroState: StateFlow<HeroState> = _heroState.asStateFlow()

    private val _continueWatchingState = MutableStateFlow(HomeWatchActivityRailState())
    val continueWatchingState: StateFlow<HomeWatchActivityRailState> = _continueWatchingState.asStateFlow()

    private val _upNextState = MutableStateFlow(HomeWatchActivityRailState())
    val upNextState: StateFlow<HomeWatchActivityRailState> = _upNextState.asStateFlow()

    private val _thisWeekState = MutableStateFlow(ThisWeekState())
    val thisWeekState: StateFlow<ThisWeekState> = _thisWeekState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _pillSections = MutableStateFlow<List<CatalogSectionRef>>(emptyList())
    val pillSections: StateFlow<List<CatalogSectionRef>> = _pillSections.asStateFlow()

    private val _contentSections = MutableStateFlow<List<CatalogSectionRef>>(emptyList())
    val contentSections: StateFlow<List<CatalogSectionRef>> = _contentSections.asStateFlow()

    private val _catalogStatusState = MutableStateFlow(HomeCatalogStatusState())
    val catalogStatusState: StateFlow<HomeCatalogStatusState> = _catalogStatusState.asStateFlow()

    private val _isForegroundLoading = MutableStateFlow(true)
    val isForegroundLoading: StateFlow<Boolean> = _isForegroundLoading.asStateFlow()

    private val catalogSectionStates = LinkedHashMap<String, MutableStateFlow<HomeCatalogSectionUi>>()

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
                try {
                    coroutineScope {
                        val jobs = listOf(
                            launch { refreshPrimaryFeed(currentRefreshGeneration) },
                            launch { refreshWatchActivity(currentRefreshGeneration) },
                        )
                        jobs.forEach { it.join() }
                    }
                } finally {
                    if (isCurrentRefresh(currentRefreshGeneration)) {
                        _isRefreshing.value = false
                        _isForegroundLoading.value = false
                        lastRefreshCompletedAtMs = System.currentTimeMillis()
                    }
                    refreshJob = null
                    if (refreshPending) {
                        refreshPending = false
                        refresh()
                    } else if (isCurrentRefresh(currentRefreshGeneration)) {
                        backgroundRefreshJob =
                            viewModelScope.launch {
                                refreshWatchActivity(
                                    generation = currentRefreshGeneration,
                                    enrichMetadata = true,
                                )
                                refreshThisWeek(currentRefreshGeneration)
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
        _isRefreshing.value = true
        _isForegroundLoading.value = showForegroundLoading
        if (!showForegroundLoading) {
            _continueWatchingState.update { current -> current.copy(isError = false) }
            _upNextState.update { current -> current.copy(isError = false) }
            _thisWeekState.update { current -> current.copy(isError = false) }
            return
        }

        _heroState.update { current ->
            current.copy(
                isLoading = true,
                statusMessage = current.statusMessage.ifBlank { "Loading featured content..." },
            )
        }
        _continueWatchingState.update { current ->
            current.copy(
                isLoading = true,
                isError = false,
            )
        }
        _upNextState.update { current ->
            current.copy(
                isLoading = true,
                isError = false,
            )
        }
        _thisWeekState.update { current ->
            current.copy(
                isLoading = true,
                isError = false,
            )
        }
        catalogSectionStates.values.forEach { state ->
            state.update { current ->
                current.copy(
                    items = if (current.items.isEmpty()) current.section.previewItems else current.items,
                    isLoading = true,
                )
            }
        }
    }

    private fun hasLoadedHomeContent(): Boolean {
        return _heroState.value.items.isNotEmpty() ||
            _continueWatchingState.value.items.isNotEmpty() ||
            _upNextState.value.items.isNotEmpty() ||
            _thisWeekState.value.items.isNotEmpty() ||
            _contentSections.value.isNotEmpty() ||
            _pillSections.value.isNotEmpty()
    }

    private fun isCurrentRefresh(generation: Long): Boolean {
        return refreshGeneration == generation
    }

    private suspend fun refreshPrimaryFeed(generation: Long) {
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
                if (!isCurrentRefresh(generation)) return
                val message = error.message ?: "Failed to load home feed."
                _heroState.value = HeroState(items = emptyList(), isLoading = false, statusMessage = message)
                setCatalogSections(sections = emptyList(), statusMessage = message)
                return
            }

        delayForMinimumSkeletonVisibility(feedLoadStartedAt)
        if (!isCurrentRefresh(generation)) return

        _heroState.update { current ->
            val selectedId =
                current.selectedId?.takeIf { id ->
                    primaryFeedResult.heroResult.items.any { it.id == id }
                } ?: primaryFeedResult.heroResult.items.firstOrNull()?.id
            current.copy(
                items = primaryFeedResult.heroResult.items,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = primaryFeedResult.heroResult.statusMessage,
            )
        }

        setCatalogSections(
            sections = primaryFeedResult.sections,
            statusMessage = primaryFeedResult.sectionsStatusMessage,
        )

        primaryFeedResult.sections.forEach { section ->
            updateCatalogSectionState(
                section = section,
                pageResult = CatalogPageResult(items = section.previewItems),
            )
        }
    }

    private suspend fun refreshWatchActivity(generation: Long) {
        refreshWatchActivity(generation = generation, enrichMetadata = !_isForegroundLoading.value)
    }

    private suspend fun refreshWatchActivity(
        generation: Long,
        enrichMetadata: Boolean,
    ) {
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
                if (!isCurrentRefresh(generation)) return
                val message = error.message ?: "Failed to load continue watching."
                setWatchActivityStates(
                    continueWatching = HomeWatchActivityRailState(
                        isLoading = false,
                        statusMessage = message,
                        isError = true,
                    ),
                    upNext = HomeWatchActivityRailState(isLoading = false),
                )
                return
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
        if (!isCurrentRefresh(generation)) return

        val nowMs = System.currentTimeMillis()
        val railItems = continueWatchingResult.items.map { item -> item.toHomeWatchActivityItemUi(nowMs) }
        val continueWatchingItems = railItems.filterNot { it.item.isUpNextPlaceholder }
        val upNextItems = railItems.filter { it.item.isUpNextPlaceholder }

        setWatchActivityStates(
            continueWatching =
                HomeWatchActivityRailState(
                    items = continueWatchingItems,
                    isLoading = false,
                    statusMessage =
                        continueWatchingResult.statusMessage.takeIf {
                            continueWatchingItems.isNotEmpty() || continueWatchingResult.isError
                        }.orEmpty(),
                    isError = continueWatchingResult.isError,
                ),
            upNext =
                HomeWatchActivityRailState(
                    items = upNextItems,
                    isLoading = false,
                ),
        )
    }

    private suspend fun refreshThisWeek(generation: Long) {
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
                if (!isCurrentRefresh(generation)) return
                setThisWeekState(
                    ThisWeekState(
                        isLoading = false,
                        statusMessage = error.message ?: "Failed to load this week.",
                        isError = true,
                    )
                )
                return
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
        if (!isCurrentRefresh(generation)) return
        setThisWeekState(
            ThisWeekState(
                items = thisWeekResult.items,
                isLoading = false,
                statusMessage =
                    thisWeekResult.statusMessage.takeIf {
                        thisWeekResult.items.isNotEmpty() || thisWeekResult.isError
                    }.orEmpty(),
                isError = thisWeekResult.isError,
            )
        )
    }

    fun catalogSectionState(section: CatalogSectionRef): StateFlow<HomeCatalogSectionUi> {
        return catalogSectionStates.getOrPut(section.key) {
            MutableStateFlow(
                HomeCatalogSectionUi(
                    section = section,
                    items = section.previewItems,
                    isLoading = false,
                )
            )
        }
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
        updateWatchActivityRailState(item) { current ->
            val remainingItems = current.items.filterNot { it.item.id == item.id }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Hidden ${item.title}.",
                isError = false,
            )
        }
    }

    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        updateWatchActivityRailState(item) { current ->
            val remainingItems = current.items.filterNot { it.item.id == item.id }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Removing ${item.title}...",
                isError = false,
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
            updateWatchActivityRailState(item) { current ->
                current.copy(
                    statusMessage = removalResult.statusMessage.takeIf { current.items.isNotEmpty() }.orEmpty(),
                    isError = false,
                )
            }
        }
    }

    fun onHeroSelected(heroId: String) {
        _heroState.update { current ->
            if (current.items.none { it.id == heroId }) {
                current
            } else {
                current.copy(selectedId = heroId)
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

    private fun setWatchActivityStates(
        continueWatching: HomeWatchActivityRailState = _continueWatchingState.value,
        upNext: HomeWatchActivityRailState = _upNextState.value,
    ) {
        _continueWatchingState.value = continueWatching
        _upNextState.value = upNext
    }

    private fun updateWatchActivityRailState(
        item: ContinueWatchingItem,
        transform: (HomeWatchActivityRailState) -> HomeWatchActivityRailState,
    ) {
        if (item.isUpNextPlaceholder) {
            setWatchActivityStates(upNext = transform(_upNextState.value))
        } else {
            setWatchActivityStates(continueWatching = transform(_continueWatchingState.value))
        }
    }

    private fun setThisWeekState(state: ThisWeekState) {
        _thisWeekState.value = state
    }

    private fun setCatalogSections(
        sections: List<CatalogSectionRef>,
        statusMessage: String,
    ) {
        val activeKeys = sections.mapTo(linkedSetOf()) { it.key }
        sections.forEach { section ->
            val sectionState = catalogSectionStates.getOrPut(section.key) {
                MutableStateFlow(
                    HomeCatalogSectionUi(
                        section = section,
                        items = section.previewItems,
                        isLoading = false,
                    )
                )
            }
            val current = sectionState.value
            sectionState.value = current.copy(
                section = section,
                items = section.previewItems.ifEmpty { current.items },
            )
        }
        catalogSectionStates.keys.retainAll(activeKeys)

        _pillSections.value =
            sections
                .asSequence()
                .filter { it.presentation == HomeCatalogPresentation.PILL }
                .filter { it.displayTitle.trim().isNotEmpty() }
                .distinctBy { it.key }
                .toList()
        _contentSections.value = sections.filter { it.presentation != HomeCatalogPresentation.PILL }
        _catalogStatusState.value =
            HomeCatalogStatusState(
                statusMessage = statusMessage,
                hasCatalogSections = sections.isNotEmpty(),
            )
    }

    private fun updateCatalogSectionState(
        section: CatalogSectionRef,
        pageResult: CatalogPageResult,
    ) {
        val sectionState = catalogSectionStates.getOrPut(section.key) {
            MutableStateFlow(
                HomeCatalogSectionUi(
                    section = section,
                    items = section.previewItems,
                    isLoading = false,
                )
            )
        }
        sectionState.value =
            HomeCatalogSectionUi(
                section = section,
                items = pageResult.items.ifEmpty { section.previewItems },
                isLoading = false,
                statusMessage = pageResult.statusMessage,
            )
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

private fun ContinueWatchingItem.toHomeWatchActivityItemUi(nowMs: Long): HomeWatchActivityItemUi {
    return HomeWatchActivityItemUi(
        item = this,
        subtitle = buildHomeWatchActivitySubtitle(nowMs),
    )
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
