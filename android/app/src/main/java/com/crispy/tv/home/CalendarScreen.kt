package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepositoryProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CalendarUiState(
    val isLoading: Boolean = true,
    val statusMessage: String = "",
    val sections: List<CalendarSection> = emptyList(),
)

private class CalendarViewModel(
    private val calendarService: CalendarService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { current -> current.copy(isLoading = true, statusMessage = if (current.sections.isEmpty()) "" else current.statusMessage) }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { calendarService.loadCalendar(System.currentTimeMillis()) }
            _uiState.value =
                CalendarUiState(
                    isLoading = false,
                    statusMessage = snapshot.statusMessage.orEmpty(),
                    sections = snapshot.sections,
                )
        }
    }

    companion object {
        fun factory(context: android.content.Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val tmdbEnrichmentRepository = TmdbEnrichmentRepositoryProvider.get(appContext)
                    val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                    return CalendarViewModel(
                        calendarService = CalendarService(
                            watchHistoryService = watchHistoryService,
                            tmdbEnrichmentRepository = tmdbEnrichmentRepository,
                        )
                    ) as T
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CalendarRoute(
    onBack: () -> Unit,
    onEpisodeClick: (CalendarEpisodeItem) -> Unit,
    onSeriesClick: (CalendarSeriesItem) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: CalendarViewModel = viewModel(factory = remember(context) { CalendarViewModel.factory(context) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val horizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.sections.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.sections.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = uiState.statusMessage.ifBlank { "No calendar items yet." },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = viewModel::refresh) {
                        Text("Refresh")
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (uiState.statusMessage.isNotBlank()) {
                        item {
                            Text(
                                text = uiState.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    items(uiState.sections, key = { it.key.name }) { section ->
                        when (section.key) {
                            CalendarSectionKey.NO_SCHEDULED -> {
                                CalendarSeriesSection(
                                    title = section.title,
                                    items = section.seriesItems,
                                    onItemClick = onSeriesClick,
                                )
                            }

                            else -> {
                                CalendarEpisodeSection(
                                    title = section.title,
                                    items = section.episodeItems,
                                    onItemClick = onEpisodeClick,
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(Dimensions.PageBottomPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEpisodeSection(
    title: String,
    items: List<CalendarEpisodeItem>,
    onItemClick: (CalendarEpisodeItem) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(title = title, statusMessage = "")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                CalendarEpisodeCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun CalendarSeriesSection(
    title: String,
    items: List<CalendarSeriesItem>,
    onItemClick: (CalendarSeriesItem) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(title = title, statusMessage = "")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                CalendarSeriesCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}
