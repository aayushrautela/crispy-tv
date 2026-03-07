package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.metadata.TmdbEpisodeListProvider
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepositoryProvider
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
data class ContinueWatchingState(
    val items: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

@Immutable
data class UpNextState(
    val items: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

@Immutable
data class ThisWeekState(
    val items: List<ThisWeekItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

@Immutable
data class CatalogSectionsState(
    val sections: List<HomeCatalogSectionUi> = emptyList(),
    val statusMessage: String = ""
)

@Immutable
data class CatalogHeaderSectionsState(
    val sections: List<CatalogSectionRef> = emptyList(),
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
    private val thisWeekService: ThisWeekService,
    private val suppressionStore: ContinueWatchingSuppressionStore
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val CATALOG_CONCURRENCY_LIMIT = 6
        private const val SECTION_SKELETON_MIN_DURATION_MS = 450L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        val httpClient = AppHttp.client(appContext)
                        val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                        val tmdbEnrichmentRepository = TmdbEnrichmentRepositoryProvider.get(appContext)
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            homeCatalogService =
                                HomeCatalogService(
                                    context = appContext,
                                    httpClient = httpClient,
                                    supabaseUrl = BuildConfig.SUPABASE_URL,
                                    supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                                ),
                            homeWatchActivityService =
                                HomeWatchActivityService(
                                    context = appContext,
                                    tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                                ),
                            watchHistoryService = watchHistoryService,
                            thisWeekService = ThisWeekService(
                                watchHistoryService = watchHistoryService,
                                episodeListProvider = TmdbEpisodeListProvider(
                                    tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                                ),
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

    private val _continueWatchingState = MutableStateFlow(ContinueWatchingState())
    val continueWatchingState: StateFlow<ContinueWatchingState> = _continueWatchingState.asStateFlow()

    private val _upNextState = MutableStateFlow(UpNextState())
    val upNextState: StateFlow<UpNextState> = _upNextState.asStateFlow()

    private val _thisWeekState = MutableStateFlow(ThisWeekState())
    val thisWeekState: StateFlow<ThisWeekState> = _thisWeekState.asStateFlow()

    private val _catalogSectionsState = MutableStateFlow(CatalogSectionsState())
    val catalogSectionsState: StateFlow<CatalogSectionsState> = _catalogSectionsState.asStateFlow()

    private val _headerCatalogSectionsState = MutableStateFlow(CatalogHeaderSectionsState())
    val headerCatalogSectionsState: StateFlow<CatalogHeaderSectionsState> = _headerCatalogSectionsState.asStateFlow()

    private var suppressedItemsByKey: MutableMap<String, Long>? = null
    @Volatile
    private var refreshGeneration: Long = 0L
    private var personalFeedJob: Job? = null
    private var headerCatalogSectionsJob: Job? = null
    private var watchActivityJob: Job? = null
    private var thisWeekJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val currentRefreshGeneration = beginRefresh()

        _heroState.update { it.copy(isLoading = true, statusMessage = "Loading featured content...") }
        _continueWatchingState.update { current ->
            current.copy(isLoading = true, statusMessage = "")
        }
        _upNextState.update { current ->
            current.copy(isLoading = true, statusMessage = "")
        }
        _thisWeekState.update { current ->
            current.copy(isLoading = true, statusMessage = "")
        }
        _catalogSectionsState.value = CatalogSectionsState()
        _headerCatalogSectionsState.value = CatalogHeaderSectionsState()

        personalFeedJob = viewModelScope.launch {
            refreshPersonalFeed(currentRefreshGeneration)
        }
        headerCatalogSectionsJob = viewModelScope.launch {
            refreshHeaderCatalogSections(currentRefreshGeneration)
        }
        watchActivityJob = viewModelScope.launch {
            refreshWatchActivity(currentRefreshGeneration)
        }
        thisWeekJob = viewModelScope.launch {
            refreshThisWeek(currentRefreshGeneration)
        }
    }

    private fun beginRefresh(): Long {
        val nextGeneration = refreshGeneration + 1L
        refreshGeneration = nextGeneration
        cancelRefreshJobs()
        return nextGeneration
    }

    private fun cancelRefreshJobs() {
        personalFeedJob?.cancel()
        headerCatalogSectionsJob?.cancel()
        watchActivityJob?.cancel()
        thisWeekJob?.cancel()
    }

    private fun isCurrentRefresh(generation: Long): Boolean {
        return refreshGeneration == generation
    }

    private suspend fun refreshPersonalFeed(generation: Long) {
        val feedLoadStartedAt = System.currentTimeMillis()
        val personalFeedResult =
            try {
                withContext(Dispatchers.IO) {
                    homeCatalogService.loadPersonalHomeFeed(heroLimit = 10)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Personal home feed refresh failed", error)
                delayForMinimumSkeletonVisibility(feedLoadStartedAt)
                if (!isCurrentRefresh(generation)) return
                val message = error.message ?: "Failed to load home feed."
                _heroState.value = HeroState(items = emptyList(), isLoading = false, statusMessage = message)
                _catalogSectionsState.value = CatalogSectionsState(statusMessage = message)
                return
            }

        delayForMinimumSkeletonVisibility(feedLoadStartedAt)
        if (!isCurrentRefresh(generation)) return

        _heroState.update { current ->
            val selectedId =
                current.selectedId?.takeIf { id ->
                    personalFeedResult.heroResult.items.any { it.id == id }
                } ?: personalFeedResult.heroResult.items.firstOrNull()?.id
            current.copy(
                items = personalFeedResult.heroResult.items,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = personalFeedResult.heroResult.statusMessage,
            )
        }

        _catalogSectionsState.value =
            CatalogSectionsState(
                sections = personalFeedResult.sections.map { section ->
                    HomeCatalogSectionUi(section = section)
                },
                statusMessage = personalFeedResult.sectionsStatusMessage,
            )

        if (personalFeedResult.sections.isEmpty()) {
            return
        }

        val sectionLoadStartedAt = System.currentTimeMillis()
        val semaphore = Semaphore(CATALOG_CONCURRENCY_LIMIT)

        coroutineScope {
            personalFeedResult.sections.map { section ->
                launch {
                    val pageResult =
                        try {
                            withContext(Dispatchers.IO) {
                                semaphore.withPermit {
                                    homeCatalogService.fetchCatalogPage(
                                        section = section,
                                        page = 1,
                                        pageSize = 12,
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            Log.w(TAG, "Home catalog section refresh failed for ${section.key}", error)
                            CatalogPageResult(
                                items = emptyList(),
                                statusMessage = error.message ?: "Failed to load catalog.",
                                attemptedUrls = emptyList(),
                            )
                        }

                    delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
                    if (!isCurrentRefresh(generation)) return@launch
                    _catalogSectionsState.update { current ->
                        current.copy(
                            sections = current.sections.map { existing ->
                                if (existing.section.key == section.key) {
                                    existing.copy(
                                        items = pageResult.items,
                                        isLoading = false,
                                        statusMessage = pageResult.statusMessage,
                                    )
                                } else {
                                    existing
                                }
                            }
                        )
                    }
                }
            }.joinAll()
        }
    }

    private suspend fun refreshHeaderCatalogSections(generation: Long) {
        val sections =
            try {
                withContext(Dispatchers.IO) {
                    homeCatalogService.loadGlobalHeaderSections()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Global header sections refresh failed", error)
                emptyList()
            }

        if (!isCurrentRefresh(generation)) return
        _headerCatalogSectionsState.value = CatalogHeaderSectionsState(sections = sections)
    }

    private suspend fun refreshWatchActivity(generation: Long) {
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
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Watch activity refresh failed", error)
                delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
                if (!isCurrentRefresh(generation)) return
                val message = error.message ?: "Failed to load continue watching."
                _continueWatchingState.value = ContinueWatchingState(isLoading = false, statusMessage = message)
                _upNextState.value = UpNextState(isLoading = false, statusMessage = message)
                return
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
        if (!isCurrentRefresh(generation)) return

        val inProgressItems = continueWatchingResult.items.filter { !it.isUpNextPlaceholder }
        val upNextItems = continueWatchingResult.items.filter { it.isUpNextPlaceholder }

        _continueWatchingState.value = ContinueWatchingState(
            items = inProgressItems,
            isLoading = false,
            statusMessage = continueWatchingResult.statusMessage,
        )
        _upNextState.value = UpNextState(
            items = upNextItems,
            isLoading = false,
            statusMessage =
                if (upNextItems.isEmpty() && inProgressItems.isNotEmpty()) {
                    ""
                } else {
                    continueWatchingResult.statusMessage
                },
        )
    }

    private suspend fun refreshThisWeek(generation: Long) {
        val sectionLoadStartedAt = System.currentTimeMillis()
        val thisWeekResult =
            try {
                withContext(Dispatchers.IO) {
                    thisWeekService.loadThisWeek(System.currentTimeMillis())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "This week refresh failed", error)
                delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
                if (!isCurrentRefresh(generation)) return
                _thisWeekState.value = ThisWeekState(isLoading = false, statusMessage = error.message ?: "Failed to load this week.")
                return
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)
        if (!isCurrentRefresh(generation)) return
        _thisWeekState.value = ThisWeekState(
            items = thisWeekResult.items,
            isLoading = false,
            statusMessage = thisWeekResult.statusMessage.orEmpty(),
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
        _continueWatchingState.update { current ->
            current.copy(
                items = current.items.filterNot { it.id == item.id },
                statusMessage = "Hidden ${item.title}."
            )
        }
        _upNextState.update { current ->
            current.copy(items = current.items.filterNot { it.id == item.id })
        }
    }

    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        _continueWatchingState.update { current ->
            current.copy(
                items = current.items.filterNot { it.id == item.id },
                statusMessage = "Removing ${item.title}..."
            )
        }
        _upNextState.update { current ->
            current.copy(items = current.items.filterNot { it.id == item.id })
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
            _continueWatchingState.update { current ->
                current.copy(statusMessage = removalResult.statusMessage)
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
