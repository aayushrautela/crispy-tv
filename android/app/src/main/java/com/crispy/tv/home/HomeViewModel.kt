package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderRecommendationsResult
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.settings.HomeScreenSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val statusMessage: String = ""
)

@Immutable
data class UpNextState(
    val items: List<ContinueWatchingItem> = emptyList(),
    val statusMessage: String = ""
)

@Immutable
data class ForYouState(
    val items: List<ContinueWatchingItem> = emptyList(),
    val statusMessage: String = ""
)

@Immutable
data class CatalogSectionsState(
    val sections: List<HomeCatalogSectionUi> = emptyList(),
    val statusMessage: String = ""
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
    private val watchHistoryService: WatchHistoryService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {
    companion object {
        private const val CATALOG_CONCURRENCY_LIMIT = 6

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            homeCatalogService =
                                HomeCatalogService(
                                    context = appContext,
                                    addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                                    httpClient = AppHttp.client(appContext),
                                ),
                            watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext),
                            suppressionStore = ContinueWatchingSuppressionStore(appContext),
                            settingsStore = HomeScreenSettingsStore(appContext)
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

    private val _forYouState = MutableStateFlow(ForYouState())
    val forYouState: StateFlow<ForYouState> = _forYouState.asStateFlow()

    private val _catalogSectionsState = MutableStateFlow(CatalogSectionsState())
    val catalogSectionsState: StateFlow<CatalogSectionsState> = _catalogSectionsState.asStateFlow()

    private var suppressedItemsByKey: MutableMap<String, Long>? = null
    private var catalogSectionsJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        catalogSectionsJob?.cancel()
        viewModelScope.launch {
            _heroState.update { it.copy(isLoading = true, statusMessage = "Loading featured content...") }

            val baseResult = withContext(Dispatchers.IO) {
                val suppressionMap = suppressionStore.read()
                suppressedItemsByKey = suppressionMap

                coroutineScope {
                    val settingsDeferred = async { settingsStore.load() }
                    val authStateDeferred = async { watchHistoryService.authState() }
                    val heroDeferred = async { homeCatalogService.loadHeroItems(limit = 10) }
                    val watchHistoryDeferred = async { watchHistoryService.listLocalHistory(limit = 40) }
                    
                    val settings = settingsDeferred.await()
                    val authState = authStateDeferred.await()
                    val selectedSource = settings.watchDataSource
                    
                    val providerConnected = when (selectedSource) {
                        WatchProvider.LOCAL -> true
                        WatchProvider.TRAKT -> authState.traktAuthenticated
                        WatchProvider.SIMKL -> authState.simklAuthenticated
                    }
                    
                    val providerContinueWatchingDeferred = async {
                        when (selectedSource) {
                            WatchProvider.LOCAL -> ContinueWatchingResult(statusMessage = "Local source selected.")
                            WatchProvider.TRAKT, WatchProvider.SIMKL -> {
                                watchHistoryService.listContinueWatching(
                                    limit = 20,
                                    source = selectedSource
                                )
                            }
                        }
                    }

                    val providerRecommendationsDeferred = async {
                        val recommendationSource = preferredRecommendationSource(
                            selectedSource = selectedSource,
                            traktAuthenticated = authState.traktAuthenticated,
                            simklAuthenticated = authState.simklAuthenticated
                        )
                        when {
                            !settings.traktTopPicksEnabled -> {
                                ProviderRecommendationsResult(statusMessage = "")
                            }
                            recommendationSource == null -> {
                                ProviderRecommendationsResult(statusMessage = "Connect Trakt or Simkl in Settings to load For You.")
                            }
                            else -> {
                                watchHistoryService.listProviderRecommendations(
                                    limit = 20,
                                    source = recommendationSource
                                )
                            }
                        }
                    }

                    HomeFeedLoadResult(
                        heroResult = heroDeferred.await(),
                        watchHistoryEntries = watchHistoryDeferred.await().entries,
                        providerContinueWatchingResult = providerContinueWatchingDeferred.await(),
                        providerRecommendationsResult = providerRecommendationsDeferred.await(),
                        selectedSource = selectedSource,
                        providerConnected = providerConnected
                    )
                }
            }

            _heroState.update { current ->
                val selectedId = current.selectedId?.takeIf { id -> baseResult.heroResult.items.any { it.id == id } }
                    ?: baseResult.heroResult.items.firstOrNull()?.id
                current.copy(
                    items = baseResult.heroResult.items,
                    selectedId = selectedId,
                    isLoading = false,
                    statusMessage = baseResult.heroResult.statusMessage
                )
            }

            // Run continue-watching enrichment, for-you enrichment, and catalog loading in parallel
            coroutineScope {
                launch {
                    val continueWatchingResult = withContext(Dispatchers.IO) {
                        when (baseResult.selectedSource) {
                            WatchProvider.LOCAL -> {
                                val filteredEntries = applySuppressionFilter(baseResult.watchHistoryEntries)
                                homeCatalogService.loadContinueWatchingItems(
                                    entries = filteredEntries,
                                    limit = 20
                                )
                            }
                            WatchProvider.TRAKT, WatchProvider.SIMKL -> {
                                val filteredProviderEntries = applyProviderSuppressionFilter(baseResult.providerContinueWatchingResult.entries)
                                if (filteredProviderEntries.isNotEmpty()) {
                                    homeCatalogService.loadContinueWatchingItemsFromProvider(
                                        entries = filteredProviderEntries,
                                        limit = 20
                                    )
                                } else {
                                    ContinueWatchingLoadResult(
                                        items = emptyList(),
                                        statusMessage = baseResult.providerContinueWatchingResult.statusMessage.ifBlank {
                                            if (baseResult.providerConnected) {
                                                "No continue watching entries available."
                                            } else {
                                                "Connect ${baseResult.selectedSource.displayName()} in Settings to load continue watching."
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    val inProgressItems = continueWatchingResult.items.filter { !it.isUpNextPlaceholder }
                    val upNextItems = continueWatchingResult.items.filter { it.isUpNextPlaceholder }

                    // Batch both emissions together to reduce recomposition count
                    _continueWatchingState.value = ContinueWatchingState(
                        items = inProgressItems,
                        statusMessage = continueWatchingResult.statusMessage
                    )
                    _upNextState.value = UpNextState(
                        items = upNextItems,
                        statusMessage = if (upNextItems.isEmpty() && inProgressItems.isNotEmpty()) "" else continueWatchingResult.statusMessage
                    )
                }

                launch {
                    val forYouResult = withContext(Dispatchers.IO) {
                        val filteredEntries = applyProviderLibrarySuppressionFilter(baseResult.providerRecommendationsResult.items)
                        if (filteredEntries.isEmpty()) {
                            ContinueWatchingLoadResult(
                                items = emptyList(),
                                statusMessage = baseResult.providerRecommendationsResult.statusMessage
                            )
                        } else {
                            val loaded = homeCatalogService.loadContinueWatchingItemsFromProvider(
                                entries = filteredEntries.map { it.toProviderContinueWatchingEntry() },
                                limit = 20
                            )
                            val statusMessage =
                                if (loaded.items.isNotEmpty()) {
                                    ""
                                } else {
                                    baseResult.providerRecommendationsResult.statusMessage.ifBlank { loaded.statusMessage }
                                }
                            loaded.copy(statusMessage = statusMessage)
                        }
                    }

                    _forYouState.value = ForYouState(
                        items = forYouResult.items,
                        statusMessage = forYouResult.statusMessage
                    )
                }

                catalogSectionsJob = launch {
                    loadCatalogSections()
                }
            }
        }
    }

    private suspend fun loadCatalogSections() {
        _catalogSectionsState.update { current ->
            current.copy(
                sections = emptyList(),
                statusMessage = "Loading catalogs..."
            )
        }

        val sectionsResult = withContext(Dispatchers.IO) {
            homeCatalogService.listHomeCatalogSections(limit = 15)
        }
        val sections = sectionsResult.first
        val statusMessage = sectionsResult.second

        _catalogSectionsState.update { current ->
            current.copy(
                sections = sections.map { section ->
                    HomeCatalogSectionUi(section = section)
                },
                statusMessage = statusMessage
            )
        }

        if (sections.isEmpty()) return

        val semaphore = Semaphore(CATALOG_CONCURRENCY_LIMIT)
        val loadedSections = withContext(Dispatchers.IO) {
            coroutineScope {
                sections.map { section ->
                    async {
                        semaphore.withPermit {
                            val pageResult = homeCatalogService.fetchCatalogPage(
                                section = section,
                                page = 1,
                                pageSize = 12
                            )
                            section.key to pageResult
                        }
                    }
                }.awaitAll()
            }
        }

        val resultsByKey = loadedSections.toMap()

        _catalogSectionsState.update { current ->
            current.copy(
                sections = current.sections.map { existing ->
                    val result = resultsByKey[existing.section.key]
                    if (result != null) {
                        existing.copy(
                            items = result.items,
                            isLoading = false,
                            statusMessage = result.statusMessage
                        )
                    } else {
                        existing
                    }
                }
            )
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
                        watchHistoryService.unmarkWatched(
                            request = item.toUnmarkRequest(),
                            source = item.provider
                        )
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

    private fun applySuppressionFilter(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> {
        if (entries.isEmpty()) return emptyList()
        val suppressionMap = suppressedItemsByKey ?: return entries

        var updated = false
        val filtered = mutableListOf<WatchHistoryEntry>()
        entries.forEach { entry ->
            val episodeKey = continueWatchingKey(entry)
            val contentKey = continueWatchingContentKey(entry)

            val episodeSuppressedAt = suppressionMap[episodeKey]
            val contentSuppressedAt = suppressionMap[contentKey]

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
                    suppressionMap.remove(episodeKey)
                    updated = true
                }
                if (contentSuppressedAt != null && entry.watchedAtEpochMs > contentSuppressedAt) {
                    suppressionMap.remove(contentKey)
                    updated = true
                }
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressionMap)
        }

        return filtered
    }

    private fun applyProviderSuppressionFilter(entries: List<ContinueWatchingEntry>): List<ContinueWatchingEntry> {
        if (entries.isEmpty()) return emptyList()
        val suppressionMap = suppressedItemsByKey ?: return entries

        var updated = false
        val filtered = mutableListOf<ContinueWatchingEntry>()
        entries.forEach { entry ->
            val key = continueWatchingContentKey(entry)
            val suppressedAt = suppressionMap[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.lastUpdatedEpochMs > suppressedAt) {
                suppressionMap.remove(key)
                updated = true
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressionMap)
        }

        return filtered
    }

    private fun applyProviderLibrarySuppressionFilter(entries: List<ProviderLibraryItem>): List<ProviderLibraryItem> {
        if (entries.isEmpty()) return emptyList()
        val suppressionMap = suppressedItemsByKey ?: return entries

        var updated = false
        val filtered = mutableListOf<ProviderLibraryItem>()
        entries.forEach { entry ->
            val key = continueWatchingContentKey(entry.contentType, entry.contentId)
            val suppressedAt = suppressionMap[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.addedAtEpochMs > suppressedAt) {
                suppressionMap.remove(key)
                updated = true
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressionMap)
        }

        return filtered
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

private data class HomeFeedLoadResult(
    val heroResult: HomeHeroLoadResult,
    val watchHistoryEntries: List<WatchHistoryEntry>,
    val providerContinueWatchingResult: ContinueWatchingResult,
    val providerRecommendationsResult: ProviderRecommendationsResult,
    val selectedSource: WatchProvider,
    val providerConnected: Boolean
)

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

private fun continueWatchingContentKey(contentType: MetadataLabMediaType, contentId: String): String {
    val type = if (contentType == MetadataLabMediaType.SERIES) "series" else "movie"
    return continueWatchingContentKey(type = type, contentId = contentId)
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

private fun ProviderLibraryItem.toProviderContinueWatchingEntry(): ContinueWatchingEntry {
    return ContinueWatchingEntry(
        contentId = contentId,
        contentType = contentType,
        title = title,
        season = season,
        episode = episode,
        progressPercent = 0.0,
        lastUpdatedEpochMs = addedAtEpochMs,
        provider = provider,
        providerPlaybackId = null,
        isUpNextPlaceholder = false
    )
}

private fun WatchProvider.displayName(): String {
    return name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
}

private fun preferredRecommendationSource(
    selectedSource: WatchProvider,
    traktAuthenticated: Boolean,
    simklAuthenticated: Boolean
): WatchProvider? {
    return when {
        selectedSource == WatchProvider.SIMKL && simklAuthenticated -> WatchProvider.SIMKL
        selectedSource == WatchProvider.TRAKT && traktAuthenticated -> WatchProvider.TRAKT
        traktAuthenticated -> WatchProvider.TRAKT
        simklAuthenticated -> WatchProvider.SIMKL
        else -> null
    }
}
