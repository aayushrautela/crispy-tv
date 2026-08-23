package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
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
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.ui.components.CrispyScreen
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.skeletonElement
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
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
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
        _uiState.update { current ->
            current.copy(
                isInitialLoading = current.sections.isEmpty(),
                isRefreshing = current.sections.isNotEmpty(),
                statusMessage = if (current.sections.isEmpty()) "" else current.statusMessage,
            )
        }
        refreshJob =
            viewModelScope.launch {
                val snapshot = withContext(Dispatchers.IO) { calendarService.loadCalendar(System.currentTimeMillis()) }
                _uiState.value =
                    CalendarUiState(
                        isInitialLoading = false,
                        isRefreshing = false,
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
                    return CalendarViewModel(
                        calendarService = CalendarService(
                            backendClient = BackendServicesProvider.backendClient(appContext),
                            backendContextResolver = BackendContextResolverProvider.get(appContext),
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
    onEpisodeClick: (CalendarEpisodeItem, String?) -> Unit,
    onSeriesClick: (CalendarSeriesItem, String?) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: CalendarViewModel = viewModel(factory = remember(context) { CalendarViewModel.factory(context) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val horizontalPadding = responsivePageHorizontalPadding()
    val pullToRefreshState = rememberPullToRefreshState()
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollBehavior = appBarScrollBehavior()

    CrispyScreen(
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
        nestedScrollConnection = scrollBehavior.nestedScrollConnection,
        pullToRefreshState = pullToRefreshState,
        isRefreshing = uiState.isRefreshing,
        onRefresh = viewModel::refresh,
        horizontalPadding = horizontalPadding,
        topPadding = 16.dp,
        bottomPaddingExtra = 16.dp,
        verticalArrangement = Arrangement.spacedBy(22.dp),
        listState = lazyListState,
    ) {
        when {
            uiState.isInitialLoading && uiState.sections.isEmpty() -> {
                items(CALENDAR_SKELETON_SECTION_COUNT) { index ->
                    CalendarSkeletonSection(titleWidthFraction = if (index % 2 == 0) 0.34f else 0.46f)
                }
            }

            uiState.sections.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
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
                }
            }

            else -> {
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

@Composable
private fun CalendarSkeletonSection(titleWidthFraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(titleWidthFraction)
                .height(20.dp)
                .skeletonElement(pulse = false),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(CALENDAR_SKELETON_CARD_COUNT) {
                CalendarSkeletonCard()
            }
        }
    }
}

@Composable
private fun CalendarSkeletonCard() {
    Column(
        modifier = Modifier.width(Dimensions.WideCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Dimensions.WideCardAspectRatio)
                .skeletonElement(pulse = false),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(16.dp)
                .skeletonElement(pulse = false),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(12.dp)
                .skeletonElement(pulse = false),
        )
    }
}

@Composable
private fun CalendarEpisodeSection(
    title: String,
    items: List<CalendarEpisodeItem>,
    onItemClick: (CalendarEpisodeItem, String?) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(title = title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                val key = "calendar-episode-${item.titleItemId}-${item.id}"
                CalendarEpisodeCard(
                    item = item,
                    sharedElementKey = key,
                    onClick = { onItemClick(item, key) },
                )
            }
        }
    }
}

@Composable
private fun CalendarSeriesSection(
    title: String,
    items: List<CalendarSeriesItem>,
    onItemClick: (CalendarSeriesItem, String?) -> Unit,
) {
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(title = title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                val key = "calendar-series-${item.itemId}-${item.id}"
                CalendarSeriesCard(
                    item = item,
                    sharedElementKey = key,
                    onClick = { onItemClick(item, key) },
                )
            }
        }
    }
}

private const val CALENDAR_SKELETON_SECTION_COUNT = 3
private const val CALENDAR_SKELETON_CARD_COUNT = 3
