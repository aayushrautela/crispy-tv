package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
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
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            return
        }
        _uiState.update { current -> current.copy(isLoading = true, statusMessage = if (current.sections.isEmpty()) "" else current.statusMessage) }
        refreshJob =
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
                    val tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext)
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
internal fun CalendarRoute(
    onBack: () -> Unit,
    onEpisodeClick: (CalendarEpisodeItem) -> Unit,
    onSeriesClick: (CalendarSeriesItem) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: CalendarViewModel = viewModel(factory = remember(context) { CalendarViewModel.factory(context) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val horizontalPadding = responsivePageHorizontalPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val scrollBehavior = appBarScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            StandardTopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            start = horizontalPadding,
            top = innerPadding.calculateTopPadding() + 16.dp,
            end = horizontalPadding,
            bottom = innerPadding.calculateBottomPadding() + 16.dp + Dimensions.PageBottomPadding,
        )

        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
            indicator = {
                Indicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isLoading,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = innerPadding.calculateTopPadding()),
                )
            },
        ) {
            when {
                uiState.isLoading && uiState.sections.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.sections.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
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
