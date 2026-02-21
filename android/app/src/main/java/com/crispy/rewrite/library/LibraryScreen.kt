package com.crispy.rewrite.library

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.ProviderLibraryFolder
import com.crispy.rewrite.player.ProviderLibraryItem
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchProviderAuthState
import com.crispy.rewrite.settings.HomeScreenSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySource {
    LOCAL,
    TRAKT,
    SIMKL
}

data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val localEntries: List<WatchHistoryEntry> = emptyList(),
    val providerFolders: List<ProviderLibraryFolder> = emptyList(),
    val providerItems: List<ProviderLibraryItem> = emptyList(),
    val authState: WatchProviderAuthState = WatchProviderAuthState(),
    val selectedSource: LibrarySource = LibrarySource.LOCAL,
    val selectedProviderFolderId: String? = null
)

class LibraryViewModel internal constructor(
    private val watchHistoryService: WatchHistoryLabService,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            LibraryUiState(
                selectedSource = settingsStore.load().watchDataSource.toLibrarySource()
            )
        )
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val selectedSource = settingsStore.load().watchDataSource.toLibrarySource()
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        statusMessage = "Loading library...",
                        selectedSource = selectedSource
                    )
                }

                val authState = watchHistoryService.authState()
                val selectedProvider = selectedSource.toProvider()
                val providerAuthenticated =
                    when (selectedProvider) {
                        WatchProvider.LOCAL,
                        WatchProvider.TRAKT -> authState.traktAuthenticated
                        WatchProvider.SIMKL -> authState.simklAuthenticated
                        null -> false
                    }
                val localResult =
                    if (selectedSource == LibrarySource.LOCAL) {
                        runCatching { watchHistoryService.listLocalHistory(limit = 100) }.getOrNull()
                    } else {
                        null
                    }
                val providerResult =
                    if (selectedProvider != null) {
                        runCatching {
                            watchHistoryService.listProviderLibrary(
                                limitPerFolder = 250,
                                source = selectedProvider
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                val statusMessage =
                    when (selectedSource) {
                        LibrarySource.LOCAL -> localResult?.statusMessage ?: "No local history yet."
                        LibrarySource.TRAKT,
                        LibrarySource.SIMKL -> {
                            providerResult?.statusMessage
                                ?: if (!providerAuthenticated) {
                                    "Connect ${selectedSource.displayName()} in Settings to load this provider."
                                } else {
                                    "No provider library data available."
                                }
                        }
                    }

                _uiState.update { current ->
                    val fallbackFolder = defaultFolderIdFor(selectedSource, providerResult?.folders.orEmpty())
                    current.copy(
                        isRefreshing = false,
                        statusMessage = statusMessage,
                        localEntries = localResult?.entries.orEmpty().sortedByDescending { it.watchedAtEpochMs },
                        providerFolders = providerResult?.folders.orEmpty(),
                        providerItems = providerResult?.items.orEmpty(),
                        authState = authState,
                        selectedSource = selectedSource,
                        selectedProviderFolderId =
                            current.selectedProviderFolderId
                                ?.takeIf { selectedFolderId ->
                                    providerResult?.folders.orEmpty().any { folder -> folder.id == selectedFolderId }
                                }
                                ?: fallbackFolder
                    )
                }
            }
    }

    fun selectSource(source: LibrarySource) {
        settingsStore.save(
            settingsStore.load().copy(
                watchDataSource = source.toWatchProvider()
            )
        )
        _uiState.update { current ->
            current.copy(
                selectedSource = source,
                selectedProviderFolderId = defaultFolderIdFor(source, current.providerFolders)
            )
        }
        refresh()
    }

    fun selectProviderFolder(folderId: String) {
        _uiState.update { it.copy(selectedProviderFolderId = folderId) }
    }

    private fun defaultFolderIdFor(source: LibrarySource, folders: List<ProviderLibraryFolder>): String? {
        val provider = source.toProvider() ?: return null
        return folders.firstOrNull { it.provider == provider }?.id
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LibraryViewModel(
                            watchHistoryService = PlaybackLabDependencies.watchHistoryServiceFactory(appContext),
                            settingsStore = HomeScreenSettingsStore(appContext)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@Composable
fun LibraryRoute(
    onItemClick: (WatchHistoryEntry) -> Unit,
    onNavigateToDiscover: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: LibraryViewModel =
        viewModel(
            factory = remember(appContext) {
                LibraryViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onItemClick = onItemClick,
        onNavigateToDiscover = onNavigateToDiscover,
        onSelectSource = viewModel::selectSource,
        onSelectProviderFolder = viewModel::selectProviderFolder
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onItemClick: (WatchHistoryEntry) -> Unit,
    onNavigateToDiscover: () -> Unit,
    onSelectSource: (LibrarySource) -> Unit,
    onSelectProviderFolder: (String) -> Unit
) {
    val selectedProvider = uiState.selectedSource.toProvider()
    val providerFolders = uiState.providerFolders.filter { folder -> folder.provider == selectedProvider }
    val selectedFolder = uiState.selectedProviderFolderId
    val providerItems =
        uiState.providerItems
            .filter { item -> item.provider == selectedProvider }
            .filter { item -> selectedFolder == null || item.folderId == selectedFolder }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(text = "Library") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.selectedSource == LibrarySource.LOCAL,
                        onClick = { onSelectSource(LibrarySource.LOCAL) },
                        label = { Text("Local") }
                    )
                    FilterChip(
                        selected = uiState.selectedSource == LibrarySource.TRAKT,
                        onClick = { onSelectSource(LibrarySource.TRAKT) },
                        label = { Text("Trakt") }
                    )
                    FilterChip(
                        selected = uiState.selectedSource == LibrarySource.SIMKL,
                        onClick = { onSelectSource(LibrarySource.SIMKL) },
                        label = { Text("Simkl") }
                    )
                }
            }

            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (uiState.selectedSource) {
                LibrarySource.LOCAL -> {
                    if (uiState.localEntries.isEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.padding(16.dp)) {
                                    androidx.compose.foundation.layout.Column(
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "Nothing here yet",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = "Start watching from Discover or Home and your recent local history will appear here.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        FilledTonalButton(onClick = onNavigateToDiscover) {
                                            Text("Go to Discover")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            Text(text = "Recently watched", style = MaterialTheme.typography.titleMedium)
                        }
                        items(
                            items = uiState.localEntries,
                            key = { entry -> "local:${entry.contentId}:${entry.watchedAtEpochMs}" }
                        ) { entry ->
                            LocalHistoryCard(entry = entry, onItemClick = onItemClick)
                        }
                    }
                }

                LibrarySource.TRAKT,
                LibrarySource.SIMKL -> {
                    val authenticated =
                        if (uiState.selectedSource == LibrarySource.TRAKT) {
                            uiState.authState.traktAuthenticated
                        } else {
                            uiState.authState.simklAuthenticated
                        }

                    if (!authenticated) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Connect ${uiState.selectedSource.name.lowercase().replaceFirstChar { it.uppercase() }} in Settings to load this provider.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        if (providerFolders.isNotEmpty()) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(providerFolders, key = { it.id }) { folder ->
                                        FilterChip(
                                            selected = folder.id == selectedFolder,
                                            onClick = { onSelectProviderFolder(folder.id) },
                                            label = { Text("${folder.label} (${folder.itemCount})") }
                                        )
                                    }
                                }
                            }
                        }

                        if (providerItems.isEmpty()) {
                            item {
                                val emptyMessage =
                                    if (providerFolders.isEmpty()) {
                                        "No provider library data available."
                                    } else {
                                        val label = providerFolders.firstOrNull { it.id == selectedFolder }?.label ?: "this folder"
                                        "No items in $label yet."
                                    }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = emptyMessage,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        } else {
                            items(
                                items = providerItems,
                                key = { item -> "${item.provider.name}:${item.folderId}:${item.contentId}:${item.addedAtEpochMs}" }
                            ) { item ->
                                ProviderHistoryCard(
                                    item = item,
                                    onItemClick = onItemClick
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalHistoryCard(
    entry: WatchHistoryEntry,
    onItemClick: (WatchHistoryEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(entry) }
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = entry.title.ifBlank { entry.contentId },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = formatEntrySubtitle(entry),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = entry.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            trailingContent = {
                Text(
                    text = entry.contentType.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

@Composable
private fun ProviderHistoryCard(
    item: ProviderLibraryItem,
    onItemClick: (WatchHistoryEntry) -> Unit
) {
    val mapped =
        WatchHistoryEntry(
            contentId = item.contentId,
            contentType = item.contentType,
            title = item.title,
            season = item.season,
            episode = item.episode,
            watchedAtEpochMs = item.addedAtEpochMs
        )
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(mapped) }
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = item.title.ifBlank { item.contentId },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = formatEntrySubtitle(mapped),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                Text(
                    text = item.folderId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

private fun formatEntrySubtitle(entry: WatchHistoryEntry): String {
    val seasonEpisode =
        when {
            entry.season != null && entry.episode != null -> "S${entry.season}E${entry.episode}"
            entry.season != null -> "S${entry.season}"
            else -> ""
        }
    val relative =
        DateUtils.getRelativeTimeSpanString(
            entry.watchedAtEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()

    return listOf(seasonEpisode, relative).filter { it.isNotBlank() }.joinToString(separator = " - ")
}

private fun LibrarySource.toProvider(): WatchProvider? {
    return when (this) {
        LibrarySource.LOCAL -> null
        LibrarySource.TRAKT -> WatchProvider.TRAKT
        LibrarySource.SIMKL -> WatchProvider.SIMKL
    }
}

private fun LibrarySource.toWatchProvider(): WatchProvider {
    return when (this) {
        LibrarySource.LOCAL -> WatchProvider.LOCAL
        LibrarySource.TRAKT -> WatchProvider.TRAKT
        LibrarySource.SIMKL -> WatchProvider.SIMKL
    }
}

private fun WatchProvider.toLibrarySource(): LibrarySource {
    return when (this) {
        WatchProvider.LOCAL -> LibrarySource.LOCAL
        WatchProvider.TRAKT -> LibrarySource.TRAKT
        WatchProvider.SIMKL -> LibrarySource.SIMKL
    }
}

private fun LibrarySource.displayName(): String {
    return when (this) {
        LibrarySource.LOCAL -> "Local"
        LibrarySource.TRAKT -> "Trakt"
        LibrarySource.SIMKL -> "Simkl"
    }
}
