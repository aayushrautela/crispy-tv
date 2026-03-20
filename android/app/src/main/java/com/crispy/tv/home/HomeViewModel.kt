package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.BuildConfig
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    val statusMessage: String = "Loading featured content...",
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = "",
)

private data class HomePrimarySnapshot(
    val hero: HeroState = HeroState(),
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
    val catalogStatusMessage: String = "",
)

private data class HomeWatchActivitySnapshot(
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

private data class CatalogSectionLayoutMeta(
    val key: String,
    val presentation: HomeCatalogPresentation,
)

private const val CONTINUE_WATCHING_SECTION_KEY = "continueWatching"
private const val UP_NEXT_SECTION_KEY = "upNext"
private const val THIS_WEEK_SECTION_KEY = "thisWeek"
private const val CATALOG_STATUS_SECTION_KEY = "catalogStatus"

class HomeViewModel internal constructor(
    private val homeCatalogService: HomeCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: WatchHistoryService,
    private val calendarService: CalendarService,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
    private val suppressionStore: ContinueWatchingSuppressionStore,
) : ViewModel() {
    companion object {
        private const val TAG = "HomeViewModel"
        private const val SECTION_SKELETON_MIN_DURATION_MS = 150L
        private const val BACKGROUND_REFRESH_DEBOUNCE_MS = 60_000L
        private const val HOME_PROVIDER_CONTINUE_WATCHING_LIMIT = 30

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                        val tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext)
                        val calendarMetaEpisodeService =
                            CalendarMetaEpisodeService(
                                context = appContext,
                                addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                                httpClient = AppHttp.client(appContext),
                            )
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
                            homeCatalogService = SupabaseServicesProvider.homeCatalogService(appContext),
                            homeWatchActivityService =
                                HomeWatchActivityService(
                                    context = appContext,
                                    tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                                ),
                            watchHistoryService = watchHistoryService,
                            calendarService =
                                CalendarService(
                                    watchHistoryService = watchHistoryService,
                                    tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                                    metaEpisodeService = calendarMetaEpisodeService,
                                ),
                            tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                            suppressionStore = ContinueWatchingSuppressionStore(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _headerPillsState = MutableStateFlow<List<CatalogSectionRef>>(emptyList())
    val headerPillsState: StateFlow<List<CatalogSectionRef>> = _headerPillsState.asStateFlow()

    private val _heroState = MutableStateFlow(HeroState())
    val heroState: StateFlow<HeroState> = _heroState.asStateFlow()

    private val _layoutState = MutableStateFlow(HomeLayoutState())
    val layoutState: StateFlow<HomeLayoutState> = _layoutState.asStateFlow()

    private val _wideRailSectionsState = MutableStateFlow(defaultWideRailSections())
    val wideRailSectionsState: StateFlow<Map<String, HomeWideRailSectionUi>> = _wideRailSectionsState.asStateFlow()

    private val _catalogSectionsState = MutableStateFlow<Map<String, HomeCatalogSectionUi>>(emptyMap())
    val catalogSectionsState: StateFlow<Map<String, HomeCatalogSectionUi>> = _catalogSectionsState.asStateFlow()

    private var catalogSectionLayoutMeta: List<CatalogSectionLayoutMeta> = emptyList()
    private var catalogStatusMessage: String = ""
    private var primarySnapshot = HomePrimarySnapshot()
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
        prepareForRefresh(showForegroundLoading)

        refreshJob =
            viewModelScope.launch {
                var nextPrimarySnapshot: HomePrimarySnapshot? = null
                var nextWatchActivitySnapshot: HomeWatchActivitySnapshot? = null
                try {
                    coroutineScope {
                        val primaryDeferred = async { loadPrimarySnapshot() }
                        val watchActivityDeferred =
                            async {
                                loadWatchActivitySnapshot(
                                    enrichMetadata = !showForegroundLoading,
                                )
                            }
                        nextPrimarySnapshot = primaryDeferred.await()
                        nextWatchActivitySnapshot = watchActivityDeferred.await()
                    }
                } finally {
                    if (isCurrentRefresh(currentRefreshGeneration)) {
                        nextPrimarySnapshot?.let(::applyPrimarySnapshot)
                        nextWatchActivitySnapshot?.let(::applyWatchActivitySnapshot)
                        _isRefreshing.value = false
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
                                        loadWatchActivitySnapshot(enrichMetadata = true)
                                    } else {
                                        watchActivitySnapshot
                                    }
                                val refreshedThisWeekSection = loadThisWeekSection()
                                if (!isCurrentRefresh(currentRefreshGeneration)) {
                                    return@launch
                                }

                                if (refreshedWatchActivitySnapshot != watchActivitySnapshot) {
                                    applyWatchActivitySnapshot(refreshedWatchActivitySnapshot)
                                }
                                if (refreshedThisWeekSection != thisWeekSection) {
                                    applyThisWeekSection(refreshedThisWeekSection)
                                }
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

    fun hideContinueWatchingItem(item: ContinueWatchingItem) {
        suppressKeys(
            item.id,
            continueWatchingContentKey(type = item.type, contentId = item.contentId),
        )
        updateWideRailSection(item.sectionKey()) { current ->
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
            continueWatchingContentKey(type = item.type, contentId = item.contentId),
        )
        updateWideRailSection(item.sectionKey()) { current ->
            val remainingItems = current.items.filterNot { it.continueWatchingItem?.id == item.id }
            current.copy(
                items = remainingItems,
                statusMessage = if (remainingItems.isEmpty()) "" else "Removing ${item.title}...",
            )
        }

        viewModelScope.launch {
            val removalResult =
                withContext(Dispatchers.IO) {
                    when {
                        item.isUpNextPlaceholder -> {
                            com.crispy.tv.player.WatchHistoryResult(
                                statusMessage = "Removed ${item.title} from Continue Watching.",
                            )
                        }

                        item.provider == WatchProvider.LOCAL -> {
                            if (item.progressPercent < 100.0) {
                                watchHistoryService.removeLocalWatchProgress(item.toPlaybackIdentity())
                            } else {
                                watchHistoryService.unmarkWatched(
                                    request = item.toUnmarkRequest(),
                                    source = item.provider,
                                )
                            }
                        }

                        !item.providerPlaybackId.isNullOrBlank() -> {
                            watchHistoryService.removeFromPlayback(
                                playbackId = item.providerPlaybackId,
                                source = item.provider,
                            )
                        }

                        else -> {
                            com.crispy.tv.player.WatchHistoryResult(
                                statusMessage = "Removed ${item.title} from Continue Watching.",
                            )
                        }
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

    private suspend fun loadPrimarySnapshot(): HomePrimarySnapshot {
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
        val enrichedCatalogSections = enrichCollectionShelfPreviewLogos(catalogSections)

        val selectedId =
            _heroState.value.selectedId?.takeIf { id -> heroItems.any { it.id == id } }
                ?: heroItems.firstOrNull()?.id

        return HomePrimarySnapshot(
            hero = HeroState(
                items = heroItems,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = primaryFeedResult.heroResult.statusMessage,
            ),
            headerPills = headerPills,
            catalogSections = enrichedCatalogSections,
            catalogStatusMessage = primaryFeedResult.sectionsStatusMessage,
        )
    }

    private suspend fun enrichCollectionShelfPreviewLogos(
        catalogSections: List<HomeCatalogSectionUi>,
    ): List<HomeCatalogSectionUi> {
        if (catalogSections.isEmpty()) {
            return emptyList()
        }

        val collectionItemsToEnrich =
            catalogSections
                .asSequence()
                .filter { it.section.presentation == HomeCatalogPresentation.COLLECTION_SHELF }
                .mapNotNull { sectionUi ->
                    sectionUi.items.firstOrNull()?.takeIf { item ->
                        item.logoUrl.isNullOrBlank() && item.id.isNotBlank()
                    }
                }
                .distinctBy { item -> collectionLogoCacheKey(item.type, item.id) }
                .toList()

        if (collectionItemsToEnrich.isEmpty()) {
            return catalogSections
        }

        val logosByKey =
            withContext(Dispatchers.IO) {
                coroutineScope {
                    collectionItemsToEnrich.map { item ->
                        async {
                            val logoUrl =
                                try {
                                    tmdbEnrichmentRepository
                                        .loadArtwork(
                                            rawId = item.id,
                                            mediaTypeHint = item.type.toMetadataLabMediaType(),
                                        )
                                        ?.logoUrl
                                        ?.trim()
                                        ?.ifBlank { null }
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    Log.w(TAG, "Failed to load collection logo for ${item.id}", error)
                                    null
                                }
                            collectionLogoCacheKey(item.type, item.id) to logoUrl
                        }
                    }.awaitAll().toMap()
                }
            }

        return catalogSections.map { sectionUi ->
            if (sectionUi.section.presentation != HomeCatalogPresentation.COLLECTION_SHELF) {
                return@map sectionUi
            }

            val featuredItem = sectionUi.items.firstOrNull() ?: return@map sectionUi
            val logoUrl = logosByKey[collectionLogoCacheKey(featuredItem.type, featuredItem.id)]
            if (logoUrl.isNullOrBlank()) {
                sectionUi
            } else {
                sectionUi.copy(
                    items = listOf(featuredItem.copy(logoUrl = logoUrl)) + sectionUi.items.drop(1),
                )
            }
        }
    }

    private suspend fun loadWatchActivitySnapshot(
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
                            limit = HOME_PROVIDER_CONTINUE_WATCHING_LIMIT,
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
                    continueWatching =
                        defaultWideRailSection(
                            key = CONTINUE_WATCHING_SECTION_KEY,
                            title = "Continue Watching",
                            kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                        ).copy(
                            isLoading = false,
                            statusMessage = message,
                        ),
                    upNext =
                        defaultWideRailSection(
                            key = UP_NEXT_SECTION_KEY,
                            title = "Up Next",
                            kind = HomeWideRailSectionKind.UP_NEXT,
                        ).copy(isLoading = false),
                )
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)

        val nowMs = System.currentTimeMillis()
        val railItems = continueWatchingResult.items
        val continueWatchingItems = railItems.filterNot { it.isUpNextPlaceholder }
        val upNextItems = railItems.filter { it.isUpNextPlaceholder }

        return HomeWatchActivitySnapshot(
            continueWatching =
                defaultWideRailSection(
                    key = CONTINUE_WATCHING_SECTION_KEY,
                    title = "Continue Watching",
                    kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
                ).copy(
                    items = continueWatchingItems.map { item -> item.toWideRailItem(nowMs) },
                    isLoading = false,
                    statusMessage =
                        continueWatchingResult.statusMessage.takeIf {
                            continueWatchingItems.isNotEmpty() || continueWatchingResult.isError
                        }.orEmpty(),
                ),
            upNext =
                defaultWideRailSection(
                    key = UP_NEXT_SECTION_KEY,
                    title = "Up Next",
                    kind = HomeWideRailSectionKind.UP_NEXT,
                ).copy(
                    items = upNextItems.map { item -> item.toWideRailItem(nowMs) },
                    isLoading = false,
                ),
        )
    }

    private suspend fun loadThisWeekSection(): HomeWideRailSectionUi {
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
                return defaultWideRailSection(
                    key = THIS_WEEK_SECTION_KEY,
                    title = "This Week",
                    kind = HomeWideRailSectionKind.THIS_WEEK,
                ).copy(
                    isLoading = false,
                    statusMessage = error.message ?: "Failed to load this week.",
                )
            }

        delayForMinimumSkeletonVisibility(sectionLoadStartedAt)

        return defaultWideRailSection(
            key = THIS_WEEK_SECTION_KEY,
            title = "This Week",
            kind = HomeWideRailSectionKind.THIS_WEEK,
        ).copy(
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
                    limit = HOME_PROVIDER_CONTINUE_WATCHING_LIMIT,
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

    private fun applyPrimarySnapshot(snapshot: HomePrimarySnapshot) {
        primarySnapshot = snapshot
        _heroState.value = snapshot.hero
        _headerPillsState.value = snapshot.headerPills
        catalogStatusMessage = snapshot.catalogStatusMessage

        catalogSectionLayoutMeta =
            snapshot.catalogSections.map { sectionUi ->
                CatalogSectionLayoutMeta(
                    key = sectionUi.section.key,
                    presentation = sectionUi.section.presentation,
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
        val wideRails = _wideRailSectionsState.value
        val blocks = mutableListOf<HomeContentSectionUi>()

        listOf(
            CONTINUE_WATCHING_SECTION_KEY,
            UP_NEXT_SECTION_KEY,
            THIS_WEEK_SECTION_KEY,
        ).forEach { key ->
            val section = wideRails[key] ?: return@forEach
            if (section.isVisible()) {
                blocks += HomeWideRailLayoutUi(key = section.key, kind = section.kind)
            }
        }

        if (catalogSectionLayoutMeta.isEmpty() && catalogStatusMessage.isNotBlank()) {
            blocks += HomeStatusSectionUi(
                key = CATALOG_STATUS_SECTION_KEY,
                statusMessage = catalogStatusMessage,
            )
        }

        var index = 0
        while (index < catalogSectionLayoutMeta.size) {
            val sectionMeta = catalogSectionLayoutMeta[index]
            if (sectionMeta.presentation == HomeCatalogPresentation.COLLECTION_SHELF) {
                val groupedKeys = mutableListOf<String>()
                while (
                    index < catalogSectionLayoutMeta.size &&
                        catalogSectionLayoutMeta[index].presentation == HomeCatalogPresentation.COLLECTION_SHELF
                ) {
                    groupedKeys += catalogSectionLayoutMeta[index].key
                    index += 1
                }
                blocks += HomeCollectionShelfSectionUi(
                    key = groupedKeys.joinToString(separator = ":", prefix = "collections:"),
                    sectionKeys = groupedKeys,
                )
            } else {
                blocks += HomeCatalogRowSectionUi(
                    key = sectionMeta.key,
                    sectionKey = sectionMeta.key,
                )
                index += 1
            }
        }

        _layoutState.value = HomeLayoutState(blocks = blocks)
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

private fun defaultWideRailSection(
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

private fun HomeWideRailSectionUi.isVisible(): Boolean {
    return isLoading || items.isNotEmpty() || statusMessage.isNotBlank()
}

private fun ContinueWatchingItem.sectionKey(): String {
    return if (isUpNextPlaceholder) UP_NEXT_SECTION_KEY else CONTINUE_WATCHING_SECTION_KEY
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
    val supportingText =
        when {
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

private fun collectionLogoCacheKey(type: String, contentId: String): String {
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
        episode = episode,
    )
}

private fun String.toMetadataLabMediaType(): MetadataLabMediaType {
    return if (equals("series", ignoreCase = true)) {
        MetadataLabMediaType.SERIES
    } else {
        MetadataLabMediaType.MOVIE
    }
}
