package com.crispy.rewrite.home

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.ContinueWatchingLabResult
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.settings.HomeScreenSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class HomeUiState(
    val heroItems: List<HomeHeroItem> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
    val selectedHeroId: String? = null,
    val isLoading: Boolean = true,
    val statusMessage: String = "Loading featured content...",
    val continueWatchingStatusMessage: String = "",
    val catalogsStatusMessage: String = ""
) {
    val selectedHero: HomeHeroItem?
        get() = heroItems.firstOrNull { it.id == selectedHeroId } ?: heroItems.firstOrNull()
}

data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

class HomeViewModel(
    private val homeCatalogService: HomeCatalogService,
    private val watchHistoryService: WatchHistoryLabService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val suppressedItemsByKey = suppressionStore.read()
    private var catalogSectionsJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        catalogSectionsJob?.cancel()
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoading = true,
                    statusMessage = "Loading featured content..."
                )
            }

            val baseResult = withContext(Dispatchers.IO) {
                val selectedSource = settingsStore.load().watchDataSource
                val authState = watchHistoryService.authState()
                val heroResult = homeCatalogService.loadHeroItems(limit = 10)
                val watchHistoryResult = watchHistoryService.listLocalHistory(limit = 40)
                val providerConnected =
                    when (selectedSource) {
                        WatchProvider.LOCAL -> true
                        WatchProvider.TRAKT -> authState.traktAuthenticated
                        WatchProvider.SIMKL -> authState.simklAuthenticated
                    }
                val providerContinueWatchingResult =
                    when (selectedSource) {
                        WatchProvider.LOCAL -> ContinueWatchingLabResult(statusMessage = "Local source selected.")
                        WatchProvider.TRAKT,
                        WatchProvider.SIMKL -> {
                            if (providerConnected) {
                                watchHistoryService.listContinueWatching(
                                    limit = 20,
                                    source = selectedSource
                                )
                            } else {
                                ContinueWatchingLabResult(
                                    statusMessage = "Connect ${selectedSource.displayName()} in Settings to load continue watching."
                                )
                            }
                        }
                    }
                HomeFeedLoadResult(
                    heroResult = heroResult,
                    watchHistoryEntries = watchHistoryResult.entries,
                    providerContinueWatchingResult = providerContinueWatchingResult,
                    selectedSource = selectedSource,
                    providerConnected = providerConnected
                )
            }
            val continueWatchingResult =
                withContext(Dispatchers.IO) {
                    when (baseResult.selectedSource) {
                        WatchProvider.LOCAL -> {
                            val filteredEntries = applySuppressionFilter(baseResult.watchHistoryEntries)
                            homeCatalogService.loadContinueWatchingItems(
                                entries = filteredEntries,
                                limit = 20
                            )
                        }

                        WatchProvider.TRAKT,
                        WatchProvider.SIMKL -> {
                            if (baseResult.providerContinueWatchingResult.entries.isNotEmpty()) {
                                homeCatalogService.loadContinueWatchingItemsFromProvider(
                                    entries = baseResult.providerContinueWatchingResult.entries,
                                    limit = 20
                                )
                            } else {
                                ContinueWatchingLoadResult(
                                    items = emptyList(),
                                    statusMessage =
                                        baseResult.providerContinueWatchingResult.statusMessage.ifBlank {
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

            _uiState.update { current ->
                val selectedId =
                    current.selectedHeroId?.takeIf { id -> baseResult.heroResult.items.any { it.id == id } }
                        ?: baseResult.heroResult.items.firstOrNull()?.id
                current.copy(
                    heroItems = baseResult.heroResult.items,
                    continueWatchingItems = continueWatchingResult.items,
                    selectedHeroId = selectedId,
                    isLoading = false,
                    statusMessage = baseResult.heroResult.statusMessage,
                    continueWatchingStatusMessage = continueWatchingResult.statusMessage
                )
            }

            catalogSectionsJob = viewModelScope.launch {
                loadCatalogSections()
            }
        }
    }

    private suspend fun loadCatalogSections() {
        _uiState.update { current ->
            current.copy(
                catalogSections = emptyList(),
                catalogsStatusMessage = "Loading catalogs..."
            )
        }

        val sectionsResult = withContext(Dispatchers.IO) {
            homeCatalogService.listHomeCatalogSections(limit = 15)
        }
        val sections = sectionsResult.first
        val statusMessage = sectionsResult.second

        _uiState.update { current ->
            current.copy(
                catalogSections = sections.map { section ->
                    HomeCatalogSectionUi(section = section)
                },
                catalogsStatusMessage = statusMessage
            )
        }

        sections.forEach { section ->
            val pageResult =
                withContext(Dispatchers.IO) {
                    homeCatalogService.fetchCatalogPage(
                        section = section,
                        page = 1,
                        pageSize = 12
                    )
                }
            _uiState.update { current ->
                current.copy(
                    catalogSections =
                        current.catalogSections.map { existing ->
                            if (existing.section.key != section.key) {
                                existing
                            } else {
                                existing.copy(
                                    items = pageResult.items,
                                    isLoading = false,
                                    statusMessage = pageResult.statusMessage
                                )
                            }
                        }
                )
            }
        }
    }

    fun hideContinueWatchingItem(item: ContinueWatchingItem) {
        suppressItem(item.id)
        _uiState.update { current ->
            current.copy(
                continueWatchingItems = current.continueWatchingItems.filterNot { it.id == item.id },
                continueWatchingStatusMessage = "Hidden ${item.title} from Continue Watching."
            )
        }
    }

    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        suppressItem(item.id)
        _uiState.update { current ->
            current.copy(
                continueWatchingItems = current.continueWatchingItems.filterNot { it.id == item.id },
                continueWatchingStatusMessage = "Removing ${item.title} from Continue Watching..."
            )
        }

        viewModelScope.launch {
            val removalResult =
                withContext(Dispatchers.IO) {
                    watchHistoryService.unmarkWatched(
                        request = item.toUnmarkRequest(),
                        source = item.provider
                    )
                }
            _uiState.update { current ->
                current.copy(
                    continueWatchingStatusMessage = removalResult.statusMessage
                )
            }
        }
    }

    fun onHeroSelected(heroId: String) {
        _uiState.update { current ->
            if (current.heroItems.none { it.id == heroId }) {
                current
            } else {
                current.copy(selectedHeroId = heroId)
            }
        }
    }

    private fun applySuppressionFilter(entries: List<WatchHistoryEntry>): List<WatchHistoryEntry> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        var updated = false
        val filtered = mutableListOf<WatchHistoryEntry>()
        entries.forEach { entry ->
            val key = continueWatchingKey(entry)
            val suppressedAt = suppressedItemsByKey[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.watchedAtEpochMs > suppressedAt) {
                suppressedItemsByKey.remove(key)
                updated = true
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressedItemsByKey)
        }

        return filtered
    }

    private fun suppressItem(itemId: String) {
        suppressedItemsByKey[itemId] = System.currentTimeMillis()
        suppressionStore.write(suppressedItemsByKey)
    }

    companion object {
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
                                    addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS
                                ),
                            watchHistoryService = PlaybackLabDependencies.watchHistoryServiceFactory(appContext),
                            suppressionStore = ContinueWatchingSuppressionStore(appContext),
                            settingsStore = HomeScreenSettingsStore(appContext)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private data class HomeFeedLoadResult(
    val heroResult: HomeHeroLoadResult,
    val watchHistoryEntries: List<WatchHistoryEntry>,
    val providerContinueWatchingResult: ContinueWatchingLabResult,
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

private fun WatchProvider.displayName(): String {
    return name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
}
