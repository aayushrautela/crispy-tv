package com.crispy.tv.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.ui.LocalAppChromeInsets
import com.crispy.tv.ui.rememberInsetPadding
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class LibrarySource {
    LOCAL,
    TRAKT,
    SIMKL,
}

@Immutable
private data class LibraryArtwork(
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
)

@Immutable
data class LibraryLocalItemUi(
    val entry: WatchHistoryEntry,
    val posterUrl: String?,
    val backdropUrl: String?,
) {
    val stableKey: String
        get() = "local:${entry.contentType}:${entry.contentId}"
}

@Immutable
data class LibraryProviderItemUi(
    val item: ProviderLibraryItem,
    val watchHistoryEntry: WatchHistoryEntry,
    val posterUrl: String?,
    val backdropUrl: String?,
) {
    val stableKey: String
        get() = "${item.provider.name}:${item.folderId}:${item.contentType}:${item.contentId}"
}

@Immutable
data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val localItems: List<LibraryLocalItemUi> = emptyList(),
    val providerFolders: List<ProviderLibraryFolder> = emptyList(),
    val providerItems: List<LibraryProviderItemUi> = emptyList(),
    val authState: WatchProviderAuthState = WatchProviderAuthState(),
    val selectedSource: LibrarySource = LibrarySource.LOCAL,
    val selectedProviderFolderId: String? = null,
)

private data class LibraryRefreshPayload(
    val authState: WatchProviderAuthState,
    val selectedSource: LibrarySource,
    val localItems: List<LibraryLocalItemUi>,
    val providerFolders: List<ProviderLibraryFolder>,
    val providerItems: List<LibraryProviderItemUi>,
    val statusMessage: String,
)

private data class ArtworkRequest(
    val cacheKey: String,
    val rawId: String,
    val posterUrl: String?,
    val backdropUrl: String?,
)

class LibraryViewModel internal constructor(
    private val watchHistoryService: WatchHistoryService,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private val artworkCache = LinkedHashMap<String, LibraryArtwork>()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob =
            viewModelScope.launch {
                _uiState.update { current ->
                    current.copy(
                        isRefreshing = true,
                        statusMessage = current.statusMessage.ifBlank { "Loading library..." },
                    )
                }

                val authState = watchHistoryService.authState()
                val selectedSource = resolveSelectedSource(authState)
                val selectedProvider = selectedSource.toProvider()
                val providerAuthenticated =
                    when (selectedProvider) {
                        WatchProvider.TRAKT -> authState.traktAuthenticated
                        WatchProvider.SIMKL -> authState.simklAuthenticated
                        else -> false
                    }

                val localResult =
                    if (selectedSource == LibrarySource.LOCAL) {
                        withContext(Dispatchers.IO) {
                            runCatching { watchHistoryService.listLocalHistory(limit = 100) }.getOrNull()
                        }
                    } else {
                        null
                    }

                val cachedProviderResult =
                    if (selectedProvider != null && providerAuthenticated) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                watchHistoryService.getCachedProviderLibrary(
                                    limitPerFolder = 250,
                                    source = selectedProvider,
                                )
                            }.getOrNull()?.takeIf { snapshot ->
                                snapshot.folders.isNotEmpty() || snapshot.items.isNotEmpty()
                            }
                        }
                    } else {
                        null
                    }

                if (selectedSource != LibrarySource.LOCAL && cachedProviderResult != null) {
                    val cachedPayload =
                        buildRefreshPayload(
                            authState = authState,
                            selectedSource = selectedSource,
                            localEntries = emptyList(),
                            providerFolders = cachedProviderResult.folders,
                            providerItems = cachedProviderResult.items,
                            providerAuthenticated = providerAuthenticated,
                            providerStatusMessage = cachedProviderResult.statusMessage,
                        )
                    applyPayload(cachedPayload, isRefreshing = true)
                }

                val providerResult =
                    if (selectedProvider != null && providerAuthenticated) {
                        val networkResult =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    watchHistoryService.listProviderLibrary(
                                        limitPerFolder = 250,
                                        source = selectedProvider,
                                    )
                                }.getOrNull()
                            }
                        networkResult ?: cachedProviderResult
                    } else {
                        cachedProviderResult
                    }

                val finalPayload =
                    buildRefreshPayload(
                        authState = authState,
                        selectedSource = selectedSource,
                        localEntries = localResult?.entries.orEmpty().sortedByDescending { it.watchedAtEpochMs },
                        providerFolders = providerResult?.folders.orEmpty(),
                        providerItems = providerResult?.items.orEmpty(),
                        providerAuthenticated = providerAuthenticated,
                        providerStatusMessage = providerResult?.statusMessage,
                    )
                applyPayload(finalPayload, isRefreshing = false)
            }
    }

    fun selectProviderFolder(folderId: String) {
        _uiState.update { current ->
            val nextFolderId = if (current.selectedProviderFolderId == folderId) null else folderId
            current.copy(selectedProviderFolderId = nextFolderId)
        }
    }

    private suspend fun buildRefreshPayload(
        authState: WatchProviderAuthState,
        selectedSource: LibrarySource,
        localEntries: List<WatchHistoryEntry>,
        providerFolders: List<ProviderLibraryFolder>,
        providerItems: List<ProviderLibraryItem>,
        providerAuthenticated: Boolean,
        providerStatusMessage: String?,
    ): LibraryRefreshPayload {
        val localUiItems = if (selectedSource == LibrarySource.LOCAL) enrichLocalItems(localEntries) else emptyList()
        val providerUiItems =
            if (selectedSource == LibrarySource.LOCAL) {
                emptyList()
            } else {
                enrichProviderItems(providerItems)
            }

        val statusMessage =
            when (selectedSource) {
                LibrarySource.LOCAL -> {
                    if (localUiItems.isEmpty()) {
                        "No local history yet."
                    } else {
                        "Recently watched"
                    }
                }
                LibrarySource.TRAKT,
                LibrarySource.SIMKL -> {
                    providerStatusMessage
                        ?: if (!providerAuthenticated) {
                            "Connect ${selectedSource.displayName()} in Settings to load this provider."
                        } else {
                            "No provider library data available."
                        }
                }
            }

        return LibraryRefreshPayload(
            authState = authState,
            selectedSource = selectedSource,
            localItems = localUiItems,
            providerFolders = providerFolders,
            providerItems = providerUiItems,
            statusMessage = statusMessage,
        )
    }

    private fun applyPayload(payload: LibraryRefreshPayload, isRefreshing: Boolean) {
        _uiState.update { current ->
            val availableFolders = payload.providerFolders.filter { it.provider == payload.selectedSource.toProvider() }
            current.copy(
                isRefreshing = isRefreshing,
                statusMessage = payload.statusMessage,
                localItems = payload.localItems,
                providerFolders = payload.providerFolders,
                providerItems = payload.providerItems,
                authState = payload.authState,
                selectedSource = payload.selectedSource,
                selectedProviderFolderId = resolveSelectedFolderId(
                    current.selectedProviderFolderId,
                    payload.selectedSource,
                    availableFolders,
                ),
            )
        }
    }

    private suspend fun enrichLocalItems(entries: List<WatchHistoryEntry>): List<LibraryLocalItemUi> {
        if (entries.isEmpty()) {
            return emptyList()
        }
        val artworkByKey =
            resolveArtwork(
                entries.map { entry ->
                    ArtworkRequest(
                        cacheKey = artworkCacheKey(entry.contentId, entry.contentType.name),
                        rawId = entry.contentId,
                        posterUrl = null,
                        backdropUrl = null,
                    )
                },
            )
        return entries.map { entry ->
            val artwork = artworkByKey[artworkCacheKey(entry.contentId, entry.contentType.name)] ?: LibraryArtwork()
            LibraryLocalItemUi(
                entry = entry,
                posterUrl = artwork.posterUrl,
                backdropUrl = artwork.backdropUrl,
            )
        }
    }

    private suspend fun enrichProviderItems(items: List<ProviderLibraryItem>): List<LibraryProviderItemUi> {
        if (items.isEmpty()) {
            return emptyList()
        }
        val artworkByKey =
            resolveArtwork(
                items.map { item ->
                    ArtworkRequest(
                        cacheKey = artworkCacheKey(item.contentId, item.contentType.name),
                        rawId = item.contentId,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                    )
                },
            )
        return items.map { item ->
            val artwork = artworkByKey[artworkCacheKey(item.contentId, item.contentType.name)] ?: LibraryArtwork()
            LibraryProviderItemUi(
                item = item,
                watchHistoryEntry =
                    WatchHistoryEntry(
                        contentId = item.contentId,
                        contentType = item.contentType,
                        title = item.title,
                        season = item.season,
                        episode = item.episode,
                        watchedAtEpochMs = item.addedAtEpochMs,
                    ),
                posterUrl = artwork.posterUrl,
                backdropUrl = artwork.backdropUrl,
            )
        }
    }

    private suspend fun resolveArtwork(requests: List<ArtworkRequest>): Map<String, LibraryArtwork> {
        if (requests.isEmpty()) {
            return emptyMap()
        }
        val uniqueRequests = LinkedHashMap<String, ArtworkRequest>()
        requests.forEach { request ->
            val merged =
                uniqueRequests[request.cacheKey]?.let { existing ->
                    existing.copy(
                        posterUrl = existing.posterUrl ?: request.posterUrl,
                        backdropUrl = existing.backdropUrl ?: request.backdropUrl,
                    )
                } ?: request
            uniqueRequests[request.cacheKey] = merged
        }

        val semaphore = Semaphore(6)
        return coroutineScope {
            uniqueRequests.values.map { request ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val artwork = resolveArtworkForRequest(request)
                        request.cacheKey to artwork
                    }
                }
            }.awaitAll().toMap()
        }
    }

    private suspend fun resolveArtworkForRequest(request: ArtworkRequest): LibraryArtwork {
        val providedArtwork =
            LibraryArtwork(
                posterUrl = request.posterUrl?.trim().takeIf { !it.isNullOrBlank() },
                backdropUrl = request.backdropUrl?.trim().takeIf { !it.isNullOrBlank() },
            )
        if (providedArtwork.posterUrl != null && providedArtwork.backdropUrl != null) {
            artworkCache[request.cacheKey] = providedArtwork
            return providedArtwork
        }

        val cachedArtwork = artworkCache[request.cacheKey]
        if (cachedArtwork != null) {
            return LibraryArtwork(
                posterUrl = providedArtwork.posterUrl ?: cachedArtwork.posterUrl,
                backdropUrl = providedArtwork.backdropUrl ?: cachedArtwork.backdropUrl,
            )
        }

        val rawId = request.rawId.trim()
        val canResolve = rawId.startsWith("tt") || rawId.startsWith("tmdb:")
        if (!canResolve) {
            return providedArtwork
        }

        val details = runCatching { tmdbEnrichmentRepository.loadArtwork(rawId = rawId) }.getOrNull()
        val resolvedArtwork =
            LibraryArtwork(
                posterUrl = providedArtwork.posterUrl ?: details?.posterUrl,
                backdropUrl = providedArtwork.backdropUrl ?: details?.backdropUrl,
            )
        artworkCache[request.cacheKey] = resolvedArtwork
        return resolvedArtwork
    }

    private fun resolveSelectedSource(authState: WatchProviderAuthState): LibrarySource {
        return when {
            authState.traktAuthenticated -> LibrarySource.TRAKT
            authState.simklAuthenticated -> LibrarySource.SIMKL
            else -> LibrarySource.LOCAL
        }
    }

    private fun resolveSelectedFolderId(
        currentFolderId: String?,
        selectedSource: LibrarySource,
        folders: List<ProviderLibraryFolder>,
    ): String? {
        val provider = selectedSource.toProvider() ?: return null
        return currentFolderId
            ?.takeIf { selectedId -> folders.any { folder -> folder.provider == provider && folder.id == selectedId } }
            ?: folders.firstOrNull { folder -> folder.provider == provider }?.id
    }

    private fun artworkCacheKey(contentId: String, contentType: String): String {
        return "${contentType.trim().lowercase()}:${contentId.trim().lowercase()}"
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LibraryViewModel(
                            watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext),
                            tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryRouteContent(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onItemClick: (WatchHistoryEntry) -> Unit,
    onNavigateToDiscover: () -> Unit,
    onSelectProviderFolder: (String) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val selectedProvider = uiState.selectedSource.toProvider()
    val providerFolders = uiState.providerFolders.filter { folder -> folder.provider == selectedProvider }
    val providerAuthenticated =
        when (uiState.selectedSource) {
            LibrarySource.LOCAL -> false
            LibrarySource.TRAKT -> uiState.authState.traktAuthenticated
            LibrarySource.SIMKL -> uiState.authState.simklAuthenticated
        }
    val selectedFolder = uiState.selectedProviderFolderId
    val providerItems =
        uiState.providerItems
            .filter { item -> item.item.provider == selectedProvider }
            .filter { item -> selectedFolder == null || item.item.folderId == selectedFolder }
    val pullToRefreshState = rememberPullToRefreshState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val appChromeInsets = LocalAppChromeInsets.current
    val gridState = rememberLazyGridState()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) {
            gridState.animateScrollToItem(0)
            onScrollToTopConsumed()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
        indicator = {
            Indicator(
                state = pullToRefreshState,
                isRefreshing = uiState.isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(appChromeInsets.asPaddingValues()),
            )
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = rememberInsetPadding(
                windowInsets = appChromeInsets,
                horizontal = pageHorizontalPadding,
                top = 12.dp,
                bottom = 12.dp + Dimensions.PageBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                    if (uiState.selectedSource != LibrarySource.LOCAL && providerAuthenticated && providerFolders.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(providerFolders, key = { it.id }) { folder ->
                                FilterChip(
                                    selected = folder.id == selectedFolder,
                                    onClick = { onSelectProviderFolder(folder.id) },
                                    label = { Text("${folder.label} (${folder.itemCount})") },
                                )
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                    if (uiState.statusMessage.isNotBlank()) {
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (uiState.selectedSource) {
                    LibrarySource.LOCAL -> {
                        if (uiState.localItems.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "local-empty") {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.padding(Dimensions.ListItemPadding)) {
                                        androidx.compose.foundation.layout.Column(
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Text(
                                                text = "Nothing here yet",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                text = "Start watching from Discover or Home and your recent local history will appear here.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            FilledTonalButton(onClick = onNavigateToDiscover) {
                                                Text("Go to Discover")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            gridItems(
                                items = uiState.localItems,
                                key = { item -> item.stableKey },
                            ) { item ->
                                PosterCard(
                                    title = item.entry.title.ifBlank { item.entry.contentId },
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    rating = null,
                                    year = null,
                                    genre = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onItemClick(item.entry) },
                                )
                            }
                        }
                    }

                    LibrarySource.TRAKT,
                    LibrarySource.SIMKL -> {
                        if (!providerAuthenticated) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "provider-auth") {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.padding(Dimensions.ListItemPadding)) {
                                        Text(
                                            text = "Connect ${uiState.selectedSource.displayName()} in Settings to load this provider.",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        } else if (providerItems.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "provider-empty") {
                                val emptyMessage =
                                    if (providerFolders.isEmpty()) {
                                        "No provider library data available."
                                    } else {
                                        val label = providerFolders.firstOrNull { it.id == selectedFolder }?.label ?: "this folder"
                                        "No items in $label yet."
                                    }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.padding(Dimensions.ListItemPadding)) {
                                        Text(
                                            text = emptyMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        } else {
                            gridItems(
                                items = providerItems,
                                key = { item -> item.stableKey },
                            ) { item ->
                                PosterCard(
                                    title = item.item.title.ifBlank { item.item.contentId },
                                    posterUrl = item.posterUrl,
                                    backdropUrl = item.backdropUrl,
                                    rating = null,
                                    year = null,
                                    genre = null,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onItemClick(item.watchHistoryEntry) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LibrarySource.toProvider(): WatchProvider? {
    return when (this) {
        LibrarySource.LOCAL -> null
        LibrarySource.TRAKT -> WatchProvider.TRAKT
        LibrarySource.SIMKL -> WatchProvider.SIMKL
    }
}

private fun LibrarySource.displayName(): String {
    return when (this) {
        LibrarySource.LOCAL -> "Local"
        LibrarySource.TRAKT -> "Trakt"
        LibrarySource.SIMKL -> "Simkl"
    }
}
