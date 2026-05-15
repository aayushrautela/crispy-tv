package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.WatchHistoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val statusMessage: String = "Loading featured content...",
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = "",
)

data class HomePrimarySnapshot(
    val hero: HeroState = HeroState(),
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
    val catalogStatusMessage: String = "",
)

data class HomeWatchActivitySnapshot(
    val continueWatching: HomeWideRailSectionUi = defaultWideRailSection(
        key = CONTINUE_WATCHING_SECTION_KEY,
        title = "Continue Watching",
        kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
    ),
    val upNext: HomeWideRailSectionUi = defaultWideRailSection(
        key = UP_NEXT_SECTION_KEY,
        title = "Up Next",
        kind = HomeWideRailSectionKind.UP_NEXT,
    ),
)

internal const val CONTINUE_WATCHING_SECTION_KEY = "continueWatching"
internal const val UP_NEXT_SECTION_KEY = "upNext"
internal const val THIS_WEEK_SECTION_KEY = "thisWeek"

@Immutable
data class HomeUiState(
    val isRefreshing: Boolean = false,
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val heroState: HeroState = HeroState(),
    val layoutState: HomeLayoutState = HomeLayoutState(),
    val wideRailSections: Map<String, HomeWideRailSectionUi> = defaultWideRailSections(),
    val catalogSections: Map<String, HomeCatalogSectionUi> = emptyMap(),
)

class HomeViewModel internal constructor(
    private val refreshCoordinator: HomeRefreshCoordinator,
    private val watchHistoryService: WatchHistoryService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val BACKGROUND_REFRESH_DEBOUNCE_MS = 60_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                        val suppressionStore = ContinueWatchingSuppressionStore(appContext)
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            refreshCoordinator = HomeRefreshCoordinator(
                                recommendationCatalogService = SupabaseServicesProvider.recommendationCatalogService(appContext),
                                homeWatchActivityService = HomeWatchActivityService(),
                                watchHistoryService = watchHistoryService,
                                calendarService =
                                    CalendarService(
                                        backendClient = BackendServicesProvider.backendClient(appContext),
                                        backendContextResolver = BackendContextResolverProvider.get(appContext),
                                    ),
                                suppressionStore = suppressionStore,
                            ),
                            watchHistoryService = watchHistoryService,
                            suppressionStore = suppressionStore,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    private val _headerPillsState = MutableStateFlow<List<CatalogSectionRef>>(emptyList())
    private val _heroState = MutableStateFlow(HeroState())
    private val _layoutState = MutableStateFlow(HomeLayoutState())
    private val _wideRailSectionsState = MutableStateFlow(defaultWideRailSections())
    private val _catalogSectionsState = MutableStateFlow<Map<String, HomeCatalogSectionUi>>(emptyMap())

    val uiState: StateFlow<HomeUiState> = combine(
        listOf(
            _isRefreshing,
            _headerPillsState,
            _heroState,
            _layoutState,
            _wideRailSectionsState,
            _catalogSectionsState,
        ),
    ) { arr ->
        HomeUiState(
            isRefreshing = arr[0] as Boolean,
            headerPills = arr[1] as List<CatalogSectionRef>,
            heroState = arr[2] as HeroState,
            layoutState = arr[3] as HomeLayoutState,
            wideRailSections = arr[4] as Map<String, HomeWideRailSectionUi>,
            catalogSections = arr[5] as Map<String, HomeCatalogSectionUi>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private var catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta> = emptyList()
    private var catalogStatusMessage: String = ""
    private var watchActivitySnapshot = HomeWatchActivitySnapshot()
    private var thisWeekSection = defaultWideRailSection(
        key = THIS_WEEK_SECTION_KEY,
        title = "This Week",
        kind = HomeWideRailSectionKind.THIS_WEEK,
    )

    private var suppressedItemsByKey: MutableMap<String, Long>? = null
    @Volatile
    private var refreshGeneration: Long = 0L
    private var refreshJob: Job? = null
    private var backgroundRefreshJob: Job? = null
    private var refreshPending: Boolean = false
    private var lastRefreshCompletedAtMs: Long = 0L

    fun ensureLoaded() {
        if (hasLoadedHomeContent() || refreshJob?.isActive == true) return
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
        prepareForRefresh(showForegroundLoading)

        refreshJob =
            viewModelScope.launch {
                val refreshCompletion = CompletableDeferred<Unit>()
                try {
                    if (showForegroundLoading) {
                        val cachedPrimarySnapshot = runCatching { refreshCoordinator.loadCachedPrimarySnapshot() }.getOrNull()
                        if (cachedPrimarySnapshot != null && isCurrentRefresh(currentRefreshGeneration)) {
                            applyPrimarySnapshot(cachedPrimarySnapshot)
                        }
                    }

                    coroutineScope {
                        async {
                            val snapshot = runCatching { refreshCoordinator.loadPrimarySnapshot() }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                Log.w(TAG, "Primary home feed refresh failed", error)
                                HomePrimarySnapshot(
                                    hero = HeroState(
                                        items = emptyList(),
                                        isLoading = false,
                                        statusMessage = error.message ?: "Failed to load home feed.",
                                    ),
                                    catalogStatusMessage = error.message ?: "Failed to load home feed.",
                                )
                            }
                            if (isCurrentRefresh(currentRefreshGeneration)) {
                                applyPrimarySnapshot(snapshot)
                            }
                        }
                        async {
                            val snapshot = runCatching { refreshCoordinator.loadWatchActivitySnapshot() }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                Log.w(TAG, "Watch activity refresh failed", error)
                                HomeWatchActivitySnapshot(
                                    continueWatching = defaultWideRailSection(
                                        key = CONTINUE_WATCHING_SECTION_KEY,
                                        title = "Continue Watching",
                                        kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                                    ).copy(
                                        isLoading = false,
                                        statusMessage = error.message ?: "Failed to load continue watching.",
                                    ),
                                    upNext = defaultWideRailSection(
                                        key = UP_NEXT_SECTION_KEY,
                                        title = "Up Next",
                                        kind = HomeWideRailSectionKind.UP_NEXT,
                                    ).copy(isLoading = false),
                                )
                            }
                            if (isCurrentRefresh(currentRefreshGeneration)) {
                                applyWatchActivitySnapshot(snapshot)
                            }
                        }
                        async {
                            val section = runCatching { refreshCoordinator.loadThisWeekSection() }.getOrElse { error ->
                                if (error is CancellationException) throw error
                                Log.w(TAG, "This week refresh failed", error)
                                defaultWideRailSection(
                                    key = THIS_WEEK_SECTION_KEY,
                                    title = "This Week",
                                    kind = HomeWideRailSectionKind.THIS_WEEK,
                                ).copy(
                                    isLoading = false,
                                    statusMessage = error.message ?: "Failed to load this week.",
                                )
                            }
                            if (isCurrentRefresh(currentRefreshGeneration)) {
                                applyThisWeekSection(section)
                            }
                        }
                    }
                } finally {
                    if (isCurrentRefresh(currentRefreshGeneration)) {
                        _isRefreshing.value = false
                        lastRefreshCompletedAtMs = System.currentTimeMillis()
                    }
                    refreshCompletion.complete(Unit)

                    refreshJob = null
                    if (refreshPending) {
                        refreshPending = false
                        refresh()
                    } else if (isCurrentRefresh(currentRefreshGeneration)) {
                        backgroundRefreshJob =
                            viewModelScope.launch {
                                refreshCompletion.await()
                                val refreshedSnapshot = runCatching { refreshCoordinator.loadAll() }.getOrNull() ?: return@launch
                                if (!isCurrentRefresh(currentRefreshGeneration)) return@launch

                                applyPrimarySnapshot(refreshedSnapshot.primary)
                                applyWatchActivitySnapshot(refreshedSnapshot.watchActivity)
                                applyThisWeekSection(refreshedSnapshot.thisWeek)
                            }
                    }
                }
            }
    }

    fun refreshIfStale() {
        if (refreshJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (lastRefreshCompletedAtMs <= 0L) return
        if (now - lastRefreshCompletedAtMs < BACKGROUND_REFRESH_DEBOUNCE_MS) return
        refresh(forceForegroundLoading = false)
    }

    fun removeContinueWatchingItem(item: CanonicalContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(item),
        )
        updateWideRailSection(item.sectionKey()) { current ->
            val remainingItems = current.items.filterNot { it.continueWatchingItem?.localKey == item.localKey }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Removing ${item.title}...",
            )
        }

        viewModelScope.launch {
            val removalResult =
                withContext(Dispatchers.IO) {
                    if (item.id.isNotBlank()) {
                        val dismissId = item.id.trim()
                        watchHistoryService.removeFromPlayback(playbackId = dismissId)
                    } else {
                        com.crispy.tv.player.WatchHistoryResult(
                            statusMessage = "Removed ${item.title} from Continue Watching.",
                        )
                    }
                }

            updateWideRailSection(item.sectionKey()) { current ->
                current.copy(
                    statusMessage = removalResult.statusMessage.takeIf { current.items.isNotEmpty() }.orEmpty(),
                )
            }
        }
    }

    private fun beginRefresh(): Long {
        val nextGeneration = refreshGeneration + 1L
        refreshGeneration = nextGeneration
        return nextGeneration
    }

    private fun prepareForRefresh(showForegroundLoading: Boolean) {
        _isRefreshing.value = true
        if (!showForegroundLoading) {
            return
        }

        _heroState.update { current ->
            current.copy(
                isLoading = true,
                statusMessage = current.statusMessage.ifBlank { "Loading featured content..." },
            )
        }
        _wideRailSectionsState.value =
            _wideRailSectionsState.value.mapValues { (_, section) ->
                section.copy(isLoading = true)
            }
        _catalogSectionsState.update { current ->
            current.mapValues { (_, sectionUi) ->
                sectionUi.copy(
                    items = sectionUi.items.ifEmpty { sectionUi.section.previewItems },
                    isLoading = true,
                )
            }
        }
        updateLayout()
    }

    private fun hasLoadedHomeContent(): Boolean {
        return _heroState.value.items.isNotEmpty() ||
            _headerPillsState.value.isNotEmpty() ||
            _layoutState.value.blocks.isNotEmpty()
    }

    private fun isCurrentRefresh(generation: Long): Boolean {
        return refreshGeneration == generation
    }

    private fun applyPrimarySnapshot(snapshot: HomePrimarySnapshot) {
        _heroState.value = snapshot.hero
        _headerPillsState.value = snapshot.headerPills
        catalogStatusMessage = snapshot.catalogStatusMessage

        catalogSectionLayoutMeta =
            snapshot.catalogSections.map { sectionUi ->
                CatalogSectionLayoutMeta(
                    key = sectionUi.section.key,
                    layout = sectionUi.section.layout,
                )
            }
        _catalogSectionsState.value = snapshot.catalogSections.associateBy { it.section.key }
        updateLayout()
    }

    private fun applyWatchActivitySnapshot(snapshot: HomeWatchActivitySnapshot) {
        watchActivitySnapshot = snapshot
        _wideRailSectionsState.update { current ->
            current + mapOf(
                snapshot.continueWatching.key to snapshot.continueWatching,
                snapshot.upNext.key to snapshot.upNext,
            )
        }
        updateLayout()
    }

    private fun applyThisWeekSection(section: HomeWideRailSectionUi) {
        thisWeekSection = section
        _wideRailSectionsState.update { current -> current + (section.key to section) }
        updateLayout()
    }

    private fun updateWideRailSection(
        key: String,
        transform: (HomeWideRailSectionUi) -> HomeWideRailSectionUi,
    ) {
        _wideRailSectionsState.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }

        watchActivitySnapshot =
            when (key) {
                CONTINUE_WATCHING_SECTION_KEY -> watchActivitySnapshot.copy(
                    continueWatching = _wideRailSectionsState.value[key] ?: watchActivitySnapshot.continueWatching,
                )
                UP_NEXT_SECTION_KEY -> watchActivitySnapshot.copy(
                    upNext = _wideRailSectionsState.value[key] ?: watchActivitySnapshot.upNext,
                )
                else -> watchActivitySnapshot
            }
        if (key == THIS_WEEK_SECTION_KEY) {
            thisWeekSection = _wideRailSectionsState.value[key] ?: thisWeekSection
        }
        updateLayout()
    }

    private fun updateLayout() {
        _layoutState.value = buildHomeLayoutState(
            wideRails = _wideRailSectionsState.value,
            catalogSectionLayoutMeta = catalogSectionLayoutMeta,
            catalogStatusMessage = catalogStatusMessage,
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
}

private fun defaultWideRailSections(): Map<String, HomeWideRailSectionUi> {
    return linkedMapOf(
        CONTINUE_WATCHING_SECTION_KEY to
            defaultWideRailSection(
                key = CONTINUE_WATCHING_SECTION_KEY,
                title = "Continue Watching",
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
            ),
        UP_NEXT_SECTION_KEY to
            defaultWideRailSection(
                key = UP_NEXT_SECTION_KEY,
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
            ),
        THIS_WEEK_SECTION_KEY to
            defaultWideRailSection(
                key = THIS_WEEK_SECTION_KEY,
                title = "This Week",
                kind = HomeWideRailSectionKind.THIS_WEEK,
            ),
    )
}

internal fun defaultWideRailSection(
    key: String,
    title: String,
    kind: HomeWideRailSectionKind,
): HomeWideRailSectionUi {
    return HomeWideRailSectionUi(
        key = key,
        title = title,
        kind = kind,
        isLoading = true,
    )
}

internal fun HomeWideRailSectionUi.isVisible(): Boolean {
    return isLoading || items.isNotEmpty() || statusMessage.isNotBlank()
}

private fun CanonicalContinueWatchingItem.sectionKey(): String {
    return CONTINUE_WATCHING_SECTION_KEY
}

internal fun CanonicalContinueWatchingItem.toWideRailItem(nowMs: Long): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${localKey}",
        title = title,
        subtitle = buildHomeWatchActivitySubtitle(nowMs),
        imageUrl = backdropUrl ?: posterUrl,
        progressFraction = progressPercent.takeIf { it > 0.0 }?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() },
        kind = HomeWideRailItemKind.WATCH_ACTIVITY,
        continueWatchingItem = this,
    )
}

internal fun CalendarEpisodeItem.toWideRailItem(): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${localKey}",
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
    val normalizedReleaseDate = releaseDate ?: return null
    return try {
        val date = LocalDate.parse(normalizedReleaseDate.take(10))
        "${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}"
    } catch (_: Exception) {
        null
    }
}

private fun CalendarEpisodeItem.buildCalendarSecondaryText(): String {
    val supportingText =
        when {
            isGroup -> "${episodeCount} new episodes"
            !episodeTitle.isNullOrBlank() -> episodeTitle
            !overview.isNullOrBlank() -> overview
            else -> null
        }?.trim()

    val episodeLabel =
        when {
            episodeRange != null && season != null -> "S${season} ${episodeRange}"
            season != null && episode != null -> "S${season} E${episode}"
            episodeRange != null -> episodeRange
            episode != null -> "Episode ${episode}"
            releaseDate != null -> releaseDate.take(10)
            else -> "Upcoming episode"
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

internal fun continueWatchingContentKey(entry: CanonicalContinueWatchingItem): String {
    return entry.titleMediaKey.trim().ifBlank { entry.id.trim().lowercase(Locale.US) }
}

private fun CanonicalContinueWatchingItem.buildHomeWatchActivitySubtitle(nowMs: Long): String {
    val seasonEpisode =
        if ((type.equals("series", ignoreCase = true) || type.equals("anime", ignoreCase = true)) && season != null && episode != null) {
            String.format(Locale.US, "S%02d:E%02d", season, episode)
        } else {
            null
        }
    val episodeName = episodeTitle?.takeIf { it.isNotBlank() }
    val relativeWatched =
        DateUtils.getRelativeTimeSpanString(
            watchedAtEpochMs,
            nowMs,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()

    return listOfNotNull(seasonEpisode, episodeName, relativeWatched).joinToString(separator = " • ")
}
