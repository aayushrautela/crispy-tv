package com.crispy.tv.library

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIBRARY_PAGE_SIZE = 60

@Immutable
data class LibrarySectionItemUi(
    val id: String,
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addedAt: String?,
    val watchedAt: String?,
    val ratedAt: String?,
    val rating: Int?,
    val lastActivityAt: String?,
    val origins: List<String>,
) {
    val stableKey: String
        get() = id
}

@Immutable
data class LibrarySectionUi(
    val id: String,
    val label: String,
    val itemCount: Int,
)

@Immutable
data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val isLoadingSection: Boolean = false,
    val isLoadingMore: Boolean = false,
    val statusMessage: String = "",
    val sectionStatusMessage: String = "",
    val sections: List<LibrarySectionUi> = emptyList(),
    val selectedSectionId: String? = null,
    val sectionItems: List<LibrarySectionItemUi> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

class LibraryViewModel internal constructor(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: CrispyBackendClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var refreshJob: Job? = null
    private var sectionJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        statusMessage = if (it.sections.isEmpty()) "" else it.statusMessage,
                    )
                }

                val result = withContext(Dispatchers.IO) { runCatching { loadLibraryDiscovery() } }
                val discovery = result.getOrNull()
                if (discovery == null) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            statusMessage = "Library temporarily unavailable.",
                        )
                    }
                    return@launch
                }

                val previousSelection = _uiState.value.selectedSectionId
                val selectedSectionId = previousSelection?.takeIf { id -> discovery.any { it.id == id } }
                    ?: discovery.firstOrNull()?.id

                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        statusMessage = if (discovery.isEmpty()) "No library sections available." else "",
                        sections = discovery,
                        selectedSectionId = selectedSectionId,
                        sectionItems = if (selectedSectionId == it.selectedSectionId) it.sectionItems else emptyList(),
                        sectionStatusMessage = if (selectedSectionId == it.selectedSectionId) it.sectionStatusMessage else "",
                        isLoadingMore = false,
                        nextCursor = if (selectedSectionId == it.selectedSectionId) it.nextCursor else null,
                        hasMore = if (selectedSectionId == it.selectedSectionId) it.hasMore else false,
                    )
                }

                if (selectedSectionId == null) {
                    _uiState.update {
                        it.copy(
                            sectionItems = emptyList(),
                            isLoadingSection = false,
                            isLoadingMore = false,
                            sectionStatusMessage = "",
                            nextCursor = null,
                            hasMore = false,
                        )
                    }
                    return@launch
                }

                loadSelectedSection(reset = true)
            }
    }

    fun selectSection(sectionId: String) {
        val normalized = sectionId.trim()
        if (normalized.isEmpty()) return
        val current = _uiState.value
        if (current.selectedSectionId == normalized) return

        _uiState.update {
            it.copy(
                selectedSectionId = normalized,
                sectionItems = emptyList(),
                sectionStatusMessage = "",
                isLoadingSection = false,
                isLoadingMore = false,
                nextCursor = null,
                hasMore = false,
            )
        }
        loadSelectedSection(reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingSection || state.isLoadingMore || !state.hasMore) return
        if (state.selectedSectionId.isNullOrBlank()) return
        if (state.nextCursor.isNullOrBlank()) return
        loadSelectedSection(reset = false)
    }

    private fun loadSelectedSection(reset: Boolean) {
        val state = _uiState.value
        val sectionId = state.selectedSectionId ?: return
        if (reset) {
            sectionJob?.cancel()
            loadMoreJob?.cancel()
        }
        val activeJob = if (reset) sectionJob else loadMoreJob
        if (activeJob?.isActive == true) return

        val job =
            viewModelScope.launch {
                val before = _uiState.value
                val cursor = if (reset) null else before.nextCursor
                if (!reset && cursor.isNullOrBlank()) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoadingSection = if (reset) true else it.isLoadingSection,
                        isLoadingMore = if (reset) false else true,
                        sectionStatusMessage = if (reset && it.sectionItems.isEmpty()) "Loading..." else it.sectionStatusMessage,
                        sectionItems = if (reset) emptyList() else it.sectionItems,
                        nextCursor = if (reset) null else it.nextCursor,
                        hasMore = if (reset) false else it.hasMore,
                    )
                }

                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        loadLibrarySectionPage(
                            sectionId = sectionId,
                            limit = LIBRARY_PAGE_SIZE,
                            cursor = cursor,
                        )
                    }
                }
                val page = result.getOrNull()
                val currentState = _uiState.value
                if (currentState.selectedSectionId != sectionId) {
                    return@launch
                }

                if (page == null) {
                    _uiState.update {
                        it.copy(
                            isLoadingSection = false,
                            isLoadingMore = false,
                            sectionStatusMessage = if (it.sectionItems.isEmpty()) {
                                "Failed to load ${selectedSectionLabel(it, sectionId)}."
                            } else {
                                "Failed to load more items."
                            },
                        )
                    }
                    return@launch
                }

                val mergedItems = if (reset) {
                    page.items
                } else {
                    mergeSectionItems(currentState.sectionItems, page.items)
                }
                val statusMessage = when {
                    mergedItems.isEmpty() -> "No items in ${selectedSectionLabel(currentState, sectionId)} yet."
                    else -> ""
                }

                _uiState.update {
                    it.copy(
                        isLoadingSection = false,
                        isLoadingMore = false,
                        sectionItems = mergedItems,
                        sectionStatusMessage = statusMessage,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                    )
                }
            }

        if (reset) {
            sectionJob = job
        } else {
            loadMoreJob = job
        }
    }

    private suspend fun loadLibraryDiscovery(): List<LibrarySectionUi> {
        val backendContext = getBackendContext() ?: return emptyList()
        val response = backend.getProfileLibrary(
            accessToken = backendContext.accessToken,
            profileId = backendContext.profileId,
        )
        return response.sections
            .sortedBy { it.order }
            .map { section ->
                LibrarySectionUi(
                    id = section.id,
                    label = section.label,
                    itemCount = section.itemCount,
                )
            }
    }

    private suspend fun loadLibrarySectionPage(
        sectionId: String,
        limit: Int,
        cursor: String?,
    ): LibrarySectionPageUi {
        val backendContext = getBackendContext() ?: return LibrarySectionPageUi()
        val response = backend.getProfileLibrarySectionPage(
            accessToken = backendContext.accessToken,
            profileId = backendContext.profileId,
            sectionId = sectionId,
            limit = limit,
            cursor = cursor,
        )
        return LibrarySectionPageUi(
            items = response.items.map { item ->
                LibrarySectionItemUi(
                    id = item.id,
                    mediaKey = item.media.mediaKey,
                    mediaType = item.media.mediaType,
                    title = item.media.title,
                    posterUrl = item.media.posterUrl,
                    backdropUrl = item.media.backdropUrl,
                    addedAt = item.state.addedAt,
                    watchedAt = item.state.watchedAt,
                    ratedAt = item.state.ratedAt,
                    rating = item.state.rating,
                    lastActivityAt = item.state.lastActivityAt,
                    origins = item.origins,
                )
            },
            nextCursor = response.pageInfo.nextCursor,
            hasMore = response.pageInfo.hasMore,
        )
    }

    private suspend fun getBackendContext(): BackendContext? {
        if (!supabase.isConfigured() || !backend.isConfigured()) return null
        val session = supabase.ensureValidSession() ?: return null
        var profileId = activeProfileStore.getActiveProfileId(session.userId).orEmpty().trim()
        if (profileId.isBlank()) {
            profileId = try {
                backend.getMe(session.accessToken).profiles.firstOrNull()?.id.orEmpty().trim()
            } catch (_: Throwable) { "" }
            if (profileId.isNotBlank()) {
                activeProfileStore.setActiveProfileId(session.userId, profileId)
            }
        }
        if (profileId.isBlank()) return null
        return BackendContext(accessToken = session.accessToken, profileId = profileId)
    }

    private data class BackendContext(
        val accessToken: String,
        val profileId: String,
    )

    private data class LibrarySectionPageUi(
        val items: List<LibrarySectionItemUi> = emptyList(),
        val nextCursor: String? = null,
        val hasMore: Boolean = false,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LibraryViewModel(
                            supabase = SupabaseServicesProvider.accountClient(appContext),
                            activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                            backend = BackendServicesProvider.backendClient(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

private fun mergeSectionItems(
    existing: List<LibrarySectionItemUi>,
    incoming: List<LibrarySectionItemUi>,
): List<LibrarySectionItemUi> {
    if (existing.isEmpty()) return incoming
    if (incoming.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + incoming.size)
    val merged = ArrayList<LibrarySectionItemUi>(existing.size + incoming.size)
    (existing + incoming).forEach { item ->
        if (seen.add(item.stableKey)) {
            merged += item
        }
    }
    return merged
}

private fun selectedSectionLabel(state: LibraryUiState, sectionId: String): String {
    return state.sections.firstOrNull { it.id == sectionId }?.label ?: "this section"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryRouteContent(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    onLoadMore: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val sections = uiState.sections
    val selectedSectionId = uiState.selectedSectionId
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId }
    val items = uiState.sectionItems
    val pullToRefreshState = rememberPullToRefreshState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 124.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                top = 12.dp,
                end = pageHorizontalPadding,
                bottom = 12.dp + Dimensions.PageBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                if (sections.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sections, key = { it.id }) { section ->
                            FilterChip(
                                selected = section.id == selectedSectionId,
                                onClick = { onSelectSection(section.id) },
                                label = { Text("${section.label} (${section.itemCount})") },
                            )
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                val message = uiState.statusMessage.ifBlank { uiState.sectionStatusMessage }
                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (uiState.isLoadingSection && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Dimensions.ListItemPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            } else if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-empty") {
                    val emptyMessage = when {
                        sections.isEmpty() -> "No library sections available."
                        selectedSection != null -> "No items in ${selectedSection.label} yet."
                        else -> "No library sections available."
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
                    items = items,
                    key = { item -> item.stableKey },
                ) { item ->
                    PosterCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        rating = null,
                        year = null,
                        genre = null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onItemClick(item) },
                    )
                }

                if (uiState.isLoadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "load-more-loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimensions.ListItemPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator()
                        }
                    }
                } else if (uiState.hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "load-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            FilledTonalButton(onClick = onLoadMore) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}
