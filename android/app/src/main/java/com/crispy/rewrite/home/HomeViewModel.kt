package com.crispy.rewrite.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.ContinueWatchingEntry
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
    val upNextItems: List<ContinueWatchingItem> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
    val selectedHeroId: String? = null,
    val isLoading: Boolean = true,
    val statusMessage: String = "Loading featured content...",
    val continueWatchingStatusMessage: String = "",
    val upNextStatusMessage: String = "",
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

class HomeViewModel internal constructor(
    private val homeCatalogService: HomeCatalogService,
    private val watchHistoryService: WatchHistoryLabService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"

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
                            watchHistoryService.listContinueWatching(
                                limit = 20,
                                source = selectedSource
                            )
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
                            val filteredProviderEntries =
                                applyProviderSuppressionFilter(baseResult.providerContinueWatchingResult.entries)
                            if (filteredProviderEntries.isNotEmpty()) {
                                homeCatalogService.loadContinueWatchingItemsFromProvider(
                                    entries = filteredProviderEntries,
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

            val inProgressItems = continueWatchingResult.items.filter { !it.isUpNextPlaceholder }
            val upNextItems = continueWatchingResult.items.filter { it.isUpNextPlaceholder }
            Log.d(TAG, "refresh: total=${continueWatchingResult.items.size} inProgress=${inProgressItems.size} upNext=${upNextItems.size} statusMessage=${continueWatchingResult.statusMessage}")
            if (inProgressItems.isEmpty() && upNextItems.isEmpty()) {
                Log.w(TAG, "refresh: no continue watching or up next items")
            }
            inProgressItems.forEachIndexed { i, item ->
                Log.d(TAG, "  inProgress[$i]: id=${item.id} title=${item.title} s=${item.season} e=${item.episode} progress=${item.progressPercent}")
            }
            upNextItems.forEachIndexed { i, item ->
                Log.d(TAG, "  upNext[$i]: id=${item.id} title=${item.title} s=${item.season} e=${item.episode} progress=${item.progressPercent}")
            }

            _uiState.update { current ->
                val selectedId =
                    current.selectedHeroId?.takeIf { id -> baseResult.heroResult.items.any { it.id == id } }
                        ?: baseResult.heroResult.items.firstOrNull()?.id
                current.copy(
                    heroItems = baseResult.heroResult.items,
                    continueWatchingItems = inProgressItems,
                    upNextItems = upNextItems,
                    selectedHeroId = selectedId,
                    isLoading = false,
                    statusMessage = baseResult.heroResult.statusMessage,
                    continueWatchingStatusMessage = continueWatchingResult.statusMessage,
                    upNextStatusMessage = if (upNextItems.isEmpty() && inProgressItems.isNotEmpty()) "" else continueWatchingResult.statusMessage
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
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        _uiState.update { current ->
            current.copy(
                continueWatchingItems = current.continueWatchingItems.filterNot { it.id == item.id },
                upNextItems = current.upNextItems.filterNot { it.id == item.id },
                continueWatchingStatusMessage = "Hidden ${item.title}."
            )
        }
    }

    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId)
        )
        _uiState.update { current ->
            current.copy(
                continueWatchingItems = current.continueWatchingItems.filterNot { it.id == item.id },
                upNextItems = current.upNextItems.filterNot { it.id == item.id },
                continueWatchingStatusMessage = "Removing ${item.title}..."
            )
        }

        viewModelScope.launch {
            val removalResult =
                withContext(Dispatchers.IO) {
                    when {
                        item.isUpNextPlaceholder -> {
                            com.crispy.rewrite.player.WatchHistoryLabResult(
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
                            com.crispy.rewrite.player.WatchHistoryLabResult(
                                statusMessage = "Removed ${item.title} from Continue Watching."
                            )
                        }
                    }
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
            val episodeKey = continueWatchingKey(entry)
            val contentKey = continueWatchingContentKey(entry)

            val episodeSuppressedAt = suppressedItemsByKey[episodeKey]
            val contentSuppressedAt = suppressedItemsByKey[contentKey]

            val suppressedAt =
                when {
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
                    suppressedItemsByKey.remove(episodeKey)
                    updated = true
                }
                if (contentSuppressedAt != null && entry.watchedAtEpochMs > contentSuppressedAt) {
                    suppressedItemsByKey.remove(contentKey)
                    updated = true
                }
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressedItemsByKey)
        }

        return filtered
    }

    private fun applyProviderSuppressionFilter(entries: List<ContinueWatchingEntry>): List<ContinueWatchingEntry> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        var updated = false
        val filtered = mutableListOf<ContinueWatchingEntry>()
        entries.forEach { entry ->
            val key = continueWatchingContentKey(entry)
            val suppressedAt = suppressedItemsByKey[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.lastUpdatedEpochMs > suppressedAt) {
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

    private fun suppressKeys(vararg keys: String) {
        val now = System.currentTimeMillis()
        keys
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { key ->
                suppressedItemsByKey[key] = now
            }
        suppressionStore.write(suppressedItemsByKey)
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

private fun WatchProvider.displayName(): String {
    return name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
}
