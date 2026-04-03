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
    val items: List<LibrarySectionItemUi>,
)

@Immutable
data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val sections: List<LibrarySectionUi> = emptyList(),
    val selectedSectionId: String? = null,
)

class LibraryViewModel internal constructor(
    private val supabase: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backend: CrispyBackendClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isRefreshing = true) }

                val sections = withContext(Dispatchers.IO) {
                    runCatching { loadLibrarySections() }.getOrNull()
                }

                if (sections == null) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            statusMessage = "Library temporarily unavailable.",
                        )
                    }
                    return@launch
                }

                val sectionUis = sections.map { section ->
                    LibrarySectionUi(
                        id = section.id,
                        label = section.label,
                        itemCount = section.itemCount,
                        items = section.items.map { item ->
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
                    )
                }

                val selectedId = _uiState.value.selectedSectionId
                    ?.takeIf { id -> sectionUis.any { it.id == id } }
                    ?: sectionUis.firstOrNull()?.id

                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        statusMessage = if (sectionUis.isEmpty()) "No library sections available." else "",
                        sections = sectionUis,
                        selectedSectionId = selectedId,
                    )
                }
            }
    }

    fun selectSection(sectionId: String) {
        _uiState.update { current ->
            val nextId = if (current.selectedSectionId == sectionId) null else sectionId
            current.copy(selectedSectionId = nextId)
        }
    }

    private suspend fun loadLibrarySections(): List<CrispyBackendClient.LibrarySection> {
        val backendContext = getBackendContext() ?: return emptyList()
        val response = backend.getProfileLibrary(
            accessToken = backendContext.accessToken,
            profileId = backendContext.profileId,
        )
        return response.sections
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryRouteContent(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onItemClick: (LibrarySectionItemUi) -> Unit,
    onSelectSection: (String) -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
) {
    val sections = uiState.sections
    val selectedSectionId = uiState.selectedSectionId
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId }
    val items = selectedSection?.items.orEmpty()
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
                if (uiState.statusMessage.isNotBlank()) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-empty") {
                    val emptyMessage =
                        if (sections.isEmpty()) "No library sections available."
                        else "No items in ${selectedSection?.label ?: "this section"} yet."
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
            }
        }
    }
}
