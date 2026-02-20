package com.crispy.rewrite.library

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val entries: List<WatchHistoryEntry> = emptyList()
)

class LibraryViewModel(
    private val watchHistoryService: WatchHistoryLabService
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isRefreshing = true, statusMessage = "Loading watch history...") }
                val result = runCatching { watchHistoryService.listLocalHistory(limit = 100) }
                val value = result.getOrNull()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        statusMessage = value?.statusMessage ?: (result.exceptionOrNull()?.message ?: "Failed to load"),
                        entries = value?.entries.orEmpty().sortedByDescending { entry -> entry.watchedAtEpochMs }
                    )
                }
            }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return LibraryViewModel(
                            watchHistoryService = PlaybackLabDependencies.watchHistoryServiceFactory(appContext)
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
        onNavigateToDiscover = onNavigateToDiscover
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    uiState: LibraryUiState,
    onRefresh: () -> Unit,
    onItemClick: (WatchHistoryEntry) -> Unit,
    onNavigateToDiscover: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Library") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
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
            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.entries.isEmpty()) {
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
                                    text = "Start watching from Discover or Home and your recently watched items will appear here.",
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
                    Text(
                        text = "Recently watched",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(
                    items = uiState.entries,
                    key = { entry -> "${entry.contentId}:${entry.watchedAtEpochMs}" }
                ) { entry ->
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
                                            shape = CircleShape
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
            }
        }
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
