package com.crispy.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.domain.watch.WatchSyncEffect
import com.crispy.tv.watchhistory.sync.WatchSyncSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
private const val TAG = "HomeViewModel"

/**
 * The home screen's viewmodel. It took a single `appContext: Context` and built
 * every collaborator in the factory below -- the same composition-root shape as
 * the other ported viewmodels. What crosses the source-set line:
 *
 * - `refreshCoordinator`, `watchHistoryService` and `suppressionStore` are all
 *   `commonMain` now (the coordinator moved outright; the service was already
 *   an interface; the store was replanted on `KeyValueStore`).
 * - `backendResolver` answers the context the sync channel needs; the
 *   `CrispyBackendClient` lazy is gone, because its only use was the channel's
 *   base URL, which the factory closes over instead.
 * - `watchSyncFactory` builds the channel from a resolved context. A shared
 *   interface would have had exactly one implementation used from one side of
 *   the line, so `stashHandoff`'s rule applies: a function slot, not a port.
 * - `timeSource`, `logger` and `ioDispatcher` are the established seams.
 */
class HomeViewModel internal constructor(
    private val refreshCoordinator: HomeRefreshCoordinator,
    private val watchHistoryService: WatchHistoryService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
    private val backendResolver: BackendContextResolver,
    private val watchSyncFactory: (
        accessToken: String,
        profileId: String,
        onEffect: (WatchSyncEffect) -> Unit,
    ) -> WatchSyncSource,
    private val timeSource: TimeSource,
    private val logger: AppLogger,
    /**
     * No default, deliberately.
     *
     * `Dispatchers.IO` does not exist in `commonMain`: on Kotlin/Native it is `internal`,
     * so a file that defaults to it does not compile for iOS at all. Defaulting to
     * `Dispatchers.Default` instead would be worse than not compiling -- it would put
     * blocking work on a CPU-sized pool and look correct. The caller is the composition
     * root, where `Dispatchers.IO` does exist and is the right answer.
     */
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
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

    init {
        viewModelScope.launch {
            HomeRefreshBus.events.collect { event ->
                when (event) {
                    HomeRefreshEvent.PlaybackEnded,
                    HomeRefreshEvent.WatchlistChanged,
                    HomeRefreshEvent.HistoryChanged,
                    -> {
                        refreshWatchActivityAndThisWeek()
                    }
                    HomeRefreshEvent.RatingsChanged -> Unit
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
                async { refreshPrimaryHomeIfStale() }
                async { loadContinueWatching() }
                async { loadUpNext() }
                async { loadThisWeek() }
            }
            initialLoadJob = null
            }
    }

    fun onHomeVisible() {
        viewModelScope.launch(ioDispatcher) {
            val context = backendResolver.resolve() ?: return@launch
            watchSyncSource?.close()
            watchSyncSource =
                watchSyncFactory(
                    context.accessToken,
                    context.profileId,
                    { effect ->
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
            refreshPrimaryHomeIfStale()
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

    private suspend fun refreshPrimaryHomeIfStale() {
        val expiresAtMs = runCatching { refreshCoordinator.cachedHomeExpiresAtMs() }.getOrNull()
        val nowMs = timeSource.nowMs()
        if (expiresAtMs == null || nowMs >= expiresAtMs) {
            loadPrimary()
        }
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
                withContext(ioDispatcher) {
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
            logger.warn(TAG, "Primary home feed load failed", error)
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
            CatalogSectionLayoutMeta(
                key = sectionUi.section.key,
                layout = sectionUi.section.layout,
                isTopTen = isTop10ListKey(sectionUi.section.kind),
            )
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
        val now = timeSource.nowMs()
        keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { key -> suppressionMap[key] = now }
        suppressionStore.write(suppressionMap)
    }
}

