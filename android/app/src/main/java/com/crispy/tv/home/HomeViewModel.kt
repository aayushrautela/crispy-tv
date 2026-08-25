package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
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
import com.crispy.tv.watchhistory.sync.WatchSyncSource
import com.crispy.tv.network.AppHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
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
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class HomePrimarySnapshot(
    val hero: HeroState = HeroState(),
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
)

internal const val CONTINUE_WATCHING_SECTION_KEY = "continueWatching"
internal const val UP_NEXT_SECTION_KEY = "upNext"
internal const val THIS_WEEK_SECTION_KEY = "thisWeek"

private const val RAIL_LOAD_ATTEMPTS = 3
private const val RAIL_RETRY_BACKOFF_MS = 400L

@Immutable
data class HomeUiState(
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val heroState: HeroState = HeroState(),
    val layoutState: HomeLayoutState = defaultHomeLayoutState(),
    val wideRailSections: Map<String, HomeWideRailSectionUi> = defaultWideRailSections(),
    val catalogSections: Map<String, HomeCatalogSectionUi> = emptyMap(),
)

class HomeViewModel internal constructor(
    private val appContext: Context,
    private val refreshCoordinator: HomeRefreshCoordinator,
    private val watchHistoryService: WatchHistoryService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                        val suppressionStore = ContinueWatchingSuppressionStore(appContext)
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            appContext = appContext,
                            refreshCoordinator = HomeRefreshCoordinator(
                                homeCatalogService = SupabaseServicesProvider.homeCatalogService(appContext),
                                homeWatchActivityService = HomeWatchActivityService(),
                                watchHistoryService = watchHistoryService,
                                calendarService =
                                    CalendarService(
                                        backendClient = BackendServicesProvider.backendClient(appContext),
                                        backendContextResolver = BackendContextResolverProvider.get(appContext),
                                    ),
                                upNextService =
                                    UpNextService(
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

    private val _state = MutableStateFlow(HomeUiState())

    val uiState: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta> = emptyList()

    private var suppressedItemsByKey: MutableMap<String, Long>? = null
    private var initialLoadJob: Job? = null
    private var watchActivityJob: Job? = null
    private var hasAttemptedInitialLoad = false

    private var watchSyncSource: WatchSyncSource? = null
    private val backendResolver by lazy { BackendContextResolverProvider.get(appContext) }
    private val backendClient by lazy { BackendServicesProvider.backendClient(appContext) }

    init {
        viewModelScope.launch {
            HomeRefreshBus.events.collect { event ->
                when (event) {
                    HomeRefreshEvent.PlaybackEnded, HomeRefreshEvent.WatchlistChanged -> {
                        refreshWatchActivityAndThisWeek()
                    }
                }
            }
        }
    }

    fun ensureLoaded() {
        if (hasAttemptedInitialLoad || initialLoadJob?.isActive == true) return
        hasAttemptedInitialLoad = true
        initialLoadJob =
            viewModelScope.launch {
                runCatching { refreshCoordinator.loadCachedPrimarySnapshot() }
                    .getOrNull()
                    ?.let(::applyPrimarySnapshot)

                coroutineScope {
                    async { loadPrimary() }
                    async { loadContinueWatching() }
                    async { loadUpNext() }
                    async { loadThisWeek() }
                }
                initialLoadJob = null
            }
    }

    fun onHomeVisible() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = backendResolver.resolve() ?: return@launch
            watchSyncSource?.close()
            watchSyncSource =
                WatchSyncSource(
                    httpClient = AppHttp.okHttp(appContext),
                    baseUrl = backendClient.baseUrl,
                    accessToken = context.accessToken,
                    profileId = context.profileId,
                    onRefetch = { refreshWatchActivityAndThisWeek() },
                )
            watchSyncSource?.onSurfaceVisible()
        }
    }

    fun onHomeHidden() {
        watchSyncSource?.onSurfaceHidden()
    }

    override fun onCleared() {
        watchSyncSource?.close()
        watchSyncSource = null
        super.onCleared()
    }

    private fun refreshWatchActivityAndThisWeek() {
        watchActivityJob?.cancel()
        watchActivityJob =
            viewModelScope.launch {
                coroutineScope {
                    async { loadContinueWatching() }
                    async { loadUpNext() }
                    async { loadThisWeek() }
                }
                watchActivityJob = null
            }
    }

    fun removeContinueWatchingItem(item: CanonicalContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(item),
        )
        updateWideRailSection(item.sectionKey()) { current ->
            val ready = current.state as? RailLoadState.Ready ?: return@update current
            val remainingItems = ready.items.filterNot { it.continueWatchingItem?.localKey == item.localKey }
            current.copy(state = RailLoadState.Ready(remainingItems))
        }

        viewModelScope.launch {
            val removalResult =
                withContext(Dispatchers.IO) {
                    if (item.id.isNotBlank()) {
                        watchHistoryService.removeFromPlayback(playbackId = item.id.trim())
                    } else {
                        com.crispy.tv.player.WatchHistoryResult(accepted = true, statusMessage = "")
                    }
                }

            if (!removalResult.accepted) {
                _errorEvents.tryEmit(removalResult.statusMessage.ifBlank { "Unable to remove this item." })
            }
        }
    }

    private suspend fun loadPrimary() {
        val snapshot = runCatching { refreshCoordinator.loadPrimarySnapshot() }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Primary home feed load failed", error)
            _errorEvents.tryEmit(error.message ?: "Failed to load home feed.")
            HomePrimarySnapshot(hero = HeroState(isLoading = false))
        }
        applyPrimarySnapshot(snapshot)
    }

    private suspend fun loadContinueWatching() {
        val section = runCatching { loadWithRetry { refreshCoordinator.loadContinueWatching() } }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Continue watching load failed", error)
            _errorEvents.tryEmit(error.message ?: "Failed to load continue watching.")
            defaultWideRailSection(
                key = CONTINUE_WATCHING_SECTION_KEY,
                title = "Continue Watching",
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
            ).copy(state = RailLoadState.Hidden)
        }
        applyWideRailSection(section)
    }

    private suspend fun loadUpNext() {
        val section = runCatching { loadWithRetry { refreshCoordinator.loadUpNext() } }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Up next load failed", error)
            _errorEvents.tryEmit(error.message ?: "Failed to load up next.")
            defaultWideRailSection(
                key = UP_NEXT_SECTION_KEY,
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
            ).copy(state = RailLoadState.Hidden)
        }
        applyWideRailSection(section)
    }

    private suspend fun loadThisWeek() {
        val section = runCatching { loadWithRetry { refreshCoordinator.loadThisWeekSection() } }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "This week load failed", error)
            _errorEvents.tryEmit(error.message ?: "Failed to load this week.")
            defaultWideRailSection(
                key = THIS_WEEK_SECTION_KEY,
                title = "This Week",
                kind = HomeWideRailSectionKind.THIS_WEEK,
            ).copy(state = RailLoadState.Hidden)
        }
        applyWideRailSection(section)
    }

    private suspend fun <T> loadWithRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(RAIL_LOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RAIL_RETRY_BACKOFF_MS * attempt)
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Rail load failed")
    }

    private fun applyPrimarySnapshot(snapshot: HomePrimarySnapshot) {
        catalogSectionLayoutMeta = snapshot.catalogSections.map { sectionUi ->
            CatalogSectionLayoutMeta(key = sectionUi.section.key, layout = sectionUi.section.layout)
        }
        _state.update { current ->
            current.copy(
                heroState = snapshot.hero,
                headerPills = snapshot.headerPills,
                catalogSections = snapshot.catalogSections.associateBy { it.section.key },
                layoutState = buildHomeLayoutState(
                    wideRails = current.wideRailSections,
                    catalogSectionLayoutMeta = catalogSectionLayoutMeta,
                ),
            )
        }
    }

    private fun applyWideRailSection(section: HomeWideRailSectionUi) {
        _state.update { current ->
            val wideRailSections = current.wideRailSections + (section.key to section)
            current.copy(
                wideRailSections = wideRailSections,
                layoutState = buildHomeLayoutState(
                    wideRails = wideRailSections,
                    catalogSectionLayoutMeta = catalogSectionLayoutMeta,
                ),
            )
        }
    }

    private fun updateWideRailSection(
        key: String,
        transform: (HomeWideRailSectionUi) -> HomeWideRailSectionUi,
    ) {
        _state.update { current ->
            val existing = current.wideRailSections[key] ?: return@update current
            current.copy(wideRailSections = current.wideRailSections + (key to transform(existing)))
        }
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

private fun defaultHomeLayoutState(): HomeLayoutState {
    val blocks = listOf(
        HomeWideRailLayoutUi(key = CONTINUE_WATCHING_SECTION_KEY, kind = HomeWideRailSectionKind.CONTINUE_WATCHING),
        HomeWideRailLayoutUi(key = UP_NEXT_SECTION_KEY, kind = HomeWideRailSectionKind.UP_NEXT),
        HomeWideRailLayoutUi(key = THIS_WEEK_SECTION_KEY, kind = HomeWideRailSectionKind.THIS_WEEK),
    )
    return HomeLayoutState(blocks = blocks)
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
        state = RailLoadState.Loading,
    )
}

internal fun HomeWideRailSectionUi.isVisible(): Boolean {
    return state !is RailLoadState.Hidden
}

private fun CanonicalContinueWatchingItem.sectionKey(): String {
    return CONTINUE_WATCHING_SECTION_KEY
}

internal fun CanonicalContinueWatchingItem.toWideRailItem(nowMs: Long): HomeWideRailItemUi {
    val isEpisode = itemType.equals("episode", ignoreCase = true)
    val displayTitle = if (isEpisode) {
        val seasonPart = season?.let { String.format(Locale.US, "S%02d", it) }
        val episodePart = episode?.let { String.format(Locale.US, "E%02d", it) }
        listOfNotNull(seasonPart, episodePart).joinToString(":")
    } else {
        title
    }
    return HomeWideRailItemUi(
        key = "${type}:${localKey}",
        title = displayTitle,
        subtitle = buildHomeWatchActivitySubtitle(),
        imageUrl = stillUrl ?: backdropUrl ?: posterUrl,
        logoUrl = logoUrl,
        progressFraction = progressPercent?.takeIf { it > 0.0 }?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() },
        kind = HomeWideRailItemKind.WATCH_ACTIVITY,
        continueWatchingItem = this,
        detailsItemId = titleItemId,
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
        detailsItemId = titleItemId,
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
    return entry.titleItemId.trim().ifBlank { entry.id.trim().lowercase(Locale.US) }
}

private fun CanonicalContinueWatchingItem.buildHomeWatchActivitySubtitle(): String {
    val isShow = type.equals("show", ignoreCase = true) || type.equals("anime", ignoreCase = true)
    return if (isShow) {
        val seasonEpisode = if (season != null && episode != null) {
            String.format(Locale.US, "S%02dE%02d", season, episode)
        } else {
            null
        }
        val episodeName = episodeTitle?.takeIf { it.isNotBlank() }
        listOfNotNull(seasonEpisode, episodeName).joinToString(separator = ": ")
    } else {
        genre.orEmpty()
    }
}
