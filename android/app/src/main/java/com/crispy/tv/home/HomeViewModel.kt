package com.crispy.tv.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.domain.watch.WatchSyncEffect
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

private const val RAIL_LOAD_ATTEMPTS = 3
private const val RAIL_RETRY_BACKOFF_MS = 400L

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
                    onEffect = { effect ->
                        when (effect) {
                            WatchSyncEffect.RefetchContinueWatching,
                            WatchSyncEffect.RefetchHistory,
                            -> refreshWatchActivityAndThisWeek()
                            WatchSyncEffect.RefetchHome -> refreshPrimary()
                            else -> Unit
                        }
                    },
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

    private fun refreshPrimary() {
        viewModelScope.launch { loadPrimary() }
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
            val ready = current.state as? RailLoadState.Ready ?: return@updateWideRailSection current
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
        val section = runCatching { loadWithRetry { refreshCoordinator.loadContinueWatching() } }.getOrNull()
        if (section != null) applyWideRailSection(section)
    }

    private suspend fun loadUpNext() {
        val section = runCatching { loadWithRetry { refreshCoordinator.loadUpNext() } }.getOrNull()
        if (section != null) applyWideRailSection(section)
    }

    private suspend fun loadThisWeek() {
        val section = runCatching { loadWithRetry { refreshCoordinator.loadThisWeekSection() } }.getOrNull()
        if (section != null) applyWideRailSection(section)
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

